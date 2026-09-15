package com.purenote.local

import android.app.Application
import com.purenote.local.data.NoteRepository
import com.purenote.local.notify.AndroidAlarmSink
import com.purenote.local.notify.ReminderReconciler
import com.purenote.local.notify.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PureNoteApp : Application() {

    lateinit var repository: NoteRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = NoteRepository(this)
        Reminders.ensureChannel(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 规范 §10：附件发布成功后登记字节级事实（大小 + SHA-256）。
        // 用回调而不是让 core 直接依赖数据库：core 层依然不认识 SQLite。
        com.purenote.local.core.ImageStore.onPublished = { published ->
            scope.launch {
                runCatching {
                    repository.recordAttachment(
                        attachmentId = published.attachmentId,
                        relativeName = published.fileName,
                        sizeBytes = published.sizeBytes,
                        sha256 = published.sha256,
                    )
                }
            }
        }
        // 补偿开机广播未触发时的提醒重排
        scope.launch {
            // 阶段 A（规范 §2 / §14）：自动物理清理停用。
            // "满 30 天就永久删除"本身不是数据保护：任何一处判定写错（时钟回拨、时区、
            // trashed/trashed_at 字段误用）都会静默销毁内容，且无处可查。
            // 待历史版本（阶段 C）与完整备份（阶段 E）就位后，再由带引用保护的清理入口启用。
            if (AUTO_PURGE_ENABLED) {
                runCatching { repository.purgeExpiredTrash(TRASH_TTL_MS) }
                runCatching { repository.purgeExpiredTodoTrash(TRASH_TTL_MS) }
            }
            // 规范 §14：登记周期任务（每 libraryId 唯一），并做一次前台到期检查
            runCatching {
                val libraryId = repository.libraryId()
                com.purenote.local.platform.backup.BackupScheduler.ensureScheduled(this@PureNoteApp, libraryId)
                com.purenote.local.platform.backup.BackupScheduler
                    .runImmediatelyIfDue(this@PureNoteApp, libraryId)
            }
            // 规范 §9：启动时按数据库现状**全量重算**提醒，而不只是"把还有效的重新排一遍"。
            // 全量重算会把已经不该存在的闹钟也取消掉——换机恢复、误删、时钟变化之后
            // 残留的闹钟会在错误的时间弹出来，只补不删是清不掉的。
            runCatching {
                ReminderReconciler(repository, AndroidAlarmSink(this@PureNoteApp)).reconcileAll()
            }
        }
    }

    companion object {
        const val TRASH_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** 阶段 A 起停用；阶段 C/E 完成后连同引用保护一起恢复。 */
        private const val AUTO_PURGE_ENABLED = false
    }
}
