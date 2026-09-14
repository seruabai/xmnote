package com.purenote.local.core

import com.purenote.local.data.ChecklistItem

/**
 * 清单条目的紧凑序列化。
 * 每行格式：标志(0/1) + 分隔符 + 文本；文本中的换行与分隔符会被替换，保证可逆解析。
 */
object ChecklistCodec {

    const val SEP = ''   // ASCII 0x1F 单元分隔符(不可见)

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
 * 笔记正文存储格式（Markdown，v1.2.21 起）：
 * - 标题：行首 "# "/"## "/"### "
 * - 行内任务（勾选框）：行首 "- [ ] "（未完成）/ "- [x] "（已完成），勾选后文字置灰
 * - 图片/录音块：整行 "![](文件名)"（文件在本应用 images 目录；导出时随附件一起走，标准 Markdown 语法）
 * - 无序列表 "- "；有序列表 "1. "；引用 "> "；首行缩进 "　　"（全角空格，纯文本）
 * 正文即标准 Markdown：导出/备份/跨应用兼容性最好（用户 2026-09-13 要求）。
 */
object NoteMarkup {

    // ---- Markdown 语法常量 ----
    const val MD_H1 = "# "
    const val MD_H2 = "## "
    const val MD_H3 = "### "

    const val TASK_TODO = "- [ ] "
    const val TASK_DONE = "- [x] "

    const val IMG_PREFIX = "![]("
    const val IMG_SUFFIX = ")"

    const val BULLET_PREFIX = "- "
    const val QUOTE_PREFIX = "> "
    const val INDENT_CHAR = '\u3000'
    const val INDENT_PREFIX = "$INDENT_CHAR$INDENT_CHAR"   // 首行缩进 = 2 个全角空格
    const val INDENT_SUFFIX = "$INDENT_CHAR$INDENT_CHAR"   // 尾行缩进 = 行尾 2 个全角空格

    // ---- 行内标签模型 ----

    /** 行首标签种类（NONE = 纯正文） */
    enum class HeadTag { NONE, CHECKBOX, BULLET, NUMBER, QUOTE, INDENT }

    /** 行标签解析结果：headLen=标题标记长度，level=标题级别(1-3)，tagLen=行首标签长度，number=有序列表序号 */
    data class TagInfo(val headLen: Int, val level: Int, val tag: HeadTag, val tagLen: Int, val number: Int)

    private val NUMBER_PREFIX_REGEX = Regex("""^(\d+)\. """)
    private val HEADING_PREFIX_REGEX = Regex("^(#{1,3}) ")

    /** 解析一行的 Markdown 标题标记 + 行首标签 */
    fun tagInfo(line: String): TagInfo {
        val hm = HEADING_PREFIX_REGEX.find(line)
        val headLen = hm?.value?.length ?: 0
        val level = hm?.groupValues?.get(1)?.length ?: 0
        val rest = line.substring(headLen)
        val m = NUMBER_PREFIX_REGEX.find(rest)
        // 序号超出 Int 上限（如 "13800138000. 张三" / "20260913120000. 会议"）时按普通正文处理。
        // 曾用 groupValues[1].toInt()，对这类行抛 NumberFormatException：
        // 首页卡片渲染(tagInfo←withoutHeading←stripHeadingMarkers←NoteCard)直接崩溃，升级迁移(onUpgrade)回滚后每次开库重抛。
        val bigNumber = m?.groupValues?.get(1)?.toLongOrNull()
        val (tag, tagLen, number) = when {
            rest.startsWith(TASK_TODO) -> Triple(HeadTag.CHECKBOX, TASK_TODO.length, 0)
            rest.startsWith(TASK_DONE) -> Triple(HeadTag.CHECKBOX, TASK_DONE.length, 0)
            m != null && bigNumber != null && bigNumber <= Int.MAX_VALUE ->
                Triple(HeadTag.NUMBER, m.value.length, bigNumber.toInt())
            rest.startsWith(BULLET_PREFIX) -> Triple(HeadTag.BULLET, BULLET_PREFIX.length, 0)
            rest.startsWith(QUOTE_PREFIX) -> Triple(HeadTag.QUOTE, QUOTE_PREFIX.length, 0)
            rest.startsWith(INDENT_PREFIX) -> Triple(HeadTag.INDENT, INDENT_PREFIX.length, 0)
            else -> Triple(HeadTag.NONE, 0, 0)
        }
        return TagInfo(headLen, level, tag, tagLen, number)
    }

    /** 行内容起点（跳过标题标记与行首标签）在行内的偏移 */
    fun contentOffsetInLine(line: String): Int {
        val info = tagInfo(line)
        return info.headLen + info.tagLen
    }

    /** 兼容旧名：标题级别（1-3，0=正文） */
    fun headingLevel(line: String): Int = tagInfo(line).level

