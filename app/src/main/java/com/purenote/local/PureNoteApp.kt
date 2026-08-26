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
        // 清理废纸篓超过 30 天的内容；补偿开机广播未触发时的提醒重排
        scope.launch {
            runCatching { repository.purgeExpiredTrash(TRASH_TTL_MS) }
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
    }
}
