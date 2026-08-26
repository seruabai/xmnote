package com.purenote.local

import com.purenote.local.core.TodoDates
import com.purenote.local.core.TodoGroup
import com.purenote.local.core.TodoGrouper
import com.purenote.local.data.RepeatRule
import com.purenote.local.data.Todo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoGrouperTest {

    private val day = 24L * 60 * 60 * 1000
    /** 以“今天正午”为基准，避免测试时间落在深夜时跨天分组的不确定 */
    private val now: Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 12)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun todo(
        id: Long,
        dueAt: Long?,
        done: Boolean = false,
        repeat: RepeatRule = RepeatRule.NONE,
    ) = Todo(
        id = id, parentId = null, title = "t$id", done = done,
        doneAt = if (done) now else null,
        dueAt = dueAt, sortIndex = 0,
        repeat = repeat,
        createdAt = now, updatedAt = now,
    )

    @Test
    fun `six mi groups are assigned correctly`() {
        val todos = listOf(
            todo(1, now - day),                 // 已过期
            todo(2, now + 3600_000),            // 今天
            todo(3, now + day + 3600_000),      // 明天
            todo(4, now + 5 * day),             // 更远
            todo(5, null),                      // 未计划
            todo(6, now - day, done = true),    // 已完成（即使过期）
            todo(7, now - 3600_000),            // 今天早些时候但时刻已过 → 滚动判定为已过期
        )
        val groups = TodoGrouper.group(todos, now)
        assertEquals(listOf(1L, 7L), groups[TodoGroup.EXPIRED]!!.map { it.id })
        assertEquals(listOf(2L), groups[TodoGroup.TODAY]!!.map { it.id })
        assertEquals(listOf(3L), groups[TodoGroup.TOMORROW]!!.map { it.id })
        assertEquals(listOf(4L), groups[TodoGroup.FUTURE]!!.map { it.id })
        assertEquals(listOf(5L), groups[TodoGroup.UNSCHEDULED]!!.map { it.id })
        assertEquals(listOf(6L), groups[TodoGroup.DONE]!!.map { it.id })
    }

    @Test
    fun `subtasks never appear in top level groups`() {
        val parent = todo(10, null)
        val sub = parent.copy(id = 11, parentId = 10)
        val groups = TodoGrouper.group(listOf(parent, sub), now)
        assertEquals(1, groups[TodoGroup.UNSCHEDULED]!!.size)
    }

    @Test
    fun `within group sorted by due time ascending`() {
        val todos = listOf(
            todo(1, now + 3 * day),
            todo(2, now + 2 * day),
            todo(3, now + day),
        )
        val groups = TodoGrouper.group(todos, now)
        assertEquals(listOf(3L), groups[TodoGroup.TOMORROW]!!.map { it.id })
        assertEquals(listOf(2L, 1L), groups[TodoGroup.FUTURE]!!.map { it.id })
    }

    @Test
    fun `finished sorted by finish time descending`() {
        val older = todo(1, null, done = true).copy(doneAt = now - 1000)
        val newer = todo(2, null, done = true).copy(doneAt = now)
        val groups = TodoGrouper.group(listOf(older, newer), now)
        assertEquals(listOf(2L, 1L), groups[TodoGroup.DONE]!!.map { it.id })
    }

    @Test
    fun `repeat next occurrence advances correctly`() {
        assertNull(TodoDates.nextOccurrence(now, RepeatRule.NONE))
        assertTrue(TodoDates.nextOccurrence(now, RepeatRule.DAILY)!! > now)
        // 周一至周五：从周六推进必须跳过周末
        val saturday = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SATURDAY)
        }.timeInMillis
        val next = TodoDates.nextOccurrence(saturday, RepeatRule.WEEKDAYS)!!
        val dow = java.util.Calendar.getInstance().apply { timeInMillis = next }
            .get(java.util.Calendar.DAY_OF_WEEK)
        assertTrue(dow in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY)
        assertTrue(TodoDates.nextOccurrence(now, RepeatRule.WEEKLY)!! >= now + 6 * day)
    }

    @Test
    fun `formatDue appends mi style repeat suffix`() {
        val text = TodoDates.formatDue(now + 3600_000, false, RepeatRule.DAILY, now)
        assertTrue(text.startsWith("今天"))
        assertTrue(text.endsWith("，每天"))
        val allDayText = TodoDates.formatDue(now + 3600_000, true, RepeatRule.NONE, now)
        assertEquals("今天", allDayText)
    }
}
