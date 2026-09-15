package com.purenote.local.platform.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 设备内自动备份（规范 §14）。
 *
 * 严格按规范要求的执行顺序：
 *   新包成功 -> 校验成功 -> **记录发布成功** -> 才决定旧包是否清理。
 * 反过来的"先删旧的再建新的"会在新建失败时把唯一可用的备份也一起清掉。
 *
 * 另外两条约束：
 *  - 开始时**重新核对 storeEpoch**，不使用创建任务时缓存的任何句柄；
 *  - 检测到异常大规模删改时**暂停普通轮换**并保留异常前的恢复点。
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? PureNoteApp ?: return@withContext Result.failure()
        val store = LocalBackupStore.forApp(applicationContext)
        when (runBackupOnce(app, store, appVersion(applicationContext))) {
            Outcome.OK -> Result.success()
            // 过程中存储被恢复切换：这份包属于旧代，不作为"最近成功"，下次重建
            Outcome.STORE_CHANGED, Outcome.FAILED -> Result.retry()
        }
    }

    enum class Outcome { OK, STORE_CHANGED, FAILED }

    companion object {

        /**
         * 执行一次完整的自动备份。
         *
         * 抽成静态函数是为了让**周期任务**与**前台到期补跑**共用同一份实现——
         * 两处各写一遍必然漂移。
         */
        suspend fun runBackupOnce(
            app: PureNoteApp,
            store: LocalBackupStore,
            appVersion: String,
        ): Outcome {
            val repo = app.repository
            val epochAtStart = repo.storeEpoch
            val now = System.currentTimeMillis()
            val staged = try {
                store.newStagingFile(now)
            } catch (t: Throwable) {
                return Outcome.FAILED
            }
            return try {
                // 1) 生成新包
                repo.exportBackup(staged, appVersion)

                // 2) 校验：重新读回整包并核验清单，不通过就当作没有备份成功
                staged.inputStream().use { BackupIo(app).readBackup(it) }

                // 3) 发布 + 记录成功（此刻起这份包才算"有效"）
                val published = store.publish(staged, BackupKind.AUTO, now)
                val count = repo.noteCount()
                val stamp = repo.contentStamp()
                val previous = store.readState()

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

                // 4) 只有发布成功之后才谈清理
                if (anomalous) {
                    pinPrevious(store, published)
                } else {
                    store.rotate(now)
                }
                store.cleanupStaging()

                if (repo.storeEpoch != epochAtStart) Outcome.STORE_CHANGED else Outcome.OK
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                staged.delete()
                Outcome.FAILED
            }
        }

        /** 固定"异常发生之前"的那一份，使任何档位都轮换不掉它。 */
        private fun pinPrevious(store: LocalBackupStore, justPublished: File) {
            store.list()
                .filter { it.name != justPublished.name && !it.pinned }
                .maxByOrNull { it.createdAt }
                ?.let { store.pin(it.name) }
        }

        private fun appVersion(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
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
                    // 后台备份是重 IO：低电量时不打扰用户，宁可推迟
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
     * 前台启动时的到期补跑（规范 §14）。
     *
     * **在当前后台协程里直接跑，不交给 WorkManager 排期。**
     * 实测：交给 WorkManager 时，一次普通启动要几十秒才见备份落地
     * （JobScheduler 的派发延迟 + 约束判定），"打开过应用就等于备份过"这句话就不成立；
     * 崩溃注入也因此根本杀不到"正在写包"的时刻。
     *
     * 只有确实到期（距上次成功满 30 分钟）**且内容变过**才会执行，
     * 所以它不会在每次启动都产生一次导出。
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
        BackupWorker.runBackupOnce(app, store, appVersion(context))
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
