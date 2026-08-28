package com.purenote.local.ui

import com.purenote.local.NoteTextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTypeScaleTest {
    @Test
    fun defaultScaleMatchesEditorAndCardDesignBaseline() {
        val scale = NoteTextSize.DEFAULT.typeScale()

        assertEquals(31f, scale.editorTitleSp)
        assertEquals(18f, scale.editorBodySp)
        assertEquals(17f, scale.cardTitleSp)
        assertEquals(15f, scale.cardBodySp)
    }

    @Test
    fun everyUserFacingNoteSizeGrowsMonotonically() {
        val small = NoteTextSize.SMALL.typeScale()
        val normal = NoteTextSize.DEFAULT.typeScale()
        val large = NoteTextSize.LARGE.typeScale()

        assertTrue(small.editorTitleSp < normal.editorTitleSp)
        assertTrue(normal.editorTitleSp < large.editorTitleSp)
        assertTrue(small.editorBodySp < normal.editorBodySp)
        assertTrue(normal.editorBodySp < large.editorBodySp)
        assertTrue(small.checklistSp < normal.checklistSp)
        assertTrue(normal.checklistSp < large.checklistSp)
        assertTrue(small.cardTitleSp < normal.cardTitleSp)
        assertTrue(normal.cardTitleSp < large.cardTitleSp)
        assertTrue(small.cardBodySp < normal.cardBodySp)
        assertTrue(normal.cardBodySp < large.cardBodySp)
    }
}
