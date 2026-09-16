package com.purenote.local.core

import kotlinx.serialization.Serializable

/**
 * 富文本块模型（body_format_version = 3）。
 *
 * 结构借鉴小米笔记富文本编辑器的块定义（utils/note-parser/def.ts 的 BlockType：
 * text / todo / item / image / sound / link + fragments 行内片段 + sizeLevel/textAlign/listType/checked），
 * 代码为 Kotlin 重写；为兼容本项目既有语法另加 QUOTE 与两个缩进标记。
 *
 * 为什么是扁平列表而不是嵌套树：块级操作（换行拆块、退格并块、拖拽排序、
 * 在光标处插图）在扁平表上都是常数规模的下标运算，嵌套结构做不到。
 */
@Serializable
enum class BlockType { TEXT, TODO, ITEM, QUOTE, IMAGE, SOUND, LINK }

@Serializable
enum class TextAlign { NONE, LEFT, CENTER, RIGHT }

/** 对应小米笔记的 sizeLevel（large/middle） */
@Serializable
enum class SizeLevel { NONE, LARGE, MIDDLE }

/** 行内样式标记 */
@Serializable
enum class InlineMark { BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT }

/** 行内片段：一段同样式的文本。空文本片段不应存在 */
@Serializable
data class Fragment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val highlight: Boolean = false,
    val link: String? = null,
) {
    val isPlain: Boolean
        get() = !bold && !italic && !underline && !strike && !highlight && link == null

    fun with(mark: InlineMark, on: Boolean): Fragment = when (mark) {
        InlineMark.BOLD -> copy(bold = on)
        InlineMark.ITALIC -> copy(italic = on)
        InlineMark.UNDERLINE -> copy(underline = on)
        InlineMark.STRIKE -> copy(strike = on)
        InlineMark.HIGHLIGHT -> copy(highlight = on)
    }

    fun has(mark: InlineMark): Boolean = when (mark) {
        InlineMark.BOLD -> bold
        InlineMark.ITALIC -> italic
        InlineMark.UNDERLINE -> underline
        InlineMark.STRIKE -> strike
        InlineMark.HIGHLIGHT -> highlight
    }
}

@Serializable
data class RichBlock(
    val id: String,
    val type: BlockType = BlockType.TEXT,
    val fragments: List<Fragment> = emptyList(),
    /** 0 = 正文，1-3 = H1-H3（对应 NoteMarkup 的 MD_H1..MD_H3） */
    val headingLevel: Int = 0,
    /** 仅 TODO 使用 */
    val checked: Boolean = false,
    /** 仅 ITEM 使用，>0 表示有序列表序号，0 表示无序 */
    val number: Int = 0,
    /** 首行缩进（两个全角空格） */
    val indentHead: Boolean = false,
    /** 尾行缩进（行尾两个全角空格） */
    val indentTail: Boolean = false,
    /** IMAGE / SOUND 的附件文件名 */
    val fileId: String? = null,
    /** 图片描述与显示方式（对应小米笔记的 imgdes / imgshow） */
    val imageDesc: String? = null,
    val imageShow: String? = null,
    /** LINK 块 */
    val href: String? = null,
    val linkTitle: String? = null,
    val textAlign: TextAlign = TextAlign.NONE,
    val sizeLevel: SizeLevel = SizeLevel.NONE,
) {
    val text: String get() = fragments.joinToString("") { it.text }

    val isEmbed: Boolean get() = type == BlockType.IMAGE || type == BlockType.SOUND
    val isListItem: Boolean get() = type == BlockType.TODO || type == BlockType.ITEM
    /** 光标可落的块：嵌入块不可编辑文本 */
    val isEditable: Boolean get() = !isEmbed

    /**
     * 可否长按拖拽排序。
     *
     * 只允许没有输入框的块（图片/录音/链接卡片）：文本块铺满整行，长按很容易在
     * "边想边点"时误触发；文本块的长按留给选字，也更符合手机笔记的直觉。
     */
    val draggable: Boolean
        get() = type == BlockType.IMAGE || type == BlockType.SOUND || type == BlockType.LINK

    companion object {
        fun text(id: String, content: String = ""): RichBlock =
            RichBlock(id = id, type = BlockType.TEXT, fragments = listOf(Fragment(content)))
    }
}

