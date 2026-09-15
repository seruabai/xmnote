package com.purenote.local.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichDocTest {

    private fun doc(vararg texts: String) = RichDoc(
        blocks = texts.mapIndexed { i, t -> RichBlock.text("b" + i, t) },
    )

    @Test
    fun insertAtEndAndAtExplicitIndex() {
        val d = doc("a", "b")
        assertEquals(listOf("a", "b", "c"), d.insert(RichBlock.text("x", "c")).blocks.map { it.text })
        assertEquals(listOf("c", "a", "b"), d.insert(RichBlock.text("x", "c"), index = 0).blocks.map { it.text })
        // 越界下标夹取到末尾而不是抛异常
        assertEquals(listOf("a", "b", "c"), d.insert(RichBlock.text("x", "c"), index = 99).blocks.map { it.text })
    }

    @Test
    fun removeNeverLeavesDocumentEmpty() {
        val d = doc("唯一")
        val after = d.remove("b0")
        assertEquals(1, after.blocks.size)
        assertEquals("", after.blocks[0].text)
        assertTrue(d.remove("不存在") == d)
    }

    @Test
    fun moveUsesIndexAfterRemoval() {
        val d = doc("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), d.move("b0", 2).blocks.map { it.text })
        assertEquals(listOf("c", "a", "b"), d.move("b2", 0).blocks.map { it.text })
        assertTrue(d.move("缺失", 0) == d)
    }

    @Test
    fun updateTextKeepsFirstFragmentStyle() {
        val block = RichBlock(
            id = "b0",
            fragments = listOf(Fragment("旧", bold = true)),
        )
        val updated = RichDoc(blocks = listOf(block)).updateText("b0", "新文字")
        assertEquals("新文字", updated.blocks[0].text)
        assertTrue("整块替换文字应保留原样式，否则用户打一半样式会被重置", updated.blocks[0].fragments[0].bold)
    }

    @Test
    fun updateTextIgnoresEmbedBlocks() {
        val img = RichBlock(id = "b0", type = BlockType.IMAGE, fileId = "a.png")
        val d = RichDoc(blocks = listOf(img))
        assertEquals(d, d.updateText("b0", "乱改"))
    }

    @Test
    fun setTypeResetsDependentFields() {
        val todo = RichBlock(id = "b0", type = BlockType.TODO, checked = true, number = 5, fragments = listOf(Fragment("甲")))
        val asText = RichDoc(blocks = listOf(todo)).setType("b0", BlockType.TEXT)
        assertFalse(asText.blocks[0].checked)
        assertEquals(0, asText.blocks[0].number)
        // 嵌入块不允许改类型
        val img = RichDoc(blocks = listOf(RichBlock(id = "b0", type = BlockType.IMAGE, fileId = "a.png")))
        assertEquals(img, img.setType("b0", BlockType.TEXT))
    }

    @Test
    fun toggleCheckedOnlyAppliesToTodo() {
        val todo = RichDoc(blocks = listOf(RichBlock(id = "b0", type = BlockType.TODO, fragments = listOf(Fragment("甲")))))
        assertTrue(todo.toggleChecked("b0").blocks[0].checked)
        assertFalse(todo.toggleChecked("b0").toggleChecked("b0").blocks[0].checked)
        val text = doc("普通")
        assertEquals(text, text.toggleChecked("b0"))
    }

    @Test
    fun splitAtMiddleStartAndEnd() {
        val d = doc("abcdef")
        val mid = d.splitAt("b0", 3, "new")
        assertEquals(listOf("abc", "def"), mid.blocks.map { it.text })
        assertEquals("new", mid.blocks[1].id)

        assertEquals(listOf("", "abcdef"), d.splitAt("b0", 0, "new").blocks.map { it.text })
        assertEquals(listOf("abcdef", ""), d.splitAt("b0", 999, "new").blocks.map { it.text })
        assertEquals(2, d.splitAt("b0", 3, "new").blocks.size)
    }

    @Test
    fun splitAtKeepsInlineMarksOnBothSides() {
        val block = RichBlock(
            id = "b0",
            fragments = listOf(Fragment("a"), Fragment("bc", bold = true), Fragment("d")),
        )
        val split = RichDoc(blocks = listOf(block)).splitAt("b0", 2, "new")
        assertEquals(listOf("a", "b"), split.blocks[0].fragments.map { it.text })
        assertTrue(split.blocks[0].fragments[1].bold)
        assertEquals(listOf("c", "d"), split.blocks[1].fragments.map { it.text })
        assertTrue(split.blocks[1].fragments[0].bold)
    }

    @Test
    fun splitInheritsBlockAttributesToTheNewBlock() {
        val todo = RichBlock(id = "b0", type = BlockType.TODO, headingLevel = 2, fragments = listOf(Fragment("甲甲")))
        val split = RichDoc(blocks = listOf(todo)).splitAt("b0", 1, "new")
        assertEquals(BlockType.TODO, split.blocks[1].type)
        assertEquals(2, split.blocks[1].headingLevel)
        // 勾选状态与序号不入新块，由调用方按"回车续接"决定
        assertFalse(split.blocks[1].checked)

        val ordered = RichBlock(
            id = "b0",
            type = BlockType.ITEM,
            number = 3,
            fragments = listOf(Fragment("第三项")),
        )
        val orderedSplit = RichDoc(blocks = listOf(ordered)).splitAt("b0", 1, "new")
        assertEquals(0, orderedSplit.blocks[1].number)
    }

    @Test
    fun mergeWithPreviousReturnsSeamOffset() {
        val d = doc("ab", "cd")
        val (merged, seam) = d.mergeWithPrevious("b1")
        assertEquals(2, seam)
        assertEquals(listOf("abcd"), merged.blocks.map { it.text })
        assertEquals(1, merged.blocks.size)
    }

    @Test
    fun mergeWithPreviousAtTopIsNoOp() {
        val d = doc("ab", "cd")
        val (same, seam) = d.mergeWithPrevious("b0")
        assertEquals(d, same)
        assertEquals(0, seam)
    }

    @Test
    fun toggleMarkOnThenOffRestoresOriginalFragments() {
        val d = RichDoc(blocks = listOf(RichBlock.text("b0", "abcdef")))
        val marked = d.toggleMark("b0", 1, 4, InlineMark.BOLD)
        assertEquals(
            listOf(Fragment("a"), Fragment("bcd", bold = true), Fragment("ef")),
            marked.blocks[0].fragments,
        )
        assertEquals(d, marked.toggleMark("b0", 1, 4, InlineMark.BOLD))
    }

    @Test
    fun toggleMarkAcrossExistingFragmentsCoversWholeRange() {
        val block = RichBlock(
            id = "b0",
            fragments = listOf(Fragment("ab", bold = true), Fragment("cd", italic = true)),
        )
        val marked = RichDoc(blocks = listOf(block)).toggleMark("b0", 1, 3, InlineMark.BOLD)
        // 区间 [1,3) 覆盖字符 b 与 c：a、b 合并且带粗体，c 同时带粗体与原有斜体，d 只保留斜体
        val fragments = marked.blocks[0].fragments
        assertEquals(listOf("ab", "c", "d"), fragments.map { it.text })
        assertTrue(fragments[0].bold)
        assertTrue(fragments[1].bold && fragments[1].italic)
        assertFalse("区间外字符不应被样式影响", fragments[2].bold)
        assertTrue(fragments[2].italic)
        assertEquals("abcd", marked.blocks[0].text)
    }

    @Test
    fun markCoverageDetectsPartialSelection() {
        val block = RichBlock(
            id = "b0",
            fragments = listOf(Fragment("abcdef", bold = true)),
        )
        assertTrue(isMarkCovered(block, 1, 3, InlineMark.BOLD))
        assertFalse(isMarkCovered(block, 1, 7, InlineMark.BOLD))
        assertFalse(isMarkCovered(block, 1, 3, InlineMark.ITALIC))
    }

    @Test
    fun derivedTitleSkipsEmptyAndEmbedBlocks() {
        val d = RichDoc(
            blocks = listOf(
                RichBlock.text("b0", ""),
                RichBlock(id = "b1", type = BlockType.IMAGE, fileId = "a.png"),
                RichBlock.text("b2", "  真正的标题  "),
            ),
        )
        assertEquals("真正的标题", d.derivedTitle())
        assertEquals("", RichDoc(blocks = listOf(RichBlock.text("b0", ""))).derivedTitle())
    }

    @Test
    fun plainTextAndAttachmentNamesFollowDocumentOrder() {
        val d = RichDoc(
            blocks = listOf(
                RichBlock.text("b0", "第一行"),
                RichBlock(id = "b1", type = BlockType.IMAGE, fileId = "a.png"),
                RichBlock(id = "b2", type = BlockType.SOUND, fileId = "aud_x.mp3"),
                RichBlock.text("b3", "第二行"),
            ),
        )
        assertEquals("第一行\n\n\n第二行", d.plainText())
        assertEquals(listOf("a.png", "aud_x.mp3"), d.attachmentNames())
    }

    @Test
    fun checklistProgressCountsOnlyTodoBlocks() {
        val d = RichDoc(
            blocks = listOf(
                RichBlock(id = "b0", type = BlockType.TODO, checked = true, fragments = listOf(Fragment("甲"))),
                RichBlock(id = "b1", type = BlockType.TODO, fragments = listOf(Fragment("乙"))),
                RichBlock.text("b2", "普通段落"),
            ),
        )
        assertEquals(1 to 2, d.checklistProgress())
        assertEquals(0 to 0, doc("无待办").checklistProgress())
    }

    @Test
    fun adjacentFragmentsWithSameStyleAreMerged() {
        val block = RichBlock(
            id = "b0",
            fragments = listOf(Fragment("a", bold = true), Fragment("b", bold = true)),
        )
        val marked = RichDoc(blocks = listOf(block)).toggleMark("b0", 0, 1, InlineMark.ITALIC)
        assertEquals(2, marked.blocks[0].fragments.size)
        assertEquals("a", marked.blocks[0].fragments[0].text)
    }
}
