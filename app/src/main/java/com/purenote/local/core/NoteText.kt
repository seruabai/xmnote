package com.purenote.local.core

import com.purenote.local.data.ChecklistItem

/**
 * 清单条目的紧凑序列化。
 * 每行格式：标志(0/1) + 分隔符 + 文本；文本中的换行与分隔符会被替换，保证可逆解析。
 */
object ChecklistCodec {

    const val SEP = ''

    fun encode(items: List<ChecklistItem>): String =
        items.joinToString("\n") { item ->
            val flag = if (item.done) "1" else "0"
            flag + SEP + sanitize(item.text)
        }

    fun decode(raw: String): List<ChecklistItem> {
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val done = line.firstOrNull() == '1'
            val text = if (line.length >= 2 && line[1] == SEP) line.substring(2) else
                line.dropWhile { it == '0' || it == '1' }
            ChecklistItem(text = text.trim(), done = done)
        }.filter { it.text.isNotEmpty() }
    }

    private fun sanitize(text: String): String =
        text.replace("\n", " ").replace(SEP, ' ').trim()

    /** 清单进度摘要，如 3/5 */
    fun progress(items: List<ChecklistItem>): Pair<Int, Int> =
        items.count { it.done } to items.size
}

/** 列表卡片预览文本的构建规则（纯函数便于测试） */
object PreviewBuilder {

    /**
     * 小米笔记式流式正文：首行即标题。
     * 返回 (首行标题, 其余正文)。
     */
    fun splitTitle(body: String): Pair<String, String> {
        val normalized = body.replace("\r\n", "\n").trimStart('\n')
        if (normalized.isBlank()) return "" to ""
        val idx = normalized.indexOf('\n')
        return if (idx == -1) {
            normalized.trim() to ""
        } else {
            normalized.substring(0, idx).trim() to normalized.substring(idx + 1).trimStart()
        }
    }

    /** 把标题与正文合并回流式正文；若正文已含首行则不再重复拼接 */
    fun joinTitleBody(title: String, body: String): String = when {
        title.isBlank() -> body
        body.isBlank() -> title
        body.startsWith("$title\n") || body == title -> body
        else -> "$title\n$body"
    }

    fun textPreview(body: String, maxChars: Int = 120): String {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= maxChars) flat else flat.take(maxChars) + "…"
    }

    fun checklistPreview(items: List<ChecklistItem>, maxLines: Int = 4): String =
        items.asSequence()
            .filter { !it.done }
            .take(maxLines)
            .map { "• ${it.text}" }
            .toList()
            .joinToString("\n")
}
