package com.purenote.local.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.purenote.local.PureNoteApp
import com.purenote.local.R
import com.purenote.local.core.TodoDates
import com.purenote.local.data.NoteRepository
import com.purenote.local.data.RepeatRule
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
                // 规范 §6.1：Receiver 不自行构造数据层，统一取 Application 持有的那一个。
                // 自行构造会让进程里出现第二个 DatabaseProvider，各自解析活动指针
                // -> 可能生成两个库 -> 写进去的提醒状态读不出来。
                val repo = (appContext as PureNoteApp).repository
                if (kind == Reminders.KIND_TODO) {
                    val todo = repo.getTodo(targetId)
                    if (todo != null && !todo.done) {
                        post(appContext, kind, todo.id, todo.title, "待办到点了，点开处理")
                    }
                } else {
                    val note = repo.getNote(targetId)
                    if (note != null && !note.trashed) {
                        post(appContext, kind, note.id, note.title, "到点的提醒，点开查看")
                        // 重复提醒：发完通知推进到下一次并重排闹钟（一次性提醒不动）
                        if (note.repeat != RepeatRule.NONE) {
                            val next = note.remindAt?.let { TodoDates.nextOccurrence(it, note.repeat) }
                            if (next != null) {
                                repo.setReminder(note.id, next, note.repeat, note.allDay)
                                // 规范 §9：期望已随 setReminder 的事务落库，这里交给协调器推送
                                ReminderReconciler(repo, AndroidAlarmSink(appContext)).applyPending()
                            } else {
                                repo.setReminder(note.id, null)
                            }
                        }
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun post(context: Context, kind: String, id: Long, title: String, text: String) {
        Reminders.ensureChannel(context)
        val strong = context.getSharedPreferences("pure_prefs", Context.MODE_PRIVATE)
            .getBoolean("strong_reminder", false)
        val notification: Notification = NotificationCompat.Builder(context, Reminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(text)
            .setAutoCancel(!strong)
            .setOngoing(strong)
            .setPriority(if (strong) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(Reminders.openTargetIntent(context, kind, id))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
            .apply {
                if (strong) flags = flags or Notification.FLAG_INSISTENT
            }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Reminders.notifyId(kind, id), notification)
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val appContext = context.applicationContext
                val result = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // 规范 §6.1：Receiver 不自行构造数据层，统一取 Application 持有的那一个。
                        // 自行构造会让进程里出现第二个 DatabaseProvider，各自解析活动指针
                        // -> 可能生成两个库 -> 写进去的提醒状态读不出来。
                        val repo = (appContext as PureNoteApp).repository

                        // 规范 §9：开机后按数据库现状**全量重算**，而不是"把还有效的重排一遍"。
                        // 原来那个循环只补不删——换机恢复、误删、时区变化之后残留的闹钟
                        // 会在错误的时间弹出来，只补是清不掉的。
                        // 与 PureNoteApp 启动时走的是同一条路径，不再各写一份。
                        ReminderReconciler(repo, AndroidAlarmSink(appContext)).reconcileAll()
                        // 用户此前开启了速记侧栏则开机恢复（BOOT_COMPLETED 允许拉起前台服务）
                        if (QuickCaptureService.isEnabled(appContext)) {
                            QuickCaptureService.start(appContext)
                        }
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }
}
