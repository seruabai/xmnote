package com.purenote.local.core

import com.purenote.local.data.ChecklistItem

/**
 * 正文存储格式的**唯一闸口**：所有读写 body 的地方都从这里走。
 *
 * 为什么要有这层：body 列同时承载两种笔记类型、三种格式版本
 * （1 私有标记 / 2 Markdown / 3 块文档），如果各处自己判断版本号，
 * 迟早出现"某条路径按 Markdown 解析 v3 JSON"的静默损坏。
 *
 * 解码策略是**保全型**的：
 * - v3 解析失败时回退按 Markdown 解析，而不是让笔记变成空白（宁可显示成纯文本）；
 * - v1/v2 一律按旧格式解析，不假设库已经迁移完（迁移失败的行会保持旧版本号）。
 */
object NoteBody {

    /** 与 NotesDb.BODY_FORMAT_BLOCKS 同义，放这里避免 core 反向依赖 data 层常量 */
    const val FORMAT_VERSION = 3

    /** 与 NotesDb.BODY_FORMAT_LEGACY 同义：v1 正文是私有标记（PUA）而非 Markdown */
    const val FORMAT_VERSION_LEGACY = 1

    // ---------------------------------------------------------------- 解码

    fun decodeText(raw: String?, formatVersion: Int): RichDoc {
        if (formatVersion >= FORMAT_VERSION) {
            // 版本号说它是 v3 但解析不出来：按旧格式兜底，避免整条笔记显示为空
            RichDocCodec.decode(raw)?.let { return it }
        } else if (RichDocCodec.looksLikeBlockDoc(raw)) {
            // 版本号 < 3 而内容其实是块文档：历史写入路径漏写版本号的情形。
            // 按内容识别，比照版本号硬当 Markdown 解析安全得多
            RichDocCodec.decode(raw)?.let { return it }
        }
        val text = raw.orEmpty()
        // v1 是私有标记（PUA 字符）而非 Markdown：必须先过 v8 那次用的同一个纯函数，
        // 否则旧包导入会把 PUA 标记原样当成正文显示
        val markup = if (formatVersion <= FORMAT_VERSION_LEGACY) {
            NoteMarkup.migrateBodyV1toV2(text)
        } else {
            text
        }
        return LegacyBody.fromText(markup)
    }

    fun decodeChecklist(raw: String?, formatVersion: Int): RichDoc {
        if (formatVersion >= FORMAT_VERSION || RichDocCodec.looksLikeBlockDoc(raw)) {
            RichDocCodec.decode(raw)?.let { return it }
        }
        return LegacyBody.fromChecklist(ChecklistCodec.decode(raw.orEmpty()))
    }

    /** 按笔记类型分派解码 */
    fun decode(isChecklist: Boolean, raw: String?, formatVersion: Int): RichDoc =
        if (isChecklist) decodeChecklist(raw, formatVersion) else decodeText(raw, formatVersion)

    // ---------------------------------------------------------------- 编码

    /** 块文档 → 落库字符串（恒为 v3） */
    fun encode(doc: RichDoc): String = RichDocCodec.encode(doc)

    /** 从旧格式的编辑产物（Markdown 正文 / 清单条目）构造块文档并落库 */
    fun encodeFromLegacy(isChecklist: Boolean, body: String, items: List<ChecklistItem>): String =
        encode(
            if (isChecklist) LegacyBody.fromChecklist(items) else LegacyBody.fromText(body),
        )

    // ---------------------------------------------------------------- 供旧界面读取

    /**
     * 块文档 → 旧编辑器能编辑的 Markdown 正文。
     *
     * **临时桥**：现有编辑器仍是"纯文本 + 标记"模型，所以读出来要转回标记文本。
     * 注意旧格式表达不了行内样式与 LINK 块，[hasMarkupInexpressibleContent] 为 true 的
     * 文档经此转换会丢样式（文字不丢）。在编辑器切到块模型之前，不要让界面产生这类内容。
     */
    fun toMarkup(doc: RichDoc): String = LegacyBody.toText(doc)

    /** 块文档 → 清单条目（清单笔记的旧界面模型） */
    fun toChecklistItems(doc: RichDoc): List<ChecklistItem> = LegacyBody.toChecklist(doc)

    /**
     * 文档里是否有旧标记格式表达不了的内容（行内样式、链接块）。
     * 一旦为 true，就不能再用 [toMarkup] 的结果回写，否则会静默丢样式。
     */
    fun hasMarkupInexpressibleContent(doc: RichDoc): Boolean =
        doc.blocks.any { block ->
            block.type == BlockType.LINK || block.fragments.any { !it.isPlain }
        }

    // ---------------------------------------------------------------- 迁移

    /**
     * 迁移专用：把一行存量正文升级成 v3，返回 (新正文, 新版本号)。
     *
     * 返回 null 表示**无需改动**（已经是 v3）。真正的转换是纯函数，所以迁移分支
     * 只负责搬运，转换规则可以被单测覆盖——与 v8 那次 PUA→Markdown 同一做法。
     */
    fun upgradeStoredBody(
        isChecklist: Boolean,
        rawBody: String,
        formatVersion: Int,
    ): Pair<String, Int>? {
        if (formatVersion >= FORMAT_VERSION) return null
        return encode(decode(isChecklist, rawBody, formatVersion)) to FORMAT_VERSION
    }
}
