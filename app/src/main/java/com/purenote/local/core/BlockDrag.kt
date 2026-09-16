package com.purenote.local.core

/**
 * 块级拖拽排序的落点计算（纯函数，与 Compose 无关，可直接单测）。
 *
 * 结构借鉴小米笔记富文本编辑器的块拖拽（hooks/useDraggable + plugins/divider）：
 * 长按抬起整块 → 跟着手指走 → 一根插入指示线标出松手后的落点。
 * 与它只有一处区别：小米一律插到"悬停块之后"，这里按悬停块的上/下半区决定插在它前面还是后面，
 * 否则第一块永远拖不到文首（小米那份代码里 clientY 到顶时被强行设成首块，落点就只能是第二个）。
 */

/** 拖拽期间一个块在列表视口里的纵向范围（像素；原点 = 列表顶部） */
data class BlockSpan(val index: Int, val top: Float, val bottom: Float) {
    val middle: Float get() = (top + bottom) / 2f
}

/**
 * 指针纵坐标 → 插入边界下标（取值 0..块数）。
 *
 * 落在块的上半区 → 插到它前面，下半区 → 插到它后面；落在块之间的空隙归上一块的下边界；
 * 在可见范围之外则夹到首/尾（配合拖拽中的自动滚动，就是"拖到文首/文末"）。
 */
fun blockInsertIndex(spans: List<BlockSpan>, pointerY: Float, count: Int): Int {
    val total = count.coerceAtLeast(0)
    if (spans.isEmpty()) return total
    if (pointerY < spans.first().top) return 0
    for (s in spans) {
        if (pointerY < s.top) return s.index
        if (pointerY < s.bottom) return if (pointerY < s.middle) s.index else s.index + 1
    }
    return total
}

/**
 * 指针纵坐标 → 命中的块下标（长按抬起的是哪一块）；在可见范围之外夹到首/尾。
 * 与 [blockInsertIndex] 的区别：这里问的是"按在谁身上"，不是"要塞到哪条缝里"。
 */
fun blockIndexAt(spans: List<BlockSpan>, pointerY: Float): Int {
    if (spans.isEmpty()) return -1
    var fallback = spans.first().index
    for (s in spans) {
        if (pointerY < s.top) return fallback
        if (pointerY < s.bottom) return s.index
        fallback = s.index
    }
    return fallback
}

/**
 * 插入边界 → [RichDoc.move] 的目标下标（它的语义是"先移除、再按下标插入"）。
 *
 * 返回 null 表示这一次拖拽不改变顺序：调用方据此不写库、也不显示指示线。
 */
fun blockMoveTarget(from: Int, insertAt: Int): Int? {
    if (from < 0) return null
    val to = if (insertAt > from) insertAt - 1 else insertAt
    return if (to == from) null else to
}

/** 把 [blockId] 拖到 [insertAt] 边界；顺序没变时原样返回 */
fun RichDoc.moveToBoundary(blockId: String, insertAt: Int): RichDoc {
    val to = blockMoveTarget(indexOf(blockId), insertAt) ?: return this
    return move(blockId, to)
}
