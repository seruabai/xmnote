package com.purenote.local

import android.app.Application
import com.purenote.local.data.NoteRepository
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
            runCatching {
                repository.allFutureReminders().forEach { (id, at) ->
                    Reminders.schedule(this@PureNoteApp, Reminders.KIND_NOTE, id, at)
                }
                repository.allFutureTodoReminders().forEach { (id, at) ->
                    Reminders.schedule(this@PureNoteApp, Reminders.KIND_TODO, id, at)
                }
            }
        }
    }

    companion object {
        const val TRASH_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** 阶段 A 起停用；阶段 C/E 完成后连同引用保护一起恢复。 */
        private const val AUTO_PURGE_ENABLED = false
    }
}
