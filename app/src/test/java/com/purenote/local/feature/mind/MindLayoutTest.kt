package com.purenote.local.feature.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MindLayoutTest {

    private val cfg = MindLayoutConfig()

    private fun build(depth: Int, breadth: Int): MindNode {
        fun rec(id: String, d: Int): MindNode {
            val kids = if (d == 0) emptyList() else (1..breadth).map { rec(id + "-" + it, d - 1) }
            return MindNode(id = id, label = "节点" + id, children = kids)
        }
        return rec("r", depth)
    }

    @Test
    fun sameColumnNodesNeverOverlapVertically() {
        val result = MindLayout.compute(build(depth = 3, breadth = 3), cfg)
        result.nodes.groupBy { it.depth }.forEach { (depth, column) ->
            val sorted = column.sortedBy { it.y }
            sorted.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    "第 " + depth + " 层 " + a.id + " 与 " + b.id + " 重叠",
                    a.bottom <= b.y + 0.01f,
                )
            }
        }
    }

    @Test
    fun parentSitsOnTheCenterLineOfItsChildrenBlock() {
        val tree = MindNode(
            id = "a", label = "中心",
            children = listOf(
                MindNode("b", "一", children = listOf(MindNode("b1", "一之一"))),
                MindNode("c", "二"),
                MindNode("d", "三"),
            ),
        )
        val result = MindLayout.compute(tree, cfg)
        val nodes = result.nodes.associateBy { it.id }
        val parent = nodes.getValue("a")
        val visible = tree.visibleChildren()
        val top = nodes.getValue(visible.first().id).y
        val lastNode = nodes.getValue(visible.last().id)
        // 子块中线：首个子节点盒顶到最后子节点盒底
        val blockCenter = (top + lastNode.bottom) / 2f
        assertTrue(
            "父节点未居中于子节点块：parent=" + parent.centerY + " block=" + blockCenter,
            abs(parent.centerY - blockCenter) < 0.01f,
        )
    }

    @Test
    fun collapsedSubtreeIsExcludedFromLayout() {
        val collapsed = MindNode("a", "根", collapsed = true, children = listOf(MindNode("b", "子")))
        val result = MindLayout.compute(collapsed, cfg)
        assertEquals(listOf("a"), result.nodes.map { it.id })
        assertTrue(result.edges.isEmpty())
        assertEquals(1, result.nodes[0].hiddenCount)
    }

    @Test
    fun collapsedNodeReportsWholeSubtreeSize() {
        val inner = MindNode("c", "孙").insertChild("c", MindNode("d", "曾孙"))
        // b 折叠 → 角标应统计被藏起来的整棵子树（孙 + 曾孙 = 2），不是只算子节点
        val tree = MindNode(
            "a", "根",
            children = listOf(MindNode("b", "子", collapsed = true, children = listOf(inner))),
        )
        val result = MindLayout.compute(tree, cfg)
        assertEquals(0, result.nodes.first { it.id == "a" }.hiddenCount)
        assertEquals(2, result.nodes.first { it.id == "b" }.hiddenCount)
    }

    @Test
    fun deeperLevelsAreFurtherRight() {
        val result = MindLayout.compute(build(depth = 3, breadth = 2), cfg)
        val byDepth = result.nodes.groupBy { it.depth }.mapValues { it.value.first().x }
        assertTrue(byDepth[0]!! < byDepth[1]!!)
        assertTrue(byDepth[1]!! < byDepth[2]!!)
        assertTrue(byDepth[2]!! < byDepth[3]!!)
    }

    @Test
    fun sameDepthLeftEdgesAreAligned() {
        val result = MindLayout.compute(build(depth = 2, breadth = 4), cfg)
        result.nodes.groupBy { it.depth }.forEach { (_, column) ->
            val xs = column.map { it.x }.distinct()
            assertEquals("同层节点应左对齐成一列", 1, xs.size)
        }
    }

    @Test
    fun edgesMatchVisibleParentChildPairs() {
        val result = MindLayout.compute(build(depth = 2, breadth = 3), cfg)
        // 根 + 3 + 9 = 13 个节点；13 - 1 = 12 条边
        assertEquals(13, result.nodes.size)
        assertEquals(12, result.edges.size)
        val nodeIds = result.nodes.map { it.id }.toSet()
        result.edges.forEach {
            assertTrue(nodeIds.contains(it.parentId))
            assertTrue(nodeIds.contains(it.childId))
            assertTrue(it.toX >= it.fromX)
        }
    }

    @Test
    fun singleNodeUsesItsOwnSize() {
        val result = MindLayout.compute(MindNode("root", "只有我"), cfg)
        val node = result.nodes.single()
        assertEquals(node.width, result.width, 0.01f)
        assertEquals(node.height, result.height, 0.01f)
    }

    @Test
    fun layoutIsDeterministic() {
        val tree = build(depth = 3, breadth = 3)
        assertEquals(MindLayout.compute(tree, cfg), MindLayout.compute(tree, cfg))
    }

    @Test
    fun measureLabel_givesCjkMoreWidthAndWrapsLongText() {
        val cjk = MindLayout.measureLabel("中文四个字", cfg)
        val latin = MindLayout.measureLabel("abcd", cfg)
        assertTrue("中文应按全角计算", cjk.first > latin.first)
        val long = MindLayout.measureLabel("这是一段很长很长很长很长很长的节点标题需要折行", cfg)
        val short = MindLayout.measureLabel("短", cfg)
        assertTrue("超宽标签应折成多行", long.second > short.second)
    }

    @Test
    fun emptyLabelStillGetsUsableBox() {
        val (w, h) = MindLayout.measureLabel("", cfg)
        assertTrue(w >= cfg.fontSize)
        assertTrue(h >= cfg.lineHeight)
    }
}