    /** 给行设置标题级别；level 0 = 移除标题标记（正文）。原有的任务/列表等标签保留 */
    fun withHeading(line: String, level: Int): String {
        val info = tagInfo(line)
        val bare = line.substring(info.headLen)
        val md = when (level) {
            1 -> MD_H1
            2 -> MD_H2
            3 -> MD_H3
            else -> ""
        }
        return md + bare
    }

    /** 行去掉标题标记后的内容 */
    fun withoutHeading(line: String): String {
        val info = tagInfo(line)
        return line.substring(info.headLen)
    }

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

    /**
     * 图片行必须是"完整整行"。若它恰好是最后一行且没有尾换行，光标只能停在标记内部
     * （钳制逻辑在 lineEnd == text.length 时无处可去），此后任何输入都会破坏标记，
     * 进而让 imageNames(body) 找不到文件、附件从笔记中永久消失。统一补一个尾换行。
     */
    fun terminateTrailingImageLine(text: String): String {
        if (text.isEmpty() || text.endsWith("\n")) return text
        val lastStart = text.lastIndexOf('\n') + 1
        return if (isImageLine(text.substring(lastStart))) "$text\n" else text
    }

    /** 光标位置所在行的完整内容 */
    fun lineAt(text: String, cursor: Int): String {
        val range = lineRangeAt(text, cursor)
        return text.substring(range.first, range.last + 1)
    }

    fun hasCheckbox(line: String): Boolean {
        val info = tagInfo(line)
        return info.tag == HeadTag.CHECKBOX
    }

    /** 勾选框切换为任务语法；非任务行原样返回 */
    fun toggleCheckboxLine(line: String): String {
        val info = tagInfo(line)
        val head = line.substring(0, info.headLen)
        val bare = line.substring(info.headLen)
        val content = bare.substring(info.tagLen)
        return when (info.tag) {
            HeadTag.CHECKBOX -> head + content
            else -> head + TASK_TODO + bare
        }
    }

    /** 点勾选框：- [ ] ↔ - [x] */
    fun cycleCheckboxLine(line: String): String {
        val info = tagInfo(line)
        if (info.tag != HeadTag.CHECKBOX) return line
        val head = line.substring(0, info.headLen)
        val bare = line.substring(info.headLen)
        val flipped = if (bare.startsWith(TASK_DONE)) TASK_TODO + bare.substring(TASK_DONE.length)
        else TASK_DONE + bare.substring(TASK_TODO.length)
        return head + flipped
    }

    /** 兼容旧调用 */
    fun inheritsCheckbox(line: String): Boolean = hasCheckbox(line)

    /**
     * 切换行首标签：同种→移除，不同种→替换，无→添加。
     * 返回 (新行, 新内容起点在行内偏移)——光标必须随前缀增删平移到内容起点。
     * numberForNew：新增有序列表时使用的起始序号。
     */
    fun toggleHeadTag(line: String, tag: HeadTag, numberForNew: Int = 1): Pair<String, Int> {
        val info = tagInfo(line)
        val head = line.substring(info.headLen)
        val content = head.substring(info.tagLen)
        val turningOff = info.tag == tag
        val newBare = when {
            turningOff -> content
            tag == HeadTag.CHECKBOX -> TASK_TODO + content
            tag == HeadTag.BULLET -> BULLET_PREFIX + content
            tag == HeadTag.NUMBER -> "$numberForNew. $content"
            tag == HeadTag.QUOTE -> QUOTE_PREFIX + content
            tag == HeadTag.INDENT -> INDENT_PREFIX + content
            else -> content
        }
        val newTagLen = if (turningOff) 0 else tagInfo(newBare).tagLen
        return (line.substring(0, info.headLen) + newBare) to (info.headLen + newTagLen)
    }

    /** 回车继承：上一行的标签转成新行的起始前缀（有序列表自动 +1），无标签返回 null */
    fun inheritTagPrefix(prevLine: String): String? {
        val info = tagInfo(prevLine)
        return when (info.tag) {
            HeadTag.CHECKBOX -> TASK_TODO
            HeadTag.BULLET -> BULLET_PREFIX
            HeadTag.NUMBER -> "${if (info.number >= Int.MAX_VALUE) 1 else info.number + 1}. "
            HeadTag.QUOTE -> QUOTE_PREFIX
            HeadTag.INDENT -> INDENT_PREFIX
            HeadTag.NONE -> null
        }
    }

    /** 尾行缩进：有则去掉（2 个全角空格），无则追加 */
    fun toggleTailIndent(line: String): String =
        if (line.endsWith(INDENT_SUFFIX)) line.dropLast(INDENT_SUFFIX.length) else line + INDENT_SUFFIX

    // ---- 图片/录音块 ----

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

