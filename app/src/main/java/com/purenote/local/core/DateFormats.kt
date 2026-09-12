package com.purenote.local.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 全应用统一的日期时间展示格式。
 *
 * 同一个格式以前散落在待办、笔记卡片、废纸篓、悬浮侧栏多处，改文案要满仓库找。
 * 现在所有展示格式只在这里定义，各处按语义调用。
 */
object DateFormats {

    /** M月d日 */
    fun monthDay(ts: Long): String = formatter("M月d日").format(Date(ts))

    /** yyyy年M月d日 */
    fun yearMonthDay(ts: Long): String = formatter("yyyy年M月d日").format(Date(ts))

    /** HH:mm */
    fun hourMinute(ts: Long): String = formatter("HH:mm").format(Date(ts))

    /** yyyy年M月d日 HH:mm */
    fun yearMonthDayHourMinute(ts: Long): String = formatter("yyyy年M月d日 HH:mm").format(Date(ts))

    /** 同年只显示 M月d日，跨年才带年份 */
    fun smartDate(ts: Long, now: Long = System.currentTimeMillis()): String =
        if (sameYear(ts, now)) monthDay(ts) else yearMonthDay(ts)

    private fun sameYear(a: Long, b: Long): Boolean =
        year(a) == year(b)

    private fun year(ts: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)

    // SimpleDateFormat 非线程安全，按线程各持一份复用
    private val threadFormatters = ThreadLocal.withInitial { HashMap<String, SimpleDateFormat>() }

    private fun formatter(pattern: String): SimpleDateFormat =
        threadFormatters.get()!!.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
}
