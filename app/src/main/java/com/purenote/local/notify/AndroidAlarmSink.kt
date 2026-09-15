package com.purenote.local.notify

import android.content.Context

/** [AlarmSink] 的 Android 实现。真实调用集中在 [Reminders]，协调器本身因此可以脱离设备测试。 */
class AndroidAlarmSink(private val context: Context) : AlarmSink {
    override fun schedule(kind: String, targetId: Long, at: Long) {
        Reminders.schedule(context, kind, targetId, at)
    }

    override fun cancel(kind: String, targetId: Long) {
        Reminders.cancel(context, kind, targetId)
    }
}
