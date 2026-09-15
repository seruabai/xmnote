package com.purenote.local.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichDocCodecTest {

    @Test
    fun roundTrip_preservesTrickyCharactersAndStructure() {
        val tricky = "引号\"与'反斜杠\\换行\n制表\temoji✅中文【标点】"
        val doc = RichDoc(
            blocks = listOf(
                RichBlock(
                    id = "b0",
                    type = BlockType.TODO,
                    checked = true,
                    number = 7,
                    headingLevel = 2,
                    indentTail = true,
                    fragments = listOf(
                        Fragment("加粗", bold = true),
                        Fragment(tricky),
                        Fragment("链接", link = "https://example.com/a?b=1&c=2"),
                    ),
                ),
                RichBlock(id = "b1", type = BlockType.IMAGE, fileId = "img_1.jpg", imageDesc = "描述", imageShow = "full"),
            ),
        )
        assertEquals(doc, RichDocCodec.decode(RichDocCodec.encode(doc)))
    }

    @Test
    fun decode_rejectsGarbageWithoutThrowing() {
        assertNull(RichDocCodec.decode(null))
        assertNull(RichDocCodec.decode(""))
        assertNull(RichDocCodec.decode("普通正文"))
        assertNull(RichDocCodec.decode("{ 截断"))
        assertNull(RichDocCodec.decode("{\"blocks\":[{\"id\":\"b0\""))
    }

    @Test
    fun decode_toleratesUnknownFieldsAndMissingOnes() {
        val newer = "{\"version\":99,\"blocks\":[{\"id\":\"b0\",\"future\":true}],\"extra\":1}"
        val decoded = RichDocCodec.decode(newer)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.blocks.size)
        assertEquals(BlockType.TEXT, decoded.blocks[0].type)
        assertTrue(decoded.blocks[0].fragments.isEmpty())
    }

    @Test
    fun decodeOrNewFallsBackToSingleBlock() {
        val doc = RichDocCodec.decodeOrNew("坏数据", title = "会议记录")
        assertEquals(1, doc.blocks.size)
        assertEquals("会议记录", doc.blocks[0].text)
        assertEquals(RichDocCodec.CURRENT_VERSION, doc.version)
    }

    @Test
    fun looksLikeBlockDocDistinguishesLegacyBody() {
        assertTrue(RichDocCodec.looksLikeBlockDoc(RichDocCodec.encode(RichDoc())))
        assertTrue(!RichDocCodec.looksLikeBlockDoc("# 标题\n- [ ] 甲"))
    }

    @Test
    fun defaultDocumentIsUsable() {
        val doc = RichDoc()
        assertEquals(1, doc.blocks.size)
        assertEquals(RichDoc.FIRST_BLOCK_ID, doc.blocks[0].id)
        assertEquals("", doc.derivedTitle())
    }
}
