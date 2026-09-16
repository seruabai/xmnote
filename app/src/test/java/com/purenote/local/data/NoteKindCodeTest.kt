package com.purenote.local.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * kind 列是**落库的持久化编码**：写错一个数字，笔记就会被当成另一种类型解析
 * （脑图被当文本笔记 → 树 JSON 会变成一行文字）。这里把映射锁死。
 */
class NoteKindCodeTest {

    @Test
    fun storedCodesAreStable() {
        assertEquals(0, NoteKind.TEXT.storedCode())
        assertEquals(1, NoteKind.CHECKLIST.storedCode())
        assertEquals(2, NoteKind.MIND.storedCode())
    }

    @Test
    fun decodingRoundTripsAndFallsBackToText() {
        NoteKind.entries.forEach { kind ->
            assertEquals(kind, noteKindOf(kind.storedCode()))
        }
        // 未知编码（未来版本写入的）按文本处理，至少不会崩
        assertEquals(NoteKind.TEXT, noteKindOf(-1))
        assertEquals(NoteKind.TEXT, noteKindOf(99))
    }
}
