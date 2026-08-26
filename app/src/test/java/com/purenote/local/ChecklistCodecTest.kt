package com.purenote.local

import com.purenote.local.core.ChecklistCodec
import com.purenote.local.data.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistCodecTest {

    @Test
    fun `round trip keeps text and state`() {
        val items = listOf(
            ChecklistItem("买牛奶", done = true),
            ChecklistItem("写周报"),
            ChecklistItem("带空格 与 emoji ✅"),
        )
        val encoded = ChecklistCodec.encode(items)
        val decoded = ChecklistCodec.decode(encoded)
        assertEquals(items, decoded)
    }

    @Test
    fun `newlines and separator inside item text are neutralized`() {
        val items = listOf(ChecklistItem("第一行\n第二行${ChecklistCodec.SEP}结尾"))
        val decoded = ChecklistCodec.decode(ChecklistCodec.encode(items))
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].text.contains("第二行"))
        assertTrue(!decoded[0].text.contains("\n"))
    }

    @Test
    fun `plain lines degrade to undone items and blanks drop`() {
        val decoded = ChecklistCodec.decode("没有标志的行\n\n1${ChecklistCodec.SEP}正常")
        assertEquals(
            listOf(ChecklistItem("没有标志的行"), ChecklistItem("正常", done = true)),
            decoded,
        )
    }

    @Test
    fun `empty input yields empty list`() {
        assertTrue(ChecklistCodec.decode("").isEmpty())
        assertEquals("", ChecklistCodec.encode(emptyList()))
    }
}
