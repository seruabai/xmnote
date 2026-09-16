package com.purenote.local.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插图/录音的块级插入：位置、光标落点、以及「文末必须有可输入处」这条硬约束 */
class BlockEditTest {

    private fun doc(vararg texts: String) = RichDoc(
        blocks = texts.mapIndexed { i, t -> RichBlock.text("b" + i, t) },
    )

    @Test
    fun lineStartsAndIndexMapping() {
        val d = doc("aa", "# bb", "")
        val starts = d.lineStarts()
        assertEquals(0, starts[0])
        assertEquals(3, starts[1])
        assertEquals(8, starts[2])   // 0 + "aa"(2) + 换行 + "# bb"(4) + 换行

        assertEquals(0, d.blockIndexAtOffset(0))
        assertEquals(0, d.blockIndexAtOffset(2))
        assertEquals(1, d.blockIndexAtOffset(3))
        assertEquals(1, d.blockIndexAtOffset(7))
        assertEquals(2, d.blockIndexAtOffset(9))
        assertEquals(2, d.blockIndexAtOffset(999))
    }

    @Test
    fun offsetInMapsBackToLineStarts() {
        val d = doc("aa", "# bb")
        assertEquals(0, d.offsetIn("b0", 0))
        assertEquals(2, d.offsetIn("b0", 2))
        assertEquals(3, d.offsetIn("b1", 0))
        assertEquals(7, d.offsetIn("b1", 5))    // "# bb" 只有 4 个字符，5 会被夹到块尾
        assertEquals(7, d.offsetIn("b1", 99))   // 越界夹取
        assertEquals(0, d.offsetIn("不存在", 3))
    }

    @Test
    fun insertImageAfterTheBlockContainingTheCursor() {
        // 光标偏移 4 = 第二行行首 → 图片插在"第二行"这一块之后
        val (newMarkup, _) = insertEmbedMarkup("第一行\n第二行\n第三行", cursor = 4, fileName = "img_a.jpg")
        assertEquals("第一行\n第二行\n![](img_a.jpg)\n第三行", newMarkup)
    }

    @Test
    fun imageAtDocumentEndGetsAnEmptyTextBlockAndCaretMovesThere() {
        val (newMarkup, caret) = insertEmbedMarkup("只有一行", cursor = 2, fileName = "img_b.jpg")
        assertEquals("只有一行\n![](img_b.jpg)\n", newMarkup)
        assertEquals("光标必须落在文末空文本块里才能接着输入", newMarkup.length, caret)
    }

    @Test
    fun blankLineBecomesTheImageLineInPlace() {
        // 沿用既有行为：光标在空行上时，空行就地变成图片行，图片上方不会多出一条空段
        val (newMarkup, _) = insertEmbedMarkup("第一行\n\n第三行", cursor = 4, fileName = "img_x.jpg")
        assertEquals("第一行\n![](img_x.jpg)\n第三行", newMarkup)
    }

    @Test
    fun audioFileNameBecomesSoundBlock() {
        val (newMarkup, _) = insertEmbedMarkup("正文", cursor = 0, fileName = "aud_20260916.m4a")
        val d = LegacyBody.fromText(newMarkup)
        assertEquals(BlockType.SOUND, d.blocks[1].type)
        assertEquals("aud_20260916.m4a", d.blocks[1].fileId)
    }

    @Test
    fun consecutiveImagesStackButAlwaysLeaveATypingSpot() {
        val first = insertEmbedMarkup("正文", cursor = 1, fileName = "img_1.jpg").first
        // 第二张的光标停在第一张后面那个空文本块上 → 该空行被就地占位，两张图相邻
        val second = insertEmbedMarkup(first, cursor = first.length, fileName = "img_2.jpg").first
        assertEquals(
            listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.IMAGE, BlockType.TEXT),
            LegacyBody.fromText(second).blocks.map { it.type },
        )
        // 末尾永远留着可输入的文本块，否则用户没有落笔处
        assertEquals(BlockType.TEXT, LegacyBody.fromText(second).blocks.last().type)
    }

    @Test
    fun caretAlwaysLandsInATextBlockRightAfterTheInsertedImage() {
        val first = insertEmbedMarkup("正文", cursor = 1, fileName = "img_1.jpg").first
        // 光标在正文块内 → 新图插在正文之后；其后紧跟的是上一张图（嵌入块），
        // 于是按规则补一个空文本块，保证"插完图立刻能打字"
        val second = insertEmbedMarkup(first, cursor = 0, fileName = "img_2.jpg").first
        assertEquals(
            listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT),
            LegacyBody.fromText(second).blocks.map { it.type },
        )
    }

    @Test
    fun insertIntoEmptyDocument() {
        // 空文档里插图不应先留一个空行
        val (newMarkup, caret) = insertEmbedMarkup("", cursor = 0, fileName = "img_c.jpg")
        assertEquals("![](img_c.jpg)\n", newMarkup)
        assertEquals(newMarkup.length, caret)
    }

    @Test
    fun ensureTrailingTextBlockIsNoOpWhenLastBlockIsText() {
        val d = doc("a", "b")
        assertEquals(d, d.ensureTrailingTextBlock("tail"))
    }

    @Test
    fun ensureTrailingTextBlockAppendsAfterEmbed() {
        val d = RichDoc(
            blocks = listOf(
                RichBlock.text("b0", "正文"),
                RichBlock(id = "b1", type = BlockType.IMAGE, fileId = "x.jpg"),
            ),
        )
        val fixed = d.ensureTrailingTextBlock("tail")
        assertEquals(3, fixed.blocks.size)
        assertEquals("tail", fixed.blocks.last().id)
        assertTrue(fixed.blocks.last().type == BlockType.TEXT)
    }

    @Test
    fun insertedEmbedsSurviveMarkupRoundTrip() {
        val once = insertEmbedMarkup("# 标题\n- [ ] 待办", cursor = 1, fileName = "img_x.jpg").first
        assertEquals(once, LegacyBody.toText(LegacyBody.fromText(once)))
    }
}
