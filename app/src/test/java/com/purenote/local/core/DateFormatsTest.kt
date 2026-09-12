package com.purenote.local.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** DateFormats 是纯函数，跨年/同年分支用固定时间戳验证（时区无关：两个时间戳同处一年内）。 */
class DateFormatsTest {

    private fun fixed(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            set(y, m - 1, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun monthDay_formatsChineseMonthDay() {
        assertEquals("3月5日", DateFormats.monthDay(fixed(2026, 3, 5, 9, 7)))
    }

    @Test
    fun yearMonthDay_includesYear() {
        assertEquals("2026年3月5日", DateFormats.yearMonthDay(fixed(2026, 3, 5, 9, 7)))
    }

    @Test
    fun hourMinute_zeroPadded() {
        assertEquals("09:07", DateFormats.hourMinute(fixed(2026, 3, 5, 9, 7)))
    }

    @Test
    fun yearMonthDayHourMinute_combined() {
        assertEquals("2026年3月5日 09:07", DateFormats.yearMonthDayHourMinute(fixed(2026, 3, 5, 9, 7)))
    }

    @Test
    fun smartDate_sameYear_omitsYear() {
        val now = fixed(2026, 6, 1, 12, 0)
        assertEquals("3月5日", DateFormats.smartDate(fixed(2026, 3, 5, 9, 7), now))
    }

    @Test
    fun smartDate_differentYear_includesYear() {
        val now = fixed(2026, 6, 1, 12, 0)
        assertEquals("2025年12月31日", DateFormats.smartDate(fixed(2025, 12, 31, 9, 7), now))
    }
}
