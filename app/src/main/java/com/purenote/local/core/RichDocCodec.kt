package com.purenote.local.core

import kotlinx.serialization.json.Json

/**
 * 正文块文档的编解码。
 *
 * 与 MindCodec / BackupCodec 同一套路数：解不出来返回 null 交给调用方决定是否重建，
 * 绝不抛异常打断编辑；字段全带默认值 + ignoreUnknownKeys，旧版本读到新内容不崩。
 */
object RichDocCodec {
    /** 对应 NotesDb.BODY_FORMAT_*：1=旧纯文本，2=Markdown 标记，3=块文档 */
    const val CURRENT_VERSION = 3

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(doc: RichDoc): String = json.encodeToString(RichDoc.serializer(), doc)

    fun decode(raw: String?): RichDoc? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("{")) return null
        return runCatching { json.decodeFromString(RichDoc.serializer(), text) }.getOrNull()
    }

    /** 兜底：解不出来就按给定标题建一个单块文档 */
    fun decodeOrNew(raw: String?, title: String = ""): RichDoc =
        decode(raw) ?: RichDoc(blocks = listOf(RichBlock.text(RichDoc.FIRST_BLOCK_ID, title)))

    fun looksLikeBlockDoc(raw: String?): Boolean = decode(raw) != null
}
