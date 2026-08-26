package com.purenote.local.core

import com.purenote.local.data.RepeatRule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 小米待办的时间展示与重复推进规则（纯函数便于测试） */
object TodoDates {

    fun startOfDay(ts: Long, offsetDays: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (offsetDays != 0) cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return cal.timeInMillis
    }

    /** 整天事件的到期取当天 23:59:59 */
    fun endOfDay(ts: Long): Long = startOfDay(ts) + 24L * 60 * 60 * 1000 - 1

    /** 今天/明天的快捷提醒默认取 08:00（对应小米 createDayRemindTimeFromToday） */
    fun dayRemindFromToday(offsetDays: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = startOfDay(now, offsetDays)
            set(Calendar.HOUR_OF_DAY, 8)
        }
        return cal.timeInMillis
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val md = SimpleDateFormat("M月d日", Locale.getDefault())
    private val ymd = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

    /** 日部分：今天/明天/昨天 + M月d日 / yyyy年M月d日 */
    fun dayLabel(ts: Long, now: Long = System.currentTimeMillis()): String = when (ts) {
        in startOfDay(now)..endOf(now) -> "今天"
        in startOfDay(now, 1)..endOf(now, 1) -> "明天"
        in startOfDay(now, -1)..endOf(now, -1) -> "昨天"
        else -> {
            val sameYear = Calendar.getInstance().apply { timeInMillis = ts }
                .get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            if (sameYear) md.format(Date(ts)) else ymd.format(Date(ts))
        }
    }

    private fun endOf(now: Long, offsetDays: Int = 0): Long = startOfDay(now, offsetDays + 1) - 1

    /**
     * 到期时间的完整展示：今天 09:00 · 明天 · M月d日 HH:mm · yyyy年M月d日；
     * 整天事件不带时刻；重复待办追加 ，每天/，每周/，周一至周五/，工作日/，每月/，每年
     */
    fun formatDue(dueAt: Long, allDay: Boolean, repeat: RepeatRule, now: Long = System.currentTimeMillis()): String {
        val day = dayLabel(dueAt, now)
        val sb = StringBuilder(day)
        if (!allDay) sb.append(' ').append(hm.format(Date(dueAt)))
        when (repeat) {
            RepeatRule.NONE -> Unit
            RepeatRule.DAILY -> sb.append("，每天")
            RepeatRule.WEEKLY -> sb.append("，每周")
            RepeatRule.WEEKDAYS -> sb.append("，周一至周五")
            RepeatRule.WORKDAYS -> sb.append("，工作日")
            RepeatRule.MONTHLY -> sb.append("，每月")
            RepeatRule.YEARLY -> sb.append("，每年")
        }
        return sb.toString()
    }

    /** 无日期时的占位（未计划待办不显示时间行） */

    /** 完成后推进到下一次到期；无法推进时返回 null */
    fun nextOccurrence(dueAt: Long, repeat: RepeatRule, now: Long = System.currentTimeMillis()): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = dueAt }
        fun advance(field: Int, amount: Int) = cal.apply { add(field, amount) }.timeInMillis
        return when (repeat) {
            RepeatRule.NONE -> null
            RepeatRule.DAILY -> advance(Calendar.DAY_OF_YEAR, 1)
            RepeatRule.WEEKLY -> advance(Calendar.DAY_OF_YEAR, 7)
            RepeatRule.WEEKDAYS, RepeatRule.WORKDAYS -> {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                } while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                    cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                )
                cal.timeInMillis
            }
            RepeatRule.MONTHLY -> advance(Calendar.MONTH, 1)
            RepeatRule.YEARLY -> advance(Calendar.YEAR, 1)
        }?.takeIf { it > dueAt }
    }

    /** 分组标题：已过期/今天 M月d日/明天 M月d日/更远/未计划/已完成 */
    fun groupTitle(group: TodoGroup, now: Long = System.currentTimeMillis()): String = when (group) {
        TodoGroup.EXPIRED -> "已过期"
        TodoGroup.TODAY -> "今天 " + md.format(Date(startOfDay(now)))
        TodoGroup.TOMORROW -> "明天 " + md.format(Date(startOfDay(now, 1)))
        TodoGroup.FUTURE -> "更远"
        TodoGroup.UNSCHEDULED -> "未计划"
        TodoGroup.DONE -> "已完成"
    }
}
