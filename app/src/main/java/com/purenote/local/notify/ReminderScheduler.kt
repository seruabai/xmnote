package com.purenote.local.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.purenote.local.MainActivity
import com.purenote.local.R

/** 笔记与待办共用的提醒调度 */
object Reminders {

    const val CHANNEL_ID = "note_reminders"
    const val KIND_NOTE = "note"
    const val KIND_TODO = "todo"
    const val EXTRA_KIND = "target_kind"
    const val EXTRA_ID = "target_id"

    private const val BASE_NOTE = 4_100_000
    private const val BASE_TODO = 4_200_000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /** 精确闹钟不可用时退化为窗口闹钟，保证提醒仍会送达 */
    fun schedule(context: Context, kind: String, targetId: Long, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, kind, targetId)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context, kind: String, targetId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, kind, targetId))
    }

    fun openTargetIntent(context: Context, kind: String, targetId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(kind, targetId),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_ID, targetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pendingIntent(context: Context, kind: String, targetId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.purenote.local.action.REMIND"
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_ID, targetId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(kind, targetId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(kind: String, id: Long): Int =
        ((if (kind == KIND_TODO) BASE_TODO else BASE_NOTE) + id).toInt()
}
