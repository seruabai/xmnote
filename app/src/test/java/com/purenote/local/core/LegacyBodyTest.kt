package com.purenote.local.core

import com.purenote.local.data.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 升级器是"用户数据只此一份"的一环：解析不一致等于改坏人家的笔记。
 * 这里逐种旧语法核对，并强制**往返无损**。
 */
class LegacyBodyTest {

    @Test
    fun parsesEveryLegacyTagIntoTheRightBlockType() {
        val body = listOf(
            "普通正文",
            "# 一级标题",
            "## 二级标题",
            "### 三级标题",
            "- [ ] 待办",
            "- [x] 已完成",
            "- 项目符号",
            "3. 有序第三项",
            "> 引用",
            NoteMarkup.INDENT_PREFIX + "首行缩进",
        ).joinToString("\n")

        val types = LegacyBody.fromText(body).blocks.map { it.type }
        assertEquals(
            listOf(
                BlockType.TEXT, BlockType.TEXT, BlockType.TEXT, BlockType.TEXT,
                BlockType.TODO, BlockType.TODO, BlockType.ITEM, BlockType.ITEM,
                BlockType.QUOTE, BlockType.TEXT,
            ),
            types,
        )

        val blocks = LegacyBody.fromText(body).blocks
        assertEquals(0, blocks[0].headingLevel)
        assertEquals(1, blocks[1].headingLevel)
        assertEquals(3, blocks[3].headingLevel)
        assertFalse(blocks[4].checked)
        assertTrue(blocks[5].checked)
        assertEquals(0, blocks[6].number)
        assertEquals(3, blocks[7].number)
        assertTrue(blocks[9].indentHead)
        assertEquals("首行缩进", blocks[9].text)
        assertEquals("三级标题", blocks[3].text)
        assertEquals("有序第三项", blocks[7].text)
    }

    @Test
    fun roundTripIsLosslessForRepresentativeBody() {
        val body = listOf(
            "会议记录",
            "# 议题一",
            "- [ ] 确认排期",
            "- [x] 已发通知",
            "- 无序号项",
            "7. 第七项",
            "> 引用一行",
            NoteMarkup.INDENT_PREFIX + "缩进段落" + NoteMarkup.INDENT_SUFFIX,
            NoteMarkup.IMG_PREFIX + "img_2026.jpg" + NoteMarkup.IMG_SUFFIX,
            NoteMarkup.IMG_PREFIX + "aud_2026.mp3" + NoteMarkup.IMG_SUFFIX,
            "",
            "结尾一段",
        ).joinToString("\n")

        val doc = LegacyBody.fromText(body)
        assertEquals("往返必须逐字一致", body, LegacyBody.toText(doc))
    }

    @Test
    fun emptyAndNewlineOnlyBodiesRoundTrip() {
        assertEquals("", LegacyBody.toText(LegacyBody.fromText("")))
        assertEquals("\n", LegacyBody.toText(LegacyBody.fromText("\n")))
        assertEquals("a\n", LegacyBody.toText(LegacyBody.fromText("a\n")))
        assertEquals(1, LegacyBody.fromText("").blocks.size)
    }

    @Test
    fun hugeNumberPrefixStaysPlainText() {
        // 回归：曾经用 toInt() 解析序号，"13800138000. 张三" 抛异常导致首页崩溃
        val block = LegacyBody.fromText("13800138000. 张三").blocks.single()
        assertEquals(BlockType.TEXT, block.type)
        assertEquals("13800138000. 张三", block.text)
        assertEquals("13800138000. 张三", LegacyBody.toText(LegacyBody.fromText("13800138000. 张三")))
    }

    @Test
    fun audioLineBecomesSoundBlockAndImageLineStaysImage() {
        val doc = LegacyBody.fromText(
            NoteMarkup.IMG_PREFIX + "aud_001.mp3" + NoteMarkup.IMG_SUFFIX + "\n" +
                NoteMarkup.IMG_PREFIX + "pic.jpg" + NoteMarkup.IMG_SUFFIX,
        )
        assertEquals(BlockType.SOUND, doc.blocks[0].type)
        assertEquals("aud_001.mp3", doc.blocks[0].fileId)
        assertEquals(BlockType.IMAGE, doc.blocks[1].type)
        assertEquals(listOf("aud_001.mp3", "pic.jpg"), doc.attachmentNames())
    }

    @Test
    fun headingOnImageLineSurvivesRoundTrip() {
        val body = "# " + NoteMarkup.IMG_PREFIX + "pic.jpg" + NoteMarkup.IMG_SUFFIX
        val block = LegacyBody.fromText(body).blocks.single()
        assertEquals(BlockType.IMAGE, block.type)
        assertEquals(1, block.headingLevel)
        assertEquals(body, LegacyBody.toText(LegacyBody.fromText(body)))
    }

    @Test
    fun checklistItemsBecomeTodoBlocksAndBack() {
        val items = listOf(
            ChecklistItem("买牛奶", done = true),
            ChecklistItem("取快递", done = false),
        )
        val doc = LegacyBody.fromChecklist(items)
        assertEquals(listOf(BlockType.TODO, BlockType.TODO), doc.blocks.map { it.type })
        assertEquals(1 to 2, doc.checklistProgress())
        assertEquals(items, LegacyBody.toChecklist(doc))
    }

    @Test
    fun emptyChecklistYieldsSingleEmptyBlock() {
        val doc = LegacyBody.fromChecklist(emptyList())
        assertEquals(1, doc.blocks.size)
        assertEquals(BlockType.TEXT, doc.blocks[0].type)
        assertTrue(LegacyBody.toChecklist(doc).isEmpty())
    }

    @Test
    fun upgradeDispatchesByNoteKind() {
        val text = LegacyBody.upgrade(isChecklist = false, rawBody = "- [ ] 甲")
        assertEquals(BlockType.TODO, text.blocks.single().type)

        val checklist = LegacyBody.upgrade(
            isChecklist = true,
            rawBody = ChecklistCodec.encode(listOf(ChecklistItem("甲", done = false))),
        )
        assertEquals(BlockType.TODO, checklist.blocks.single().type)
        assertEquals("甲", checklist.blocks.single().text)
    }

    @Test
    fun upgradeIsDeterministicAndIdempotent() {
        val body = "# 标题\n- [ ] 甲\n![](x.png)"
        assertEquals(LegacyBody.fromText(body), LegacyBody.fromText(body))
        val once = LegacyBody.fromText(body)
        val twice = LegacyBody.fromText(LegacyBody.toText(once))
        assertEquals(once, twice)
    }

    @Test
    fun downgradeKeepsTextButDropsInlineStyles() {
        val doc = RichDoc(
            blocks = listOf(
                RichBlock(
                    id = "b0",
                    type = BlockType.TEXT,
                    fragments = listOf(Fragment("加粗", bold = true), Fragment("普通")),
                ),
            ),
        )
        assertEquals("加粗普通", LegacyBody.toText(doc))
    }
}
