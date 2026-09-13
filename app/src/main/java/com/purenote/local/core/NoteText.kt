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

    // 行首标签（与标题标记可叠加，互斥只能一个）：清单/无序/有序/引用/首行缩进。
    // 全部是纯文本前缀，存储保持可读；显示层由编辑器转换渲染。
    const val BULLET_PREFIX = "- "
    const val QUOTE_PREFIX = "> "
    const val INDENT_CHAR = '\u3000'
    const val INDENT_PREFIX = "$INDENT_CHAR$INDENT_CHAR"   // 首行缩进 = 2 个全角空格
    const val INDENT_SUFFIX = "$INDENT_CHAR$INDENT_CHAR"   // 尾行缩进 = 行尾 2 个全角空格

    /** 行首标签种类（NONE = 纯正文） */
    enum class HeadTag { NONE, CHECKBOX, BULLET, NUMBER, QUOTE, INDENT }

    /** 行标签解析结果：headLen=标题标记长度，tagLen=行首标签长度，number=有序列表序号 */
    data class TagInfo(val headLen: Int, val tag: HeadTag, val tagLen: Int, val number: Int)

    private val NUMBER_PREFIX_REGEX = Regex("""^(\d+)\. """)

    /** 解析一行的标题标记 + 行首标签 */
    fun tagInfo(line: String): TagInfo {
        val head = line.dropWhile { it in HEADING_MARKERS }
        val headLen = line.length - head.length
        val m = NUMBER_PREFIX_REGEX.find(head)
        return when {
            head.startsWith(BOX_UNCHECKED_PREFIX) || head.startsWith(BOX_CHECKED_PREFIX) ->
                TagInfo(headLen, HeadTag.CHECKBOX, BOX_UNCHECKED_PREFIX.length, 0)
            m != null -> TagInfo(headLen, HeadTag.NUMBER, m.value.length, m.groupValues[1].toInt())
            head.startsWith(BULLET_PREFIX) -> TagInfo(headLen, HeadTag.BULLET, BULLET_PREFIX.length, 0)
            head.startsWith(QUOTE_PREFIX) -> TagInfo(headLen, HeadTag.QUOTE, QUOTE_PREFIX.length, 0)
            head.startsWith(INDENT_PREFIX) -> TagInfo(headLen, HeadTag.INDENT, INDENT_PREFIX.length, 0)
            else -> TagInfo(headLen, HeadTag.NONE, 0, 0)
        }
    }

    /** 行内容起点（跳过标题标记与行首标签）在行内的偏移 */
    fun contentOffsetInLine(line: String): Int {
        val info = tagInfo(line)
        return info.headLen + info.tagLen
    }

    /**
     * 切换行首标签：同种→移除，不同种→替换，无→添加。
     * 返回 (新行, 新内容起点在行内偏移)——光标必须随前缀增删平移到内容起点。
     * numberForNew：新增有序列表时使用的起始序号。
     */
    fun toggleHeadTag(line: String, tag: HeadTag, numberForNew: Int = 1): Pair<String, Int> {
        val head = line.dropWhile { it in HEADING_MARKERS }
        val headLen = line.length - head.length
        val info = tagInfo(line)
        val content = head.substring(info.tagLen)
        val turningOff = info.tag == tag
        val newBare = when {
            turningOff -> content
            tag == HeadTag.CHECKBOX -> "$BOX_UNCHECKED_PREFIX$content"
            tag == HeadTag.BULLET -> "$BULLET_PREFIX$content"
            tag == HeadTag.NUMBER -> "$numberForNew. $content"
            tag == HeadTag.QUOTE -> "$QUOTE_PREFIX$content"
            tag == HeadTag.INDENT -> "$INDENT_PREFIX$content"
            else -> content
        }
        val newTagLen = if (turningOff) 0 else tagInfo(newBare).tagLen
        return (line.substring(0, headLen) + newBare) to (headLen + newTagLen)
    }

    /** 回车继承：上一行的标签转成新行的起始前缀（有序列表自动 +1），无标签返回 null */
    fun inheritTagPrefix(prevLine: String): String? {
        val info = tagInfo(prevLine)
        return when (info.tag) {
            HeadTag.CHECKBOX -> BOX_UNCHECKED_PREFIX
            HeadTag.BULLET -> BULLET_PREFIX
            HeadTag.NUMBER -> "${info.number + 1}. "
            HeadTag.QUOTE -> QUOTE_PREFIX
            HeadTag.INDENT -> INDENT_PREFIX
            HeadTag.NONE -> null
        }
    }

    /** 尾行缩进：有则去掉（2 个全角空格），无则追加 */
    fun toggleTailIndent(line: String): String =
        if (line.endsWith(INDENT_SUFFIX)) line.dropLast(INDENT_SUFFIX.length) else line + INDENT_SUFFIX

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

    /** 光标位置所在行的完整内容 */
    fun lineAt(text: String, cursor: Int): String {
        val range = lineRangeAt(text, cursor)
        return text.substring(range.first, range.last + 1)
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
            else -> "$head$BOX_UNCHECKED_PREFIX$b"
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

    // ---- 编辑器操作（基于光标位置改行） ----

    /** 用替换后的行重建正文 */
    fun replaceLine(text: String, range: IntRange, newLine: String): String =
        text.substring(0, range.first) + newLine + text.substring(range.last + 1)

    /** 在光标所在行插入图片/音频标记行（空行就地变图片行；否则在行尾另起一行） */
    fun insertImageLineAtCursor(text: String, cursor: Int, fileName: String): String {
        val range = lineRangeAt(text, cursor)
        val line = text.substring(range.first, range.last + 1)
        val tag = IMG_PREFIX + fileName + IMG_SUFFIX
        return if (line.isBlank()) {
            replaceLine(text, range, tag)
        } else {
            text.substring(0, range.last + 1) + "\n" + tag
        }
    }

    /** 当前光标选中的图片行文件名（用于替换手写图等场景）；无则 null */
    fun selectedImageNameAt(text: String, cursor: Int): String? {
        val range = lineRangeAt(text, cursor)
        return imageNameOf(text.substring(range.first, range.last + 1))
    }
}