@Serializable
data class RichDoc(
    val version: Int = RichDocCodec.CURRENT_VERSION,
    val blocks: List<RichBlock> = listOf(RichBlock.text(FIRST_BLOCK_ID)),
) {
    companion object {
        const val FIRST_BLOCK_ID = "b0"
    }
}

/** 块 id 生成。与 MindIds 同思路：id 由调用方给，保证所有操作是纯函数 */
object BlockIds {
    private var counter = 0

    /** 单测里要可复现，故用计数后缀而不是 UUID；实例级唯一即可 */
    fun newBlockId(): String = "b" + java.util.UUID.randomUUID().toString().take(8)

    internal fun nextLocalId(): String = "b" + (counter++)
}

// ---------------------------------------------------------------- 查询

fun RichDoc.indexOf(blockId: String): Int = blocks.indexOfFirst { it.id == blockId }

fun RichDoc.find(blockId: String): RichBlock? = blocks.firstOrNull { it.id == blockId }

/** 全文纯文本（标题推导、搜索、预览都用它，替代过去直接读 body） */
fun RichDoc.plainText(): String = blocks.joinToString("\n") { it.text }

/** 卡片标题：第一个非空文本块的文字 */
fun RichDoc.derivedTitle(): String =
    blocks.firstOrNull { it.type != BlockType.IMAGE && it.text.isNotBlank() }?.text?.trim().orEmpty()

/** 清单进度（已完成, 总数）；没有勾选块时返回 (0, 0) */
fun RichDoc.checklistProgress(): Pair<Int, Int> {
    val todos = blocks.filter { it.type == BlockType.TODO }
    return todos.count { it.checked } to todos.size
}

/** 正文里按文档顺序出现的附件名（图片 + 录音） */
fun RichDoc.attachmentNames(): List<String> = blocks.mapNotNull { it.fileId }

// ---------------------------------------------------------------- 行内片段运算

/** 把片段序列在 [offset] 处切开（offset 按纯文本计），两侧片段样式保持不变 */
internal fun splitFragmentsAt(
    fragments: List<Fragment>,
    offset: Int,
): Pair<List<Fragment>, List<Fragment>> {
    var acc = 0
    val left = ArrayList<Fragment>()
    val right = ArrayList<Fragment>()
    for (f in fragments) {
        val len = f.text.length
        when {
            acc + len <= offset -> left += f
            acc >= offset -> right += f
            else -> {
                val cut = offset - acc
                left += f.copy(text = f.text.substring(0, cut))
                right += f.copy(text = f.text.substring(cut))
            }
        }
        acc += len
    }
    return left to right
}

/** 对 [start, end) 区间内的片段做变换，区间外保持不变 */
internal fun mapFragmentRange(
    fragments: List<Fragment>,
    start: Int,
    end: Int,
    transform: (Fragment) -> Fragment,
): List<Fragment> {
    var acc = 0
    val out = ArrayList<Fragment>()
    for (f in fragments) {
        val len = f.text.length
        val from = maxOf(start, acc)
        val to = minOf(end, acc + len)
        if (from >= to) {
            out += f
        } else {
            val head = f.text.substring(0, from - acc)
            val mid = f.text.substring(from - acc, to - acc)
            val tail = f.text.substring(to - acc)
            if (head.isNotEmpty()) out += f.copy(text = head)
            if (mid.isNotEmpty()) out += transform(f.copy(text = mid))
            if (tail.isNotEmpty()) out += f.copy(text = tail)
        }
        acc += len
    }
    return mergeAdjacent(out)
}

