package com.purenote.local.core

import com.purenote.local.data.ChecklistItem

/**
 * 旧正文格式（body_format_version 1/2）到块文档（v3）的升级与回退。
 *
 * 解析**直接复用 NoteMarkup.tagInfo / isImageLine 等既有函数**，不另写一套正则：
 * 迁移语义必须与线上渲染逐字一致，否则会出现"升级后笔记看起来变了"。
 *
 * 升级是确定性、幂等的：同一段正文永远得到同一份文档（块 id 按行号生成），
 * 便于比对、便于回滚后再升级。
 */
object LegacyBody {

    /** 录音文件名前缀，与 NoteMarkup.audioNames 的判断保持一致 */
    private const val AUDIO_PREFIX = "aud_"

    /** 图片/录音文件的文件名是正文里唯一的附件标识 */
    private const val ID_PREFIX = "b"

    // ---------------------------------------------------------------- 升级

    /** Markdown 标记文本 → 块文档 */
    fun fromText(body: String): RichDoc {
        val lines = body.split('\n')
        return RichDoc(blocks = lines.mapIndexed { i, line -> parseLine(line, ID_PREFIX + i) })
    }

    /** 清单笔记的条目 → 块文档（清单项本身就是 TODO 块） */
    fun fromChecklist(items: List<ChecklistItem>): RichDoc {
        if (items.isEmpty()) return RichDoc()
        return RichDoc(
            blocks = items.mapIndexed { i, item ->
                RichBlock(
                    id = ID_PREFIX + i,
                    type = BlockType.TODO,
                    fragments = listOf(Fragment(item.text)),
                    checked = item.done,
                )
            },
        )
    }

    /** 按笔记类型分派，供数据库迁移与备份导入调用 */
    fun upgrade(isChecklist: Boolean, rawBody: String): RichDoc =
        if (isChecklist) fromChecklist(ChecklistCodec.decode(rawBody)) else fromText(rawBody)

    private fun parseLine(line: String, id: String): RichBlock {
        val info = NoteMarkup.tagInfo(line)
        val afterHead = line.substring(info.headLen)

        // 图片/录音行整行匹配；同时保留标题级别，使回退能还原 "## ![](x)" 这类组合
        if (NoteMarkup.isImageLine(afterHead)) {
            val name = NoteMarkup.imageNameOf(afterHead).orEmpty()
            return RichBlock(
                id = id,
                type = if (name.startsWith(AUDIO_PREFIX)) BlockType.SOUND else BlockType.IMAGE,
                headingLevel = info.level,
                fileId = name,
            )
        }

        var content = afterHead.substring(info.tagLen)
        var indentTail = false
        if (content.endsWith(NoteMarkup.INDENT_SUFFIX)) {
            indentTail = true
            content = content.dropLast(NoteMarkup.INDENT_SUFFIX.length)
        }

        val type = when (info.tag) {
            NoteMarkup.HeadTag.CHECKBOX -> BlockType.TODO
            NoteMarkup.HeadTag.BULLET, NoteMarkup.HeadTag.NUMBER -> BlockType.ITEM
            NoteMarkup.HeadTag.QUOTE -> BlockType.QUOTE
            else -> BlockType.TEXT
        }
        return RichBlock(
            id = id,
            type = type,
            fragments = listOf(Fragment(content)),
            headingLevel = info.level,
            checked = info.tag == NoteMarkup.HeadTag.CHECKBOX && afterHead.startsWith(NoteMarkup.TASK_DONE),
            number = if (info.tag == NoteMarkup.HeadTag.NUMBER) info.number else 0,
            indentHead = info.tag == NoteMarkup.HeadTag.INDENT,
            indentTail = indentTail,
        )
    }

    // ---------------------------------------------------------------- 回退

    /**
     * 块文档 → Markdown 标记文本。
     *
     * 用于导出给旧版本、以及升级出问题时的回退手段。注意**行内样式（加粗等）在旧格式里
     * 无法表达，回退时会丢失样式、只保留文字**；LINK 块同理降级为纯文本。
     */
    fun toText(doc: RichDoc): String = doc.blocks.joinToString("\n") { encodeBlock(it) }

    /** 块文档 → 清单条目（只取 TODO 块，其余块类型旧格式存不下） */
    fun toChecklist(doc: RichDoc): List<ChecklistItem> =
        doc.blocks.filter { it.type == BlockType.TODO }.map { ChecklistItem(it.text, it.checked) }

    private fun encodeBlock(block: RichBlock): String {
        val head = if (block.headingLevel in 1..3) "#".repeat(block.headingLevel) + " " else ""
        val body = when (block.type) {
            BlockType.IMAGE, BlockType.SOUND ->
                NoteMarkup.IMG_PREFIX + (block.fileId ?: "") + NoteMarkup.IMG_SUFFIX
            BlockType.TODO ->
                (if (block.checked) NoteMarkup.TASK_DONE else NoteMarkup.TASK_TODO) + block.text
            BlockType.ITEM ->
                (if (block.number > 0) block.number.toString() + ". " else NoteMarkup.BULLET_PREFIX) + block.text
            BlockType.QUOTE -> NoteMarkup.QUOTE_PREFIX + block.text
            BlockType.TEXT -> (if (block.indentHead) NoteMarkup.INDENT_PREFIX else "") + block.text
            // 旧格式没有链接块，降级为纯文本
            BlockType.LINK -> block.text
        }
        val tail = if (block.indentTail) NoteMarkup.INDENT_SUFFIX else ""
        return head + body + tail
    }
}
