package com.purenote.local

import com.purenote.local.core.PreviewBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTitleTest {

    @Test
    fun `first line becomes title`() {
        val (title, rest) = PreviewBuilder.splitTitle("购物清单\n牛奶\n鸡蛋")
        assertEquals("购物清单", title)
        assertEquals("牛奶\n鸡蛋", rest)
    }

    @Test
    fun `single line has no rest`() {
        val (title, rest) = PreviewBuilder.splitTitle("只有一行")
        assertEquals("只有一行", title)
        assertEquals("", rest)
    }

    @Test
    fun `leading blank lines are skipped`() {
        val (title, _) = PreviewBuilder.splitTitle("\n\n  标题行\n内容")
        assertEquals("标题行", title)
    }

    @Test
    fun `join avoids duplicated first line`() {
        val merged = PreviewBuilder.joinTitleBody("T", "T\nB")
        assertEquals("T\nB", merged)
        val merged2 = PreviewBuilder.joinTitleBody("T", "B")
        assertEquals("T\nB", merged2)
        val merged3 = PreviewBuilder.joinTitleBody("", "B")
        assertEquals("B", merged3)
    }

    @Test
    fun `blank body returns blank pair`() {
        val (title, rest) = PreviewBuilder.splitTitle("   \n ")
        assertEquals("" to "", title to rest)
    }
}
