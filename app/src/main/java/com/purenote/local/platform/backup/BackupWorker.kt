package com.purenote.local.platform.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.purenote.local.PureNoteApp
import com.purenote.local.backup.BackupIo
import com.purenote.local.feature.backup.BackupKind
import com.purenote.local.feature.backup.BackupRetention
import com.purenote.local.feature.backup.LocalBackupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 设备内自动备份（规范 §14）。
 *
 * 严格按规范要求的执行顺序：
 *   新包成功 -> 校验成功 -> **记录发布成功** -> 才决定旧包是否清理。
 * 反过来的"先删旧的再建新的"会在新建失败时把唯一可用的备份也一起清掉。
 *
 * 另外两条约束：
 *  - Worker 开始时**重新核对 storeEpoch**，不使用创建任务时缓存的任何句柄
 *    （长期等待后活动存储可能已经被恢复切换过）；
 *  - 检测到异常大规模删改时**暂停普通轮换**并保留异常前的恢复点，
 *    否则"误删 + 自动轮换"会把能救回来的那份也轮掉。
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? PureNoteApp ?: return@withContext Result.failure()
        val repo = app.repository
        val store = LocalBackupStore.forApp(applicationContext)

        // 1) 重新核对当前存储代次（不用缓存句柄）
        val epochAtStart = repo.storeEpoch

        val now = System.currentTimeMillis()
        val staged = try {
            store.newStagingFile(now)
        } catch (t: Throwable) {
            return@withContext Result.retry()
        }

        try {
            // 2) 生成新包
            val exported = repo.exportBackup(staged, appVersion())

            // 3) 校验：重新读回整包并核验清单，不通过就当作没有备份成功
            staged.inputStream().use { BackupIo(applicationContext).readBackup(it) }

            // 4) 发布 + 记录成功（此刻起这份包才算"有效"）
            val published = store.publish(staged, BackupKind.AUTO, now)
            val count = repo.noteCount()
            val stamp = repo.contentStamp()
            val previous = store.readState()

            // 5) 异常大规模删改检测：暂停轮换，并保留异常发生前的恢复点
            val anomalous = previous.lastNoteCount >= 0 &&
                BackupRetention.isAnomalousDrop(previous.lastNoteCount, count)
            store.writeState(
                previous.copy(
                    lastSuccessAt = now,
                    lastNoteCount = count,
                    rotationPaused = anomalous,
                    lastContentStamp = stamp,
                ),
            )

            if (anomalous) {
                // 把上一份（异常发生前生成的）固定住，不从任何档位里轮换掉
                pinPrevious(store, published)
            } else {
                store.rotate(now)
            }
            store.cleanupStaging()

            // 6) 校验代次没有在过程中被切换
            if (repo.storeEpoch != epochAtStart) {
                // 存储已被恢复切换：这份包属于旧代，不作为"最近成功"。保留文件，下次重建。
                return@withContext Result.retry()
            }
            Result.success()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            staged.delete()
            // 空间不足等可重试；用退避策略，不在这里做任何清理
            Result.retry()
        }
    }

    /** 固定"异常发生之前"的那一份，使任何档位都轮换不掉它。 */
    private fun pinPrevious(store: LocalBackupStore, justPublished: java.io.File) {
        store.list()
            .filter { it.name != justPublished.name && !it.pinned }
            .maxByOrNull { it.createdAt }
            ?.let { store.pin(it.name) }
    }

    private fun appVersion(): String = runCatching {
        applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
    }.getOrNull() ?: ""
}

/**
 * 周期任务登记（规范 §14）。
 *
 * 目标间隔 30 分钟是**尽力调度**：系统会因电量、待机、配额推迟，
 * 所以界面上只能显示"最后一次实际成功时间"，不能承诺准点。
 */
object BackupScheduler {

    fun workName(libraryId: String): String =
        "purenote-local-backup-" + libraryId.ifBlank { "default" }

    suspend fun ensureScheduled(context: Context, libraryId: String) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(30, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    // 备份是重 IO：低电量时不打扰用户，宁可推迟
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(libraryId),
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 目标间隔（与周期任务一致），前台检查"是否到期"时用同一个值。 */
    const val TARGET_INTERVAL_MS = 30L * 60 * 1000

    /**
     * 前台恢复时的到期检查（规范 §14）：距上次**实际成功**已超过目标间隔，
     * 且自那以后内容确实变过，就立刻补一次。
     * 周期任务被系统推迟时，这一步保证用户"打开过应用"就等于"备份过"。
     */
    suspend fun runImmediatelyIfDue(context: Context, libraryId: String) {
        val store = LocalBackupStore.forApp(context)
        val state = store.readState()
        val now = System.currentTimeMillis()
        if (now - state.lastSuccessAt < TARGET_INTERVAL_MS) return
        val app = context.applicationContext as? PureNoteApp ?: return
        if (state.lastContentStamp >= 0 && app.repository.contentStamp() == state.lastContentStamp) {
            // 内容没变过：不必重复打包
            return
        }
        val request = androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