/** 合并样式相同且相邻的片段，避免编辑多次后碎片爆炸 */
internal fun mergeAdjacent(fragments: List<Fragment>): List<Fragment> {
    val out = ArrayList<Fragment>(fragments.size)
    for (f in fragments) {
        if (f.text.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && sameStyle(last, f)) {
            out[out.size - 1] = last.copy(text = last.text + f.text)
        } else {
            out += f
        }
    }
    return out
}

private fun sameStyle(a: Fragment, b: Fragment): Boolean =
    a.bold == b.bold && a.italic == b.italic && a.underline == b.underline &&
        a.strike == b.strike && a.highlight == b.highlight && a.link == b.link

// ---------------------------------------------------------------- 块级运算

private fun RichDoc.replaceAt(index: Int, block: RichBlock): RichDoc =
    copy(blocks = blocks.toMutableList().also { it[index] = block })

fun RichDoc.insert(block: RichBlock, index: Int = -1): RichDoc {
    val list = blocks.toMutableList()
    val at = if (index < 0 || index > list.size) list.size else index
    list.add(at, block)
    return copy(blocks = list)
}

/** 删除块；**永不留空文档**，删到只剩一块时保留一个空文本块 */
fun RichDoc.remove(blockId: String): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    val list = blocks.toMutableList().also { it.removeAt(idx) }
    if (list.isEmpty()) list += RichBlock.text(RichDoc.FIRST_BLOCK_ID)
    return copy(blocks = list)
}

/**
 * 拖拽排序（对应小米笔记编辑器的块拖拽）。
 * [toIndex] 是**移除该块之后**的列表下标，语义与 List.add 一致。
 */
fun RichDoc.move(blockId: String, toIndex: Int): RichDoc {
    val from = indexOf(blockId)
    if (from < 0) return this
    val list = blocks.toMutableList()
    val block = list.removeAt(from)
    val at = toIndex.coerceIn(0, list.size)
    list.add(at, block)
    return copy(blocks = list)
}

fun RichDoc.updateText(blockId: String, content: String): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    val old = blocks[idx]
    if (!old.isEditable) return this
    val first = old.fragments.firstOrNull()
    // 整块替换文字时保留首片段的样式，避免用户打了半天样式被重置
    val fragments = listOf((first ?: Fragment("")).copy(text = content))
    return replaceAt(idx, old.copy(fragments = fragments))
}

fun RichDoc.setType(blockId: String, type: BlockType): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    val old = blocks[idx]
    if (old.isEmbed) return this
    return replaceAt(
        idx,
        old.copy(
            type = type,
            checked = if (type == BlockType.TODO) old.checked else false,
            number = if (type == BlockType.ITEM) old.number else 0,
        ),
    )
}

/**
 * 行首标签（清单/项目符号/有序列表/引用）的开关，语义与旧 `NoteMarkup.toggleHeadTag` 一致：
 * 同种 → 取消（只回落到 TEXT），不同种 → 替换，无 → 添加。标题级别与缩进不受影响。
 *
 * 这是工具栏从"改标记文本的一行"切到"改光标所在块"之后，标签类的落点。
 */
fun RichDoc.toggleHeadTag(blockId: String, type: BlockType, ordered: Boolean = false): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    val old = blocks[idx]
    if (old.isEmbed) return this
    // ITEM 分有序（1. 2. 3.）与无序（•）两种形态，靠 number 区分；
    // 取消判据必须也带上它，否则按 "1." 会把已在的无序列表当成"同种"直接取消
    val sameForm = old.type == type && (type != BlockType.ITEM || (old.number > 0) == ordered)
    if (sameForm) return setType(blockId, BlockType.TEXT)
    if (type !in HEAD_TAGS) return this
    val number = if (type == BlockType.ITEM && ordered) {
        // 有序列表接续：上一块也是有序列表就 +1，否则从 1 开始（旧行为）
        val prev = blocks.getOrNull(idx - 1)
        if (prev != null && prev.type == BlockType.ITEM && prev.number > 0) prev.number + 1 else 1
    } else {
        0
    }
    return replaceAt(
        idx,
        old.copy(
            type = type,
            checked = false,
            number = number,
        ),
    )
}

