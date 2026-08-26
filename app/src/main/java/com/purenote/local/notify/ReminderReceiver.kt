package com.purenote.local.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.purenote.local.R
import com.purenote.local.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val targetId = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        val kind = intent.getStringExtra(Reminders.EXTRA_KIND) ?: Reminders.KIND_NOTE
        if (targetId <= 0) return
        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = NoteRepository(appContext)
                if (kind == Reminders.KIND_TODO) {
                    val todo = repo.getTodo(targetId)
                    if (todo != null && !todo.done) {
                        post(appContext, kind, todo.id, todo.title, "待办到点了，点开处理")
                    }
                } else {
                    val note = repo.getNote(targetId)
                    if (note != null && !note.trashed) {
                        post(appContext, kind, note.id, note.title, "到点的提醒，点开查看")
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun post(context: Context, kind: String, id: Long, title: String, text: String) {
        Reminders.ensureChannel(context)
        val notification: Notification = NotificationCompat.Builder(context, Reminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(Reminders.openTargetIntent(context, kind, id))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id.toInt(), notification)
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = NoteRepository(appContext)
                repo.allFutureReminders().forEach { (id, at) ->
                    Reminders.schedule(appContext, Reminders.KIND_NOTE, id, at)
                }
                repo.allFutureTodoReminders().forEach { (id, at) ->
                    Reminders.schedule(appContext, Reminders.KIND_TODO, id, at)
                }
            } finally {
                result.finish()
            }
        }
    }
}
