package com.purenote.local

import com.purenote.local.core.PreviewBuilder
import com.purenote.local.data.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewBuilderTest {

    @Test
    fun `whitespace collapsed and long body truncated`() {
        val preview = PreviewBuilder.textPreview("  多个   空白\n与换行  ")
        assertEquals("多个 空白 与换行", preview)

        val long = "字".repeat(200)
        val cut = PreviewBuilder.textPreview(long, maxChars = 120)
        assertEquals(121, cut.length)
        assertEquals(true, cut.endsWith("…"))
    }

    @Test
    fun `checklist preview lists only undone items`() {
        val items = listOf(
            ChecklistItem("已完成项", done = true),
            ChecklistItem("待办一"),
            ChecklistItem("待办二"),
        )
        assertEquals("• 待办一\n• 待办二", PreviewBuilder.checklistPreview(items))
        assertEquals("", PreviewBuilder.checklistPreview(items.filter { it.done }))
    }
}
