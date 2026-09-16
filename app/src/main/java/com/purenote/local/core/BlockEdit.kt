package com.purenote.local.core

/** 录音文件名前缀，与 NoteMarkup.audioNames / LegacyBody 保持一致 */
private const val AUDIO_PREFIX = "aud_"

/**
 * 块级编辑运算（纯函数）。
 *
 * 图片/录音在正文里的**唯一表示就是一个块**：插入、定位、保证后面还有可输入的光标位，
 * 全部在这里完成。编辑器只负责把结果写回文档与光标，不自己拼标记文本。
 */

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
 * 在 [index] 指向的位置插入嵌入块，保证其后有可输入的空文本块；返回新文档与光标落脚块 id。
 *
 * 位置语义沿用既有行为：**光标所在块是空行时就地占位**（空行变成图片行），
 * 否则插在该块之后。这样既不会在图片上方多留一条空段，也符合用户"在空行处插图"的直觉。
 */
fun RichDoc.insertEmbedAt(index: Int, embed: RichBlock, tailId: String): Pair<RichDoc, String> {
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
        inserted.insert(RichBlock.text(tailId), insertAt + 1) to tailId
    } else {
        inserted to next.id
    }
}

/**
 * 在 [blockId] 这一块处插入图片/录音，返回 (新文档, 光标落点块 id)。
 *
 * 编辑器三处插入入口（拍照 / 相册 / 录音 / 手写）统一走这里：位置按**块**计算，
 * 并在必要时补出可输入的落点。[blockId] 不存在时退化成插在文首，不抛异常。
 */
fun RichDoc.insertEmbedAtBlock(blockId: String, embed: RichBlock, tailId: String): Pair<RichDoc, String> {
    val index = indexOf(blockId).takeIf { it >= 0 } ?: 0
    return insertEmbedAt(index, embed, tailId)
}

/** 按文件名造一个嵌入块：aud_ 前缀是录音，其余是图片 */
fun embedBlockFor(fileName: String, id: String = BlockIds.newBlockId()): RichBlock =
    RichBlock(
        id = id,
        type = if (fileName.startsWith(AUDIO_PREFIX)) BlockType.SOUND else BlockType.IMAGE,
        fileId = fileName,
    )
