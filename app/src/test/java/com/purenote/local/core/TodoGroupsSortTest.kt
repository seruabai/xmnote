package com.purenote.local.core

import com.purenote.local.data.RepeatRule
import com.purenote.local.data.Todo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 待办主列表排序参数化测试：编辑保持原位、新建置顶、完成沉底、拖拽序生效 */
class TodoGroupsSortTest {

    private fun todo(
        id: Long,
        createdAt: Long,
        updatedAt: Long = createdAt,
        done: Boolean = false,
        sortIndex: Int = 0,
    ) = Todo(
        id = id,
        parentId = null,
        title = "待办$id",
        done = done,
        doneAt = if (done) updatedAt else null,
        dueAt = null,
        sortIndex = sortIndex,
        createdAt = createdAt,
        updatedAt = updatedAt,
        repeat = RepeatRule.NONE,
    )

    @Test
    fun `编辑只改updatedAt不影响位置`() {
        val a = todo(1, createdAt = 100)
        val b = todo(2, createdAt = 200)
        assertEquals(listOf(2L, 1L), TodoGrouper.sortRootTodos(listOf(a, b)).map { it.id })
        // 模拟编辑 a：updatedAt 更新到最新,但位置仍应保持
        val editedA = a.copy(title = "待办1改", updatedAt = 999)
        assertEquals(listOf(2L, 1L), TodoGrouper.sortRootTodos(listOf(editedA, b)).map { it.id })
    }

    @Test
    fun `新建待办出现在顶部`() {
        val a = todo(1, createdAt = 100)
        val b = todo(2, createdAt = 200)
        val c = todo(3, createdAt = 300)
        assertEquals(listOf(3L, 2L, 1L), TodoGrouper.sortRootTodos(listOf(c, a, b)).map { it.id })
    }

    @Test
    fun `已完成沉底且按完成时间倒序`() {
        val a = todo(1, createdAt = 100)
        val b = todo(2, createdAt = 200, done = true, updatedAt = 500)
        val c = todo(3, createdAt = 300, done = true, updatedAt = 800)
        assertEquals(listOf(1L, 3L, 2L), TodoGrouper.sortRootTodos(listOf(c, a, b)).map { it.id })
    }

    @Test
    fun `拖拽sortIndex优先于创建时间`() {
        val a = todo(1, createdAt = 100)
        val b = todo(2, createdAt = 200, sortIndex = -1)
        // b 被拖到最前(sortIndex 更小),之后编辑 a 也不改变
        assertEquals(listOf(2L, 1L), TodoGrouper.sortRootTodos(listOf(a, b)).map { it.id })
        val editedA = a.copy(updatedAt = 999)
        assertEquals(listOf(2L, 1L), TodoGrouper.sortRootTodos(listOf(editedA, b)).map { it.id })
    }

    @Test
    fun `子任务不参与主列表排序`() {
        val parent = todo(1, createdAt = 100)
        val sub = Todo(
            id = 9, parentId = 1, title = "子项", done = false, doneAt = null,
            dueAt = null, sortIndex = 0, createdAt = 50, updatedAt = 50,
        )
        assertEquals(listOf(1L), TodoGrouper.sortRootTodos(listOf(sub, parent)).map { it.id })
    }
}
