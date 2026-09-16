package com.purenote.local.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 块拖拽落点：把"手指在哪 → 块落到哪"这条链拆成可复现的断言。
 * 设备上只能看到最终顺序，中间每一步的边界判断必须在单测里锁住。
 */
class BlockDragTest {

    /** 四块，每块高 100，块间 10 空隙：A[0,100) B[110,210) C[220,320) D[330,430) */
    private val spans = listOf(
        BlockSpan(0, 0f, 100f),
        BlockSpan(1, 110f, 210f),
        BlockSpan(2, 220f, 320f),
        BlockSpan(3, 330f, 430f),
    )

    private fun doc(vararg ids: String): RichDoc =
        RichDoc(blocks = ids.map { RichBlock.text(it, it) })

    // ------------------------------------------------------------ 插入边界

    @Test
    fun pointerInUpperHalfInsertsBeforeThatBlock() {
        assertEquals(1, blockInsertIndex(spans, 130f, 4))   // B 的上半区 → B 之前
        assertEquals(2, blockInsertIndex(spans, 240f, 4))   // C 的上半区
    }

    @Test
    fun pointerInLowerHalfInsertsAfterThatBlock() {
        assertEquals(2, blockInsertIndex(spans, 190f, 4))   // B 的下半区 → B 之后
        assertEquals(4, blockInsertIndex(spans, 400f, 4))   // D 的下半区 → 文末
    }

    @Test
    fun pointerInGapBelongsToBlockAbove() {
        assertEquals(1, blockInsertIndex(spans, 105f, 4))   // A 与 B 之间 → A 的下边界
        assertEquals(3, blockInsertIndex(spans, 325f, 4))
    }

    @Test
    fun pointerOutsideVisibleRangeClampsToEnds() {
        assertEquals(0, blockInsertIndex(spans, -40f, 4))   // 拖到列表上方 → 文首
        assertEquals(4, blockInsertIndex(spans, 900f, 4))   // 拖到列表下方 → 文末
    }

    @Test
    fun emptyVisibleSpansFallsBackToDocEnd() {
        assertEquals(0, blockInsertIndex(emptyList(), 10f, 0))
        assertEquals(3, blockInsertIndex(emptyList(), 10f, 3))
    }

    // ------------------------------------------------------------ 长按命中

    @Test
    fun longPressHitsTheBlockUnderTheFinger() {
        assertEquals(0, blockIndexAt(spans, 50f))
        assertEquals(1, blockIndexAt(spans, 200f))
        assertEquals(3, blockIndexAt(spans, 429f))
    }

    @Test
    fun longPressInGapOrOutsideStaysOnANeighbour() {
        assertEquals(0, blockIndexAt(spans, 105f))     // 空隙归上一块
        assertEquals(0, blockIndexAt(spans, -80f))     // 列表上方的可见首块
        assertEquals(3, blockIndexAt(spans, 900f))     // 列表下方 → 末块
        assertEquals(-1, blockIndexAt(emptyList(), 10f))
    }

    // ------------------------------------------------------------ 边界 → 目标下标

    @Test
    fun moveDownKeepsTargetInsideShortenedList() {
        // A(0) 拖到 C 之后(边界 3) → [B, C, A, D]，移除 A 后 C 的下标是 1，插入点 2
        assertEquals(2, blockMoveTarget(0, 3))
    }

    @Test
    fun moveUpShiftsByOne() {
        // D(3) 拖到 B 之前(边界 1) → [A, D, B, C]，插入点 1
        assertEquals(1, blockMoveTarget(3, 1))
        // D(3) 拖到文首(边界 0)
        assertEquals(0, blockMoveTarget(3, 0))
    }

    @Test
    fun adjacentBoundaryIsNoOp() {
        // 拖到"自己原来的位置"：A(0) 的边界 1 与 D(3) 的边界 3 都不改变顺序
        assertNull(blockMoveTarget(0, 1))
        assertNull(blockMoveTarget(0, 0))
        assertNull(blockMoveTarget(3, 3))
        assertNull(blockMoveTarget(3, 4))
    }

    @Test
    fun sameBlockIsNoOpAndNegativeIndexIgnored() {
        assertNull(blockMoveTarget(2, 2))
        assertNull(blockMoveTarget(2, 3))
        assertNull(blockMoveTarget(-1, 0))
    }

    // ------------------------------------------------------------ 与 RichDoc.move 串起来

    @Test
    fun moveToBoundaryReordersDocExactlyLikeTheIndicatorShowed() {
        val d = doc("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), d.moveToBoundary("a", 3).blocks.map { it.id })
        assertEquals(listOf("a", "d", "b", "c"), d.moveToBoundary("d", 1).blocks.map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), d.moveToBoundary("d", 0).blocks.map { it.id })
        assertEquals(listOf("c", "a", "b", "d"), d.moveToBoundary("c", 0).blocks.map { it.id })
    }

    @Test
    fun moveToBoundaryNoOpKeepsSameInstance() {
        val d = doc("a", "b", "c")
        assertEquals(d, d.moveToBoundary("a", 1))
        assertEquals(d, d.moveToBoundary("a", 0))
        assertEquals(d, d.moveToBoundary("x", 0))   // 不存在的块
    }
}
