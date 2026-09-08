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

/**
 * 笔记富文本标记（v1.2.20）：
 * - 标题级别：私有区字符作为行首标记 H1=\uE201 H2=\uE202 H3=\uE203（显示时剥离并放大）
 * - 图片块：整行 "[img:文件名]"（编辑器显示为占位，插入在光标所在行）
 * - 行内勾选：行首可见字符 ☐（未完成）/ ☑（已完成），勾选后文字置灰
 * 标记只存在于正文文本中，预览/卡片/分享时剥离为可读文本。
 */
object NoteMarkup {

    const val IMG_PREFIX = "[img:"
    const val IMG_SUFFIX = "]"

    const val H1 = '\uE201'
    const val H2 = '\uE202'
    const val H3 = '\uE203'
    val HEADING_MARKERS = setOf(H1, H2, H3)

    const val BOX_UNCHECKED = '☐'
    const val BOX_CHECKED = '☑'
    const val BOX_UNCHECKED_PREFIX = "$BOX_UNCHECKED "
    const val BOX_CHECKED_PREFIX = "$BOX_CHECKED "

    fun headingLevel(line: String): Int = when (line.firstOrNull()) {
        H1 -> 1
        H2 -> 2
        H3 -> 3
        else -> 0
    }

    /** 给行设置标题级别；level 0 = 移除标记（正文） */
    fun withHeading(line: String, level: Int): String {
        val bare = line.dropWhile { it in HEADING_MARKERS }
        return when (level) {
            1 -> "$H1$bare"
            2 -> "$H2$bare"
            3 -> "$H3$bare"
            else -> bare
        }
    }

    /** 行去掉标题标记后的内容 */
    fun withoutHeading(line: String): String = line.dropWhile { it in HEADING_MARKERS }

    /** 光标所在行的下标（0 起） */
    fun lineIndexAt(text: String, cursor: Int): Int =
        text.substring(0, cursor.coerceIn(0, text.length)).count { it == '\n' }

    /** 光标所在行的 [起,止) 区间 */
    fun lineRangeAt(text: String, cursor: Int): IntRange {
        val pos = cursor.coerceIn(0, text.length)
        val start = if (pos == 0) 0 else text.lastIndexOf('\n', pos - 1) + 1
        val nl = text.indexOf('\n', pos)
        val end = if (nl == -1) text.length else nl
        return start until maxOf(end, start)
    }

    private fun bare(line: String): String = line.dropWhile { it in HEADING_MARKERS }

    fun hasCheckbox(line: String): Boolean {
        val b = bare(line)
        return b.startsWith(BOX_UNCHECKED_PREFIX) || b.startsWith(BOX_CHECKED_PREFIX)
    }

    /** 第4键：当前行切换勾选框（无→☐；☐/☑→移除） */
    fun toggleCheckboxLine(line: String): String {
        val b = bare(line)
        val head = line.take(line.length - b.length)
        return when {
            b.startsWith(BOX_UNCHECKED_PREFIX) || b.startsWith(BOX_CHECKED_PREFIX) -> head + b.substring(BOX_UNCHECKED_PREFIX.length)
            else -> "$head$BOX_UNCHECKED_PREFIX"
        }
    }

    /** 点勾选框：☐↔☑ */
    fun cycleCheckboxLine(line: String): String = when {
        line.startsWith(BOX_CHECKED_PREFIX) -> BOX_UNCHECKED_PREFIX + line.substring(BOX_CHECKED_PREFIX.length)
        line.startsWith(BOX_UNCHECKED_PREFIX) -> BOX_CHECKED_PREFIX + line.substring(BOX_UNCHECKED_PREFIX.length)
        else -> line
    }

    /** 回车继承：当前行带勾选框时，新行自动带 ☐ */
    fun inheritsCheckbox(line: String): Boolean = hasCheckbox(line)

    fun isImageLine(line: String): Boolean =
        line.startsWith(IMG_PREFIX) && line.endsWith(IMG_SUFFIX) && line.length > IMG_PREFIX.length + IMG_SUFFIX.length

    fun imageNameOf(line: String): String? =
        if (isImageLine(line)) line.removePrefix(IMG_PREFIX).removeSuffix(IMG_SUFFIX) else null

    /** 正文里按文档顺序出现的图片文件名 */
    fun imageNames(text: String): List<String> =
        text.split('\n').mapNotNull(::imageNameOf)

    /** 正文里按文档顺序出现的音频文件名（文件名以 aud_ 开头存在 images 目录） */
    fun audioNames(text: String): List<String> =
        imageNames(text).filter { it.startsWith("aud_") }

    /** 剥离标题标记（卡片/分享用） */
    fun stripHeadingMarkers(text: String): String =
        text.split('\n').joinToString("\n") { withoutHeading(it) }

    /** 预览用：图片/音频行转可读占位 */
    fun previewText(text: String): String =
        stripHeadingMarkers(text).split('\n')
            .joinToString("\n") {
                when {
                    isImageLine(it) && imageNameOf(it)?.startsWith("aud_") == true -> "［录音］"
                    isImageLine(it) -> "［图片］"
                    else -> it
                }
            }
}