private val HEAD_TAGS = setOf(BlockType.TODO, BlockType.ITEM, BlockType.QUOTE)

fun RichDoc.toggleChecked(blockId: String): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    val old = blocks[idx]
    if (old.type != BlockType.TODO) return this
    return replaceAt(idx, old.copy(checked = !old.checked))
}

fun RichDoc.setHeading(blockId: String, level: Int): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    return replaceAt(idx, blocks[idx].copy(headingLevel = level.coerceIn(0, 3)))
}

fun RichDoc.toggleIndentHead(blockId: String): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    return replaceAt(idx, blocks[idx].copy(indentHead = !blocks[idx].indentHead))
}

fun RichDoc.toggleIndentTail(blockId: String): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    return replaceAt(idx, blocks[idx].copy(indentTail = !blocks[idx].indentTail))
}

/** 回车：在 [offset] 处把块拆成两块，[newId] 为后半块 id，块级属性由调用方随后设置 */
fun RichDoc.splitAt(blockId: String, offset: Int, newId: String): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    val old = blocks[idx]
    if (old.isEmbed) return this
    val (left, right) = splitFragmentsAt(old.fragments, offset.coerceIn(0, old.text.length))
    val leftBlock = old.copy(fragments = left)
    // 继承块级外观（标题级别/缩进/对齐/字号），否则回车后新段会掉样式。
    // checked 与 number 不继承：勾选状态与序号由调用方按"回车续接"语义决定
    val rightBlock = RichBlock(
        id = newId,
        type = old.type,
        fragments = right,
        headingLevel = old.headingLevel,
        indentHead = old.indentHead,
        indentTail = old.indentTail,
        textAlign = old.textAlign,
        sizeLevel = old.sizeLevel,
    )
    val list = blocks.toMutableList()
    list[idx] = leftBlock
    list.add(idx + 1, rightBlock)
    return copy(blocks = list)
}

/** 退格并块：把 [blockId] 并入上一块，光标停在接缝处（返回接缝偏移） */
fun RichDoc.mergeWithPrevious(blockId: String): Pair<RichDoc, Int> {
    val idx = indexOf(blockId)
    if (idx <= 0) return this to 0
    val prev = blocks[idx - 1]
    val cur = blocks[idx]
    if (prev.isEmbed || cur.isEmbed) return this to 0
    val seam = prev.text.length
    val merged = prev.copy(fragments = mergeAdjacent(prev.fragments + cur.fragments))
    val list = blocks.toMutableList()
    list[idx - 1] = merged
    list.removeAt(idx)
    return copy(blocks = list) to seam
}

/** 行内样式开关：对 [start, end) 区间生效；区间内是否已全部带该样式决定开还是关 */
fun RichDoc.toggleMark(blockId: String, start: Int, end: Int, mark: InlineMark): RichDoc {
    val idx = indexOf(blockId)
    if (idx < 0) return this
    val old = blocks[idx]
    if (!old.isEditable || start >= end) return this
    val on = !isMarkCovered(old, start, end, mark)
    val fragments = mapFragmentRange(old.fragments, start, end) { it.with(mark, on) }
    return replaceAt(idx, old.copy(fragments = fragments))
}

/** [start, end) 是否已整体带该样式（决定下次点击是加还是取消） */
fun isMarkCovered(block: RichBlock, start: Int, end: Int, mark: InlineMark): Boolean {
    var acc = 0
    var covered = 0
    val total = end - start
    if (total <= 0) return false
    for (f in block.fragments) {
        val len = f.text.length
        val from = maxOf(start, acc)
        val to = minOf(end, acc + len)
        if (from < to && f.has(mark)) covered += to - from
        acc += len
    }
    return covered >= total
}
