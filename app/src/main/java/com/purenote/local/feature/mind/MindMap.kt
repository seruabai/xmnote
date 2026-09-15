package com.purenote.local.feature.mind

import kotlinx.serialization.Serializable

/**
 * 脑图（思维笔记）节点。
 *
 * 借鉴自小米笔记思维笔记的节点模型（id/parentId/label/collapsed/children），
 * 但改为不可变树 + 显式父指针隐含在 children 里：整套操作都是纯函数，
 * 便于单元测试，也便于编辑器做撤销栈（保留旧树即可）。
 */
@Serializable
data class MindNode(
    val id: String,
    val label: String = "",
    /** 折叠后子节点不参与布局，但数据仍在 */
    val collapsed: Boolean = false,
    val children: List<MindNode> = emptyList(),
)

/** 脑图页面的两种视图，对应用户看到「导图 / 大纲」 */
@Serializable
enum class MindView { MIND, OUTLINE }

/** 存进 notes.body 的文档结构；version 用于未来格式演进 */
@Serializable
data class MindDoc(
    val version: Int = MindCodec.CURRENT_VERSION,
    val root: MindNode = MindNode(id = ROOT_ID, label = ""),
    val view: MindView = MindView.MIND,
) {
    /** 根节点标题即笔记标题（项目里文本笔记取首行，脑图取根节点） */
    val displayTitle: String get() = root.label.trim().ifBlank { DEFAULT_TITLE }

    companion object {
        const val ROOT_ID = "root"
        const val DEFAULT_TITLE = "未命名脑图"
    }
}

/** 大纲视图的一行 */
data class OutlineRow(
    val id: String,
    val depth: Int,
    val label: String,
    val collapsed: Boolean,
    val hasChildren: Boolean,
)

/** 节点 id 生成。放在这里而不是操作函数内部，是为了让所有树操作保持纯函数、可断言 */
object MindIds {
    fun newNodeId(): String = java.util.UUID.randomUUID().toString()
}

// ---------------------------------------------------------------- 查询

fun MindNode.contains(nodeId: String): Boolean =
    this.id == nodeId || children.any { it.contains(nodeId) }

fun MindNode.find(nodeId: String): MindNode? = when {
    this.id == nodeId -> this
    else -> children.firstNotNullOfOrNull { it.find(nodeId) }
}

/** 参与布局的子节点：折叠时为空 */
fun MindNode.visibleChildren(): List<MindNode> = if (collapsed) emptyList() else children

/** 含自身在内的**可见**节点数，用于折叠角标与字数统计 */
fun MindNode.visibleCount(): Int = 1 + visibleChildren().sumOf { it.visibleCount() }

/** 全部节点数（不受折叠影响） */
fun MindNode.totalCount(): Int = 1 + children.sumOf { it.totalCount() }

/** 可见部分的最大层级（根为 0） */
fun MindNode.visibleDepth(): Int =
    1 + (visibleChildren().maxOfOrNull { it.visibleDepth() } ?: 0)

fun MindNode.charCount(): Int = label.length + children.sumOf { it.charCount() }

/** 某个节点的兄弟列表与自身下标，供插入/移动使用 */
private fun MindNode.siblingsOf(nodeId: String): Pair<List<MindNode>, Int>? {
    val idx = children.indexOfFirst { it.id == nodeId }
    return if (idx >= 0) children to idx else null
}

// ---------------------------------------------------------------- 纯函数操作

private fun MindNode.mapChildren(transform: (List<MindNode>) -> List<MindNode>): MindNode =
    copy(children = transform(children))

/** 通用递归替换：命中 [targetId] 时用 [transform] 处理该节点 */
private fun MindNode.transformNode(
    targetId: String,
    transform: (MindNode) -> MindNode,
): MindNode = when {
    id == targetId -> transform(this)
    children.isEmpty() -> this
    else -> mapChildren { kids -> kids.map { it.transformNode(targetId, transform) } }
}

