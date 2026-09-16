package com.purenote.local.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插图/录音的块级插入：位置、光标落点、以及「文末必须有可输入处」这条硬约束 */
class BlockEditTest {

    private fun doc(vararg texts: String) = RichDoc(
        blocks = texts.mapIndexed { i, t -> RichBlock.text("b" + i, t) },
    )

    private fun image(id: String = "img") = RichBlock(id = id, type = BlockType.IMAGE, fileId = "img.jpg")

    @Test
    fun embedInsertsAfterTheGivenBlock() {
        val d = doc("第一行", "第二行", "第三行")
        val (after, target) = d.insertEmbedAtBlock("b1", image(), "tail")
        assertEquals(
            listOf(BlockType.TEXT, BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT),
            after.blocks.map { it.type },
        )
        assertEquals("光标落在第三块", "b2", target)
    }

    @Test
    fun embedAtDocumentEndGetsAnEmptyTextBlockAndCaretMovesThere() {
        val d = doc("只有一行")
        val (after, target) = d.insertEmbedAtBlock("b0", image(), "tail")
        assertEquals(listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT), after.blocks.map { it.type })
        assertEquals("tail", target)
        assertEquals("落点必须是可输入的文本块", BlockType.TEXT, after.blocks.last().type)
        assertTrue("落点块不能是嵌入块", after.find(target)?.isEditable == true)
    }

    @Test
    fun blankBlockBecomesTheImageBlockInPlace() {
        // 沿用既有行为：光标在空行上时，空行就地变成图片行，图片上方不会多出一条空段
        val d = doc("第一行", "", "第三行")
        val (after, _) = d.insertEmbedAtBlock("b1", image(), "tail")
        assertEquals(
            listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT),
            after.blocks.map { it.type },
        )
        assertEquals("第一行", after.blocks[0].text)
        assertEquals("第三行", after.blocks[2].text)
    }

    @Test
    fun audioFileNameBecomesSoundBlock() {
        val d = doc("正文")
        val (after, _) = d.insertEmbedAtBlock("b0", embedBlockFor("aud_20260916.m4a", "snd"), "tail")
        assertEquals(BlockType.SOUND, after.blocks[1].type)
        assertEquals("aud_20260916.m4a", after.blocks[1].fileId)
    }

    @Test
    fun imageFileNameBecomesImageBlock() {
        val block = embedBlockFor("img_a.jpg", "pic")
        assertEquals(BlockType.IMAGE, block.type)
        assertEquals("img_a.jpg", block.fileId)
    }

    @Test
    fun consecutiveImagesStackButAlwaysLeaveATypingSpot() {
        val d = doc("正文")
        val (first, target1) = d.insertEmbedAtBlock("b0", image("i1"), "tail1")
        // 第二张的光标停在第一张后面那个空文本块上 → 该空行被就地占位，两张图相邻
        val (second, _) = first.insertEmbedAtBlock(target1, image("i2"), "tail2")
        assertEquals(
            listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.IMAGE, BlockType.TEXT),
            second.blocks.map { it.type },
        )
        // 末尾永远留着可输入的文本块，否则用户没有落笔处
        assertEquals(BlockType.TEXT, second.blocks.last().type)
    }

    @Test
    fun caretAlwaysLandsInATextBlockRightAfterTheInsertedImage() {
        val d = doc("正文")
        val (first, _) = d.insertEmbedAtBlock("b0", image("i1"), "tail1")
        // 光标回到正文块内 → 新图插在正文之后；其后紧跟的是上一张图（嵌入块），
        // 于是按规则补一个空文本块，保证"插完图立刻能打字"
        val (second, target) = first.insertEmbedAtBlock("b0", image("i2"), "tail2")
        assertEquals(
            listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT),
            second.blocks.map { it.type },
        )
        assertEquals(BlockType.TEXT, second.find(target)?.type)
    }

    @Test
    fun insertIntoEmptyDocumentKeepsNoLeadingBlankBlock() {
        // 空文档里插图不应先留一个空行：空块就地变成图片块，原来的空块挪到后面当落点
        val (after, target) = RichDoc().insertEmbedAtBlock(RichDoc.FIRST_BLOCK_ID, image(), "tail")
        assertEquals(listOf(BlockType.IMAGE, BlockType.TEXT), after.blocks.map { it.type })
        assertEquals("原空块就是落点", RichDoc.FIRST_BLOCK_ID, target)
        assertEquals(BlockType.TEXT, after.find(target)?.type)
    }

    @Test
    fun unknownAnchorFallsBackToTheFirstBlock() {
        // 锚点不存在时按首块处理：插在首块之后（与"插在光标所在块之后"同一语义），不抛异常
        val d = doc("正文")
        val (after, _) = d.insertEmbedAtBlock("不存在", image(), "tail")
        assertEquals(listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT), after.blocks.map { it.type })
        assertEquals("正文", after.blocks[0].text)
    }

    @Test
    fun headingAndTodoBlocksAreNotReplacedInPlace() {
        // 只有"空的无样式文本块"才允许就地占位；标题块即使文字为空也保留
        val d = RichDoc(
            blocks = listOf(
                RichBlock(id = "b0", headingLevel = 1, fragments = listOf(Fragment(""))),
                RichBlock.text("b1", "正文"),
            ),
        )
        val (after, target) = d.insertEmbedAtBlock("b0", image(), "tail")
        // 空标题块不就地占位 → 图片插在它之后，后面已有的正文块就是落点，不必再补
        assertEquals(
            listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT),
            after.blocks.map { it.type },
        )
        assertEquals(1, after.blocks[0].headingLevel)
        assertEquals("b1", target)
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
}
