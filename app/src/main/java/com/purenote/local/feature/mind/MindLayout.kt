package com.purenote.local.feature.mind

/** 布局参数。尺寸单位统一用 px，由 UI 侧换算 sp/dp 后传入，便于单元测试 */
data class MindLayoutConfig(
    val fontSize: Float = 16f,
    val lineHeight: Float = 22f,
    val paddingX: Float = 12f,
    val paddingY: Float = 8f,
    /** 层级之间的水平间距 */
    val levelGap: Float = 56f,
    /** 兄弟节点之间的垂直间距 */
    val siblingGap: Float = 14f,
    val maxLabelWidth: Float = 220f,
)

data class MindLayoutNode(
    val id: String,
    val label: String,
    val depth: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val collapsed: Boolean,
    /** 折叠时被藏起来的后代数量，用于画角标 */
    val hiddenCount: Int,
) {
    val centerY: Float get() = y + height / 2f
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/** 一条连线：父节点右中点 → 子节点左中点，UI 用三次贝塞尔画出来即可 */
data class MindLayoutEdge(
    val parentId: String,
    val childId: String,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
) {
    /** 控制点水平偏移，取水平距离一半，画出来是小米笔记那种柔和曲线 */
    val controlOffset: Float get() = (toX - fromX) / 2f
}

data class MindLayoutResult(
    val nodes: List<MindLayoutNode>,
    val edges: List<MindLayoutEdge>,
    val width: Float,
    val height: Float,
)

/**
 * 分层整齐树布局（layered tidy tree）。
 *
 * 为什么不用递归下标算坐标：节点宽度随文字长度变化，简单累加会列错位、节点重叠。
 * 这里分三趟 —— 量尺寸、自底向上算「带高」(band)、自顶向下摆放，保证三条不变量：
 * 1. 同一层左边缘对齐成列，列间距固定；
 * 2. 兄弟子树各自占一段互不重叠的纵向带（就是防重叠的根据）；
 * 3. 父节点盒在自身带内居中，因而正好落在子节点块的中线上。
 * 折叠的整支子树不参与任何一趟计算。
 */
object MindLayout {

    fun compute(root: MindNode, config: MindLayoutConfig = MindLayoutConfig()): MindLayoutResult {
        val sizes = HashMap<String, Pair<Float, Float>>()
        measureVisible(root, config, sizes)

        // 每一层取最大节点宽作为列宽，保证同层文字都不被挤压
        val columnWidth = HashMap<Int, Float>()
        collectColumnWidths(root, 0, sizes, columnWidth)

        val bandHeight = HashMap<String, Float>()
        computeBand(root, config, sizes, bandHeight)

        val columnX = HashMap<Int, Float>()
        var cursorX = 0f
        var depth = 0
        while (columnWidth.containsKey(depth)) {
            columnX[depth] = cursorX
            cursorX += (columnWidth[depth] ?: 0f) + config.levelGap
            depth++
        }

        val nodes = ArrayList<MindLayoutNode>()
        val edges = ArrayList<MindLayoutEdge>()
        place(root, 0, 0f, config, sizes, bandHeight, columnX, nodes, edges)

        val totalWidth = nodes.maxOfOrNull { it.right } ?: 0f
        val totalHeight = nodes.maxOfOrNull { it.bottom } ?: 0f
        return MindLayoutResult(nodes, edges, totalWidth, totalHeight)
    }

    /** 标签尺寸：中日韩字符按一个字宽，其余按 0.55 估算，超宽自动折行 */
    fun measureLabel(label: String, config: MindLayoutConfig): Pair<Float, Float> {
        var width = 0f
        var lines = 1
        var lineWidth = 0f
        val usable = (config.maxLabelWidth - config.paddingX * 2).coerceAtLeast(config.fontSize)
        for (ch in label) {
            val w = config.fontSize * (if (isFullWidth(ch)) 1f else 0.55f)
            if (lineWidth + w > usable) {
                lines++
                width = maxOf(width, lineWidth)
                lineWidth = w
            } else {
                lineWidth += w
            }
        }
        width = maxOf(width, lineWidth).coerceAtLeast(config.fontSize)
        return (width + config.paddingX * 2) to (config.lineHeight * lines + config.paddingY * 2)
    }

    private fun isFullWidth(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x115F || code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 || code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F || code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
    }

    private fun measureVisible(
        node: MindNode,
        config: MindLayoutConfig,
        out: MutableMap<String, Pair<Float, Float>>,
    ) {
        out[node.id] = measureLabel(node.label, config)
        node.visibleChildren().forEach { measureVisible(it, config, out) }
    }

    private fun collectColumnWidths(
        node: MindNode,
        depth: Int,
        sizes: Map<String, Pair<Float, Float>>,
        out: MutableMap<Int, Float>,
    ) {
        out[depth] = maxOf(out[depth] ?: 0f, sizes[node.id]?.first ?: 0f)
        node.visibleChildren().forEach { collectColumnWidths(it, depth + 1, sizes, out) }
    }

    private fun computeBand(
        node: MindNode,
        config: MindLayoutConfig,
        sizes: Map<String, Pair<Float, Float>>,
        out: MutableMap<String, Float>,
    ): Float {
        val own = sizes[node.id]?.second ?: 0f
        val kids = node.visibleChildren()
        val band = if (kids.isEmpty()) {
            own
        } else {
            var kidsBand = 0f
            kids.forEachIndexed { i, child ->
                kidsBand += computeBand(child, config, sizes, out)
                if (i < kids.size - 1) kidsBand += config.siblingGap
            }
            // 自身比子树带更高时必须按自身算，否则会和相邻子树重叠
            maxOf(own, kidsBand)
        }
        out[node.id] = band
        return band
    }

    private fun place(
        node: MindNode,
        depth: Int,
        bandTop: Float,
        config: MindLayoutConfig,
        sizes: Map<String, Pair<Float, Float>>,
        bandHeight: Map<String, Float>,
        columnX: Map<Int, Float>,
        nodes: MutableList<MindLayoutNode>,
        edges: MutableList<MindLayoutEdge>,
    ) {
        val (w, h) = sizes[node.id] ?: (0f to 0f)
        val band = bandHeight[node.id] ?: h
        val x = columnX[depth] ?: 0f
        val y = bandTop + (band - h) / 2f // 自身盒在带内居中 → 父节点落在子节点块中线上

        val kids = node.visibleChildren()
        val hidden = if (node.collapsed) node.children.sumOf { it.totalCount() } else 0
        nodes += MindLayoutNode(
            id = node.id,
            label = node.label,
            depth = depth,
            x = x,
            y = y,
            width = w,
            height = h,
            collapsed = node.collapsed,
            hiddenCount = hidden,
        )
        if (kids.isEmpty()) return

        var kidsBand = 0f
        kids.forEachIndexed { i, child ->
            kidsBand += bandHeight[child.id] ?: 0f
            if (i < kids.size - 1) kidsBand += config.siblingGap
        }

        var childTop = bandTop + (band - kidsBand) / 2f
        kids.forEachIndexed { i, child ->
            place(child, depth + 1, childTop, config, sizes, bandHeight, columnX, nodes, edges)
            val childH = sizes[child.id]?.second ?: 0f
            val childBand = bandHeight[child.id] ?: childH
            edges += MindLayoutEdge(
                parentId = node.id,
                childId = child.id,
                fromX = x + w,
                fromY = y + h / 2f,
                toX = columnX[depth + 1] ?: 0f,
                toY = childTop + (childBand - childH) / 2f + childH / 2f,
            )
            childTop += childBand
            if (i < kids.size - 1) childTop += config.siblingGap
        }
    }
}
