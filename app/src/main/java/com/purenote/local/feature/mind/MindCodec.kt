package com.purenote.local.feature.mind

import kotlinx.serialization.json.Json

/**
 * 脑图文档与 notes.body 之间的编解码。
 *
 * 与备份包同一套路数：宁可解不出来（返回 null，由调用方决定是否重建），
 * 也不要抛异常把编辑流程打断。字段全部带默认值 + ignoreUnknownKeys，
 * 这样旧版本读到新版本写入的内容不会崩。
 */
object MindCodec {
    const val CURRENT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(doc: MindDoc): String = json.encodeToString(MindDoc.serializer(), doc)

    /** 解析失败返回 null（空白、截断、非本格式都算失败） */
    fun decode(raw: String?): MindDoc? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("{")) return null
        return runCatching { json.decodeFromString(MindDoc.serializer(), text) }.getOrNull()
    }

    /** 给编辑器的兜底：解不出来就按标题建一棵只有根节点的树 */
    fun decodeOrNew(raw: String?, title: String = ""): MindDoc =
        decode(raw) ?: MindDoc(root = MindNode(id = MindDoc.ROOT_ID, label = title))

    /** 判断 body 是否已经是脑图格式（用于从文本笔记升级/导入时判断） */
    fun looksLikeMindDoc(raw: String?): Boolean = decode(raw) != null
}