/** 在 [parentId] 下追加子节点，[index] 为 -1 表示末尾。父节点不存在时原样返回 */
fun MindNode.insertChild(parentId: String, child: MindNode, index: Int = -1): MindNode =
    transformNode(parentId) { parent ->
        val kids = parent.children.toMutableList()
        val at = if (index < 0 || index > kids.size) kids.size else index
        kids.add(at, child)
        parent.copy(children = kids, collapsed = false)
    }

/** 在 [refId] 旁边插入同级节点（小米笔记的「插入同级节点」） */
fun MindNode.insertSibling(refId: String, sibling: MindNode, after: Boolean = true): MindNode {
    if (id == refId) return this // 根节点没有同级
    val hit = siblingsOf(refId)
    if (hit != null) {
        val (kids, idx) = hit
        val list = kids.toMutableList()
        list.add(if (after) idx + 1 else idx, sibling)
        return copy(children = list)
    }
    return mapChildren { kids -> kids.map { it.insertSibling(refId, sibling, after) } }
}

/**
 * 把 [refId] 包进一个新的父节点（小米笔记的「插入父级节点」）。
 * 新父节点继承原节点的位置，原节点成为其唯一子节点。
 */
fun MindNode.insertParent(refId: String, newParentId: String, newParentLabel: String = ""): MindNode {
    if (id == refId) return this
    val hit = siblingsOf(refId)
    if (hit != null) {
        val (kids, idx) = hit
        val list = kids.toMutableList()
        val old = list[idx]
        list[idx] = MindNode(
            id = newParentId,
            label = newParentLabel,
            children = listOf(old),
        )
        return copy(children = list)
    }
    return mapChildren { kids -> kids.map { it.insertParent(refId, newParentId, newParentLabel) } }
}

/** 删除节点（连同子树）。根节点不可删除，此时原样返回 */
fun MindNode.remove(nodeId: String): MindNode {
    if (id == nodeId) return this
    val hit = siblingsOf(nodeId)
    if (hit != null) {
        val (kids, idx) = hit
        return copy(children = kids.filterIndexed { i, _ -> i != idx })
    }
    return mapChildren { kids -> kids.map { it.remove(nodeId) } }
}

/**
 * 把 [nodeId] 移动到 [newParentId] 下的 [index] 位置（拖拽换父节点）。
 *
 * 三处防御：根节点不能移动；不能移动到自己的子孙下（会形成环）；目标不存在时不动。
 * 与小米笔记一致，移动后要展开新父节点，否则用户看不到结果。
 */
fun MindNode.move(nodeId: String, newParentId: String, index: Int = -1): MindNode {
    if (nodeId == id) return this
    if (nodeId == newParentId) return this
    val moving = find(nodeId) ?: return this
    if (moving.contains(newParentId)) return this // 目标在自己的子树里
    if (find(newParentId) == null) return this
    val detached = remove(nodeId)
    return detached.insertChild(newParentId, moving, index)
}

fun MindNode.updateLabel(nodeId: String, label: String): MindNode =
    transformNode(nodeId) { it.copy(label = label) }

fun MindNode.toggleCollapsed(nodeId: String): MindNode =
    transformNode(nodeId) { it.copy(collapsed = !it.collapsed) }

fun MindNode.setCollapsed(nodeId: String, collapsed: Boolean): MindNode =
    transformNode(nodeId) { it.copy(collapsed = collapsed) }

/** 导出大纲视图的行序（只含可见节点） */
fun MindNode.flattenOutline(depth: Int = 0): List<OutlineRow> {
    val self = OutlineRow(
        id = id,
        depth = depth,
        label = label,
        collapsed = collapsed,
        hasChildren = children.isNotEmpty(),
    )
    return listOf(self) + visibleChildren().flatMap { it.flattenOutline(depth + 1) }
}
