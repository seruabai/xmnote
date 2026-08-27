package com.purenote.local

import com.purenote.local.core.TodoCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodoCompletionTest {
    @Test
    fun noChildrenKeepsParentState() {
        assertNull(TodoCompletion.parentDone(childCount = 0, incompleteCount = 0))
    }

    @Test
    fun allChildrenDoneCompletesParent() {
        assertEquals(true, TodoCompletion.parentDone(childCount = 3, incompleteCount = 0))
    }

    @Test
    fun anyIncompleteChildReopensParent() {
        assertEquals(false, TodoCompletion.parentDone(childCount = 3, incompleteCount = 1))
    }
}
