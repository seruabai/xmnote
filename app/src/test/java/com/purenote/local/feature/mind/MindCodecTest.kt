package com.purenote.local.feature.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * body 是用户唯一的数据副本，编解码必须做到：能解的绝不错，不能解的绝不崩。
 * 字符保真部分与 BackupJsonTest 同一思路。
 */
class MindCodecTest {

    @Test
    fun roundTrip_preservesHardCharacters() {
        val tricky = "引号\"与'反斜杠\\换行\n制表\temoji✅中文【标点】，。！？"
        val doc = MindDoc(root = MindNode(id = "root", label = tricky, children = listOf(
            MindNode(id = "n1", label = "子节点\n带换行", collapsed = true),
        )))
        val decoded = MindCodec.decode(MindCodec.encode(doc))
        assertEquals(tricky, decoded!!.root.label)
        assertEquals("子节点\n带换行", decoded.root.children[0].label)
        assertTrue(decoded.root.children[0].collapsed)
    }

    @Test
    fun roundTrip_keepsDeepNestingAndOrder() {
        var node = MindNode(id = "n49", label = "底")
        for (i in 48 downTo 0) {
            node = MindNode(id = "n" + i, label = "层" + i, children = listOf(node))
        }
        val doc = MindDoc(root = MindNode(id = "root", label = "根", children = listOf(node)))
        val decoded = MindCodec.decode(MindCodec.encode(doc))!!
        assertEquals(51, decoded.root.totalCount())
        assertEquals(50, decoded.root.visibleDepth() - 1)
    }

    @Test
    fun decode_rejectsGarbageWithoutThrowing() {
        assertNull(MindCodec.decode(null))
        assertNull(MindCodec.decode(""))
        assertNull(MindCodec.decode("   "))
        assertNull(MindCodec.decode("普通文本笔记的正文"))
        assertNull(MindCodec.decode("{ 截断的 json"))
        assertNull(MindCodec.decode("{\"root\":{\"id\":\"root\"}"))
    }

    @Test
    fun decode_toleratesUnknownFieldsAndMissingOnes() {
        val newer = "{\"version\":99,\"root\":{\"id\":\"root\",\"label\":\"标题\",\"future\":123}," +
            "\"view\":\"OUTLINE\",\"extraTopLevel\":true}"
        val decoded = MindCodec.decode(newer)
        assertNotNull(decoded)
        assertEquals("标题", decoded!!.root.label)
        assertEquals(MindView.OUTLINE, decoded.view)
        assertTrue(decoded.root.children.isEmpty())
    }

    @Test
    fun decodeOrNew_fallsBackToSingleRoot() {
        val doc = MindCodec.decodeOrNew("损坏内容", title = "会议记录")
        assertEquals("会议记录", doc.root.label)
        assertEquals(1, doc.root.totalCount())
        assertEquals(MindView.MIND, doc.view)
    }

    @Test
    fun emptyDocument_hasStableIdentity() {
        val doc = MindDoc()
        assertEquals(MindDoc.ROOT_ID, doc.root.id)
        assertEquals(MindDoc.DEFAULT_TITLE, doc.displayTitle)
        assertEquals("真的标题", MindDoc(root = MindNode(id = "root", label = "  真的标题  ")).displayTitle)
    }

    @Test
    fun looksLikeMindDoc_distinguishesTextNoteBody() {
        assertTrue(MindCodec.looksLikeMindDoc(MindCodec.encode(MindDoc())))
        assertTrue(!MindCodec.looksLikeMindDoc("今天买牛奶"))
    }
}
