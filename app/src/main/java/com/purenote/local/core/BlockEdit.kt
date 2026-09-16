package com.purenote.local.core

/** 录音文件名前缀，与 NoteMarkup.audioNames / LegacyBody 保持一致 */
private const val AUDIO_PREFIX = "aud_"

/**
 * 块级编辑运算（纯函数）。
 *
 * 图片/录音在正文里的**唯一表示就是一个块**：插入、定位、保证后面还有可输入的光标位，
 * 全部在这里完成。编辑器只负责把结果写回正文与光标，不自己拼标记文本。
 */

/** 每个块在整段正文中的起始偏移 */
fun RichDoc.lineStarts(): IntArray {
    val starts = IntArray(blocks.size)
    var acc = 0
    blocks.forEachIndexed { i, b ->
        starts[i] = acc
        acc += LegacyBody.encodeLine(b).length + 1
    }
    return starts
}

/** 全局正文偏移 → 块下标（越界夹取；偏移落在换行上时归前一块） */
fun RichDoc.blockIndexAtOffset(offset: Int): Int {
    if (blocks.isEmpty()) return 0
    val starts = lineStarts()
    for (i in blocks.indices.reversed()) {
        if (offset >= starts[i]) return i
    }
    return 0
}

/** 块内光标 → 全局正文偏移 */
fun RichDoc.offsetIn(blockId: String, caret: Int): Int {
    val idx = indexOf(blockId)
    if (idx < 0) return 0
    val start = lineStarts()[idx]
    val len = LegacyBody.encodeLine(blocks[idx]).length
    return start + caret.coerceIn(0, len)
}

/**
 * 末尾是嵌入块时补一个空文本块。
 *
 * 没有这一步，图片插在文末之后用户就没有可落笔的地方——块编辑器里嵌入块没有输入框，
 * 光标无处可去。这是块编辑器与单文本框最不一样的地方。
 */
fun RichDoc.ensureTrailingTextBlock(newId: String): RichDoc =
    if (blocks.isNotEmpty() && blocks.last().isEmbed) {
        insert(RichBlock.text(newId), blocks.size)
    } else {
        this
    }

/**
 * 在 [index] 指向的位置插入嵌入块，保证其后有可输入的空文本块；返回新文档与光标落点。
 *
 * 位置语义沿用既有行为：**光标所在块是空行时就地占位**（空行变成图片行），
 * 否则插在该块之后。这样既不会在图片上方多留一条空段，也符合用户"在空行处插图"的直觉。
 */
fun RichDoc.insertEmbedAt(
    index: Int,
    embed: RichBlock,
    tailId: String,
): Pair<RichDoc, Pair<String, Int>> {
    val at = index.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
    val target = blocks.getOrNull(at)
    val inPlace = target != null &&
        target.type == BlockType.TEXT &&
        target.text.isEmpty() &&
        target.headingLevel == 0 &&
        !target.indentHead &&
        !target.indentTail

    val base = if (inPlace) remove(target!!.id) else this
    val insertAt = (if (inPlace) at else at + 1).coerceIn(0, base.blocks.size)
    val inserted = base.insert(embed, insertAt)
    val next = inserted.blocks.getOrNull(insertAt + 1)
    return if (next == null || next.isEmbed) {
        // 插入点后面没有块、或后面还是嵌入块（连续插图）→ 补一个空文本块给光标落脚
        val withTail = inserted.insert(RichBlock.text(tailId), insertAt + 1)
        withTail to (tailId to 0)
    } else {
        inserted to (next.id to 0)
    }
}

/**
 * 在光标处插入图片/录音块，返回 (新的 Markdown 正文, 建议的光标偏移)。
 *
 * 编辑器三处插入入口（拍照 / 相册 / 录音）统一走这里，替代原来直接拼 "![](file)" 文本行的做法：
 * 插入位置按**块**计算，并在必要时补出可输入的落点。
 */
fun insertEmbedMarkup(markup: String, cursor: Int, fileName: String): Pair<String, Int> {
    val doc = LegacyBody.fromText(markup)
    val index = doc.blockIndexAtOffset(cursor)
    val embed = RichBlock(
        id = BlockIds.newBlockId(),
        type = if (fileName.startsWith(AUDIO_PREFIX)) BlockType.SOUND else BlockType.IMAGE,
        fileId = fileName,
    )
    val (updated, target) = doc.insertEmbedAt(index, embed, BlockIds.newBlockId())
    return LegacyBody.toText(updated) to updated.offsetIn(target.first, target.second)
}