    /** 预览用：图片/音频行转可读占位，任务标记转 ☐/☑ 字形 */
    fun previewText(text: String): String =
        stripHeadingMarkers(text).split('\n')
            .joinToString("\n") { line ->
                when {
                    isImageLine(line) && imageNameOf(line)?.startsWith("aud_") == true -> "［录音］"
                    isImageLine(line) -> "［图片］"
                    line.startsWith(TASK_DONE) -> "☑ " + line.substring(TASK_DONE.length)
                    line.startsWith(TASK_TODO) -> "☐ " + line.substring(TASK_TODO.length)
                    else -> line
                }
            }

    // ---- 编辑器操作（基于光标位置改行） ----

    /**
     * 图片行被追加字符时的拆行修复（纯函数便于测试）：
     * "![](a.jpg)x" → "![](a.jpg)\nx"，保持图片行原子，避免标记被拆散后图片永久消失。
     *
     * 标记的结束位置是 [IMG_PREFIX] 之后的第一个 ')'。
     * 曾用 indexOf(']')：新语法 "![](name)" 里 ']' 位于第 3 个字符，会把标记本身切成 "![]",
     * 实测 "![](img_1.jpg)c" → "![]\n(img_1.jpg)c"。
     *
     * @param cursorAfter 追加字符后的光标位置
     * @return (新文本, 新光标)；无需修复时返回 null
     */
    fun imageLineAppendIntercept(text: String, cursorAfter: Int): Pair<String, Int>? {
        val insAt = cursorAfter - 1
        if (insAt < 0 || insAt >= text.length) return null
        val range = lineRangeAt(text, insAt)
        val line = text.substring(range.first, range.last + 1)
        if (!line.startsWith(IMG_PREFIX) || isImageLine(line)) return null
        val close = line.indexOf(')', IMG_PREFIX.length)
        if (close !in IMG_PREFIX.length until line.length) return null
        val fixedLine = line.substring(0, close + 1) + "\n" + line.substring(close + 1)
        val patched = text.substring(0, range.first) + fixedLine + text.substring(range.last + 1)
        return patched to (insAt + 2).coerceAtMost(patched.length)
    }

    /** 用替换后的行重建正文 */
    fun replaceLine(text: String, range: IntRange, newLine: String): String =
        text.substring(0, range.first) + newLine + text.substring(range.last + 1)

    /** 在光标所在行插入图片/音频标记行（空行就地变图片行；否则在行尾另起一行） */
    fun insertImageLineAtCursor(text: String, cursor: Int, fileName: String): String {
        val range = lineRangeAt(text, cursor)
        val line = text.substring(range.first, range.last + 1)
        val tag = IMG_PREFIX + fileName + IMG_SUFFIX
        val out = if (line.isBlank()) {
            replaceLine(text, range, tag)
        } else {
            // 必须拼回本行之后的全部内容：曾漏掉 text.substring(range.last + 1)，
            // 导致光标行之后的所有行被永久删除（拍照/相册/录音/手写四个入口均触发，500ms 防抖自动落库）。
            text.substring(0, range.last + 1) + "\n" + tag + text.substring(range.last + 1)
        }
        return terminateTrailingImageLine(out)
    }

    /** 当前光标选中的图片行文件名（用于替换手写图等场景）；无则 null */
    fun selectedImageNameAt(text: String, cursor: Int): String? {
        val range = lineRangeAt(text, cursor)
        return imageNameOf(text.substring(range.first, range.last + 1))
    }

    // ---- 旧格式迁移（v1 标记 → Markdown，纯函数便于测试） ----

    private val LEGACY_HEADING = mapOf('\uE201' to MD_H1, '\uE202' to MD_H2, '\uE203' to MD_H3)

    /**
     * 把 v1.2.20 私有标记正文迁移为 Markdown：
     * - PUA 标题字符 → "# "/"## "/"### "
     * - "☐ "/"☑ " → "- [ ] "/"- [x] "（可叠加在标题后）
     * - "[img:文件名]" 整行 → "![](文件名)"
     * 其余（无序/有序/引用/缩进/普通文本）原样保留。幂等：对已是 Markdown 的文本原样返回。
     */
    fun migrateBodyV1toV2(raw: String): String =
        raw.split('\n').joinToString("\n") { line ->
            var out = line
            // 1) 标题标记
            out = when {
                out.isNotEmpty() && LEGACY_HEADING.containsKey(out[0]) -> LEGACY_HEADING[out[0]] + out.substring(1)
                else -> out
            }
            // 2) 勾选前缀（可能紧跟在标题标记之后）
            val bodyStart = tagInfo(out).headLen
            val head = out.substring(0, bodyStart)
            var rest = out.substring(bodyStart)
            rest = when {
                rest.startsWith("\u2610 ") -> TASK_TODO + rest.substring(2)   // ☐
                rest.startsWith("\u2611 ") -> TASK_DONE + rest.substring(2)   // ☑
                else -> rest
            }
            out = head + rest
            // 3) 图片行
            val legacy = out.startsWith("[img:") && out.endsWith("]") && out.length > 6
            if (legacy) IMG_PREFIX + out.substring(5, out.length - 1) + IMG_SUFFIX else out
        }
}
