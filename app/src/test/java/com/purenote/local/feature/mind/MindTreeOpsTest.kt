package com.purenote.local.feature.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindTreeOpsTest {

    private fun leaf(id: String, label: String = id) = MindNode(id = id, label = label)

    /** 根 a，下挂 b、c；b 下挂 d */
    private fun sample() = MindNode(
        id = "a",
        label = "中心主题",
        children = listOf(
            MindNode(id = "b", label = "分支一", children = listOf(leaf("d"))),
            leaf("c", "分支二"),
        ),
    )

    @Test
    fun insertChild_appendsAtEndByDefault() {
        val tree = sample().insertChild("b", leaf("e"))
        assertEquals(listOf("d", "e"), tree.find("b")!!.children.map { it.id })
    }

    @Test
    fun insertChild_atIndexAndAutoExpandsParent() {
        val collapsed = sample().setCollapsed("b", true)
        val tree = collapsed.insertChild("b", leaf("e"), index = 0)
        assertFalse("插入后父节点应自动展开，否则用户看不到结果", tree.find("b")!!.collapsed)
        assertEquals(listOf("e", "d"), tree.find("b")!!.children.map { it.id })
    }

    @Test
    fun insertSibling_afterAndBefore() {
        assertEquals(
            listOf("b", "x", "c"),
            sample().insertSibling("b", leaf("x"), after = true).children.map { it.id },
        )
        assertEquals(
            listOf("x", "b", "c"),
            sample().insertSibling("b", leaf("x"), after = false).children.map { it.id },
        )
    }

    @Test
    fun insertSibling_onRoot_isNoOp() {
        // 根节点没有同级，返回原树且不能抛异常
        assertEquals(sample(), sample().insertSibling("a", leaf("x")))
    }

    @Test
    fun insertParent_wrapsNodeAndKeepsSubtree() {
        val tree = sample().insertParent("b", newParentId = "p", newParentLabel = "新父级")
        val wrapper = tree.find("p")
        assertNotNull(wrapper)
        assertEquals(listOf("b"), wrapper!!.children.map { it.id })
        // 原子树完整保留
        assertEquals(listOf("d"), tree.find("b")!!.children.map { it.id })
        // 位置不变：仍在 c 前面
        assertEquals(listOf("p", "c"), tree.children.map { it.id })
    }

    @Test
    fun remove_dropsSubtreeButNeverRoot() {
        assertEquals(listOf("c"), sample().remove("b").children.map { it.id })
        assertEquals(sample(), sample().remove("a"))
        // 删掉 c 后只剩 b，且 b 自己的子树不受影响
        val withoutC = sample().remove("c")
        assertEquals(listOf("b"), withoutC.children.map { it.id })
        assertEquals(listOf("d"), withoutC.find("b")!!.children.map { it.id })
    }

    @Test
    fun move_reparentsAndDetachesFromOldPlace() {
        val tree = sample().move("d", newParentId = "c")
        // c 原本没有子节点，d 搬过来后应恰好一个
        assertEquals(listOf("d"), tree.find("c")!!.children.map { it.id })
        assertTrue(tree.find("b")!!.children.isEmpty())
        // 顶层顺序不变，搬的是节点不是整棵子树
        assertEquals(listOf("b", "c"), tree.children.map { it.id })
    }

    @Test
    fun move_intoOwnDescendant_isRejected() {
        // 把 b 移到它的子节点 d 下会形成环，必须原样返回
        assertEquals(sample(), sample().move("b", newParentId = "d"))
    }

    @Test
    fun move_rootOrToItself_isRejected() {
        assertEquals(sample(), sample().move("a", newParentId = "c"))
        assertEquals(sample(), sample().move("b", newParentId = "b"))
    }

    @Test
    fun move_toMissingParent_isRejected() {
        assertEquals(sample(), sample().move("d", newParentId = "zzz"))
    }

    @Test
    fun operations_areImmutable() {
        val original = sample()
        original.insertChild("b", leaf("e"))
        original.remove("c")
        original.updateLabel("a", "改过")
        original.toggleCollapsed("b")
        assertEquals(sample(), original)
    }

    @Test
    fun collapseHidesDescendantsFromVisibleTraversal() {
        val tree = sample().toggleCollapsed("b")
        assertEquals(listOf("a", "b", "c"), tree.flattenOutline().map { it.id })
        assertEquals(4, tree.totalCount())
        assertEquals(3, tree.visibleCount())
    }

    @Test
    fun flattenOutline_carriesDepthAndFlags() {
        val rows = sample().flattenOutline()
        assertEquals(listOf(0, 1, 2, 1), rows.map { it.depth })
        assertEquals(listOf("a", "b", "d", "c"), rows.map { it.id })
        assertTrue(rows[1].hasChildren)
        assertFalse(rows[2].hasChildren)
    }

    @Test
    fun stats_matchTreeShape() {
        val tree = sample()
        assertEquals(4, tree.totalCount())
        assertEquals(3, tree.visibleDepth())
        assertEquals(4 + 3 + 3 + 1, tree.charCount())
    }

    @Test
    fun findAndContains_walkWholeTree() {
        val tree = sample()
        assertNotNull(tree.find("d"))
        assertNull(tree.find("nope"))
        assertTrue(tree.contains("d"))
        assertFalse(tree.contains("nope"))
    }
}
