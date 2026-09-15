package com.purenote.local.core

import com.purenote.local.data.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格式闸口的边界：body 里可能同时存在 v1/v2/v3 三种内容（迁移未完成、
 * 迁移失败、历史写入漏标版本号），任何一种都不能解析成空白或乱码。
 */
class NoteBodyTest {

    private val markup = "# 标题\n- [ ] 待办\n![](pic.jpg)"
    private val doc = LegacyBody.fromText(markup)
    private val json = NoteBody.encode(doc)

    @Test
    fun readsLegacyMarkdownByVersion() {
        assertEquals(doc, NoteBody.decodeText(markup, formatVersion = 2))
        assertEquals(doc, NoteBody.decodeText(markup, formatVersion = 1))
    }

    @Test
    fun readsBlockDocumentByVersion() {
        assertEquals(doc, NoteBody.decodeText(json, formatVersion = 3))
    }

    @Test
    fun sniffsBlockDocumentEvenWhenVersionSaysLegacy() {
        // 历史写入路径漏写版本号：内容是块文档但标记为 v2，必须按内容识别
        val sniffed = NoteBody.decodeText(json, formatVersion = 2)
        assertEquals(doc, sniffed)
        // 嵌入块的文字为空，文件名在 fileId 里
        assertEquals(listOf("标题", "待办", ""), sniffed.blocks.map { it.text })
        assertEquals("pic.jpg", sniffed.blocks[2].fileId)
        assertEquals(BlockType.IMAGE, sniffed.blocks[2].type)
    }

    @Test
    fun brokenBlockDocumentFallsBackToMarkdownInsteadOfBlank() {
        // 版本号说 v3、内容却是坏的：宁可当纯文本显示，也不能显示空白
        val broken = "{\"blocks\":[{\"id\":"
        val decoded = NoteBody.decodeText(broken, formatVersion = 3)
        assertEquals(broken, decoded.blocks.single().text)
    }

    @Test
    fun legacyV1PrivateMarkersGoThroughPuaMigrationFirst() {
        // v1 正文是 PUA 私有标记，不是 Markdown：直接当 Markdown 解析会把标记原样显示
        val pua = "\uE000会议记录\uE001\n正文一行"
        val migrated = NoteMarkup.migrateBodyV1toV2(pua)
        val decoded = NoteBody.decodeText(pua, formatVersion = 1)
        assertEquals(
            "v1 必须先过 PUA→Markdown 转换再解析",
            LegacyBody.fromText(migrated),
            decoded,
        )
        assertEquals(migrated, NoteBody.toMarkup(decoded))
    }

    @Test
    fun emptyBodyYieldsSingleEmptyBlock() {
        assertEquals(1, NoteBody.decodeText(null, formatVersion = 3).blocks.size)
        assertEquals(1, NoteBody.decodeText("", formatVersion = 2).blocks.size)
    }

    @Test
    fun checklistDecodesBothFormats() {
        val items = listOf(ChecklistItem("甲", done = true), ChecklistItem("乙", done = false))
        val legacy = ChecklistCodec.encode(items)
        val blocks = LegacyBody.fromChecklist(items)

        assertEquals(items, NoteBody.toChecklistItems(NoteBody.decodeChecklist(legacy, formatVersion = 2)))
        assertEquals(items, NoteBody.toChecklistItems(NoteBody.decodeChecklist(NoteBody.encode(blocks), formatVersion = 3)))
        assertEquals(items, NoteBody.toChecklistItems(NoteBody.decodeChecklist(NoteBody.encode(blocks), formatVersion = 2)))
    }

    @Test
    fun encodeFromLegacyProducesBlockDocument() {
        val encoded = NoteBody.encodeFromLegacy(isChecklist = false, body = markup, items = emptyList())
        assertTrue(RichDocCodec.looksLikeBlockDoc(encoded))
        assertEquals(doc, RichDocCodec.decode(encoded))

        val checklist = NoteBody.encodeFromLegacy(
            isChecklist = true,
            body = "",
            items = listOf(ChecklistItem("甲", done = false)),
        )
        assertEquals(BlockType.TODO, RichDocCodec.decode(checklist)!!.blocks.single().type)
    }

    @Test
    fun markupRoundTripThroughFacadeIsLossless() {
        assertEquals(markup, NoteBody.toMarkup(NoteBody.decodeText(markup, formatVersion = 2)))
        assertEquals(markup, NoteBody.toMarkup(NoteBody.decodeText(json, formatVersion = 3)))
    }

    // ---- 迁移用的纯函数 ----

    @Test
    fun upgradeStoredBodyConvertsLegacyAndReportsNewVersion() {
        val (body, version) = NoteBody.upgradeStoredBody(false, markup, formatVersion = 2)!!
        assertEquals(3, version)
        assertTrue(RichDocCodec.looksLikeBlockDoc(body))
        assertEquals(doc, RichDocCodec.decode(body))
    }

    @Test
    fun upgradeStoredBodySkipsRowsAlreadyAtV3() {
        assertNull(NoteBody.upgradeStoredBody(false, json, formatVersion = 3))
        assertNull(NoteBody.upgradeStoredBody(false, json, formatVersion = 4))
    }

    @Test
    fun upgradeStoredBodyIsIdempotent() {
        val (body, version) = NoteBody.upgradeStoredBody(false, markup, formatVersion = 2)!!
        assertNull("第二次迁移必须无事可做", NoteBody.upgradeStoredBody(false, body, formatVersion = version))
    }

    @Test
    fun upgradeStoredBodyHandlesChecklistRows() {
        val legacy = ChecklistCodec.encode(listOf(ChecklistItem("甲", done = true)))
        val (body, version) = NoteBody.upgradeStoredBody(true, legacy, formatVersion = 2)!!
        assertEquals(3, version)
        assertEquals(listOf(ChecklistItem("甲", done = true)), NoteBody.toChecklistItems(RichDocCodec.decode(body)!!))
    }

    @Test
    fun upgradeStoredBodyToleratesGarbage() {
        // 坏数据不抛异常，转换结果至少保留可读文字，版本照样推进
        val (body, version) = NoteBody.upgradeStoredBody(true, "不是清单编码", formatVersion = 2)!!
        assertEquals(3, version)
        assertNotNull(RichDocCodec.decode(body))
    }

    @Test
    fun upgradeStoredBodyOnEmptyTextStaysEmpty() {
        val (body, version) = NoteBody.upgradeStoredBody(false, "", formatVersion = 2)!!
        assertEquals(3, version)
        assertEquals("", NoteBody.toMarkup(RichDocCodec.decode(body)!!))
    }

    // ---- 旧格式表达能力守卫 ----

    @Test
    fun markupExpressibleDocumentsAreNotFlagged() {
        assertFalse(NoteBody.hasMarkupInexpressibleContent(doc))
        assertFalse(NoteBody.hasMarkupInexpressibleContent(NoteBody.decodeChecklist("", 3)))
    }

    @Test
    fun documentsWithInlineStylesOrLinksAreFlagged() {
        val styled = RichDoc(
            blocks = listOf(
                RichBlock(id = "b0", fragments = listOf(Fragment("加粗", bold = true))),
            ),
        )
        assertTrue(NoteBody.hasMarkupInexpressibleContent(styled))

        val linked = RichDoc(
            blocks = listOf(RichBlock(id = "b0", type = BlockType.LINK, href = "https://a.b")),
        )
        assertTrue(NoteBody.hasMarkupInexpressibleContent(linked))
    }
}
