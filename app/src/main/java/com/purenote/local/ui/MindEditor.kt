package com.purenote.local.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteTextSize
import com.purenote.local.feature.mind.MindDoc
import com.purenote.local.feature.mind.MindIds
import com.purenote.local.feature.mind.MindLayout
import com.purenote.local.feature.mind.MindLayoutConfig
import com.purenote.local.feature.mind.MindLayoutResult
import com.purenote.local.feature.mind.MindNode
import com.purenote.local.feature.mind.MindView
import com.purenote.local.feature.mind.find
import com.purenote.local.feature.mind.flattenOutline
import com.purenote.local.feature.mind.insertChild
import com.purenote.local.feature.mind.contains
import com.purenote.local.feature.mind.insertSibling
import com.purenote.local.feature.mind.move
import com.purenote.local.feature.mind.remove
import com.purenote.local.feature.mind.toggleCollapsed
import com.purenote.local.feature.mind.updateLabel
import kotlin.math.roundToInt

/** 节点文字行高倍数：MindLayout 的测量与画出来的文字共用同一个值 */
private const val NODE_LINE_HEIGHT = 1.4f

/** 节点气泡内边距：布局测量与文字绘制必须用同一个值，否则盒子会出现"字小框大"的空档 */
private val MindNodePadX = 12.dp
private val MindNodePadY = 8.dp

/**
 * 脑图编辑器（导图 + 大纲两种视图）。
 *
 * 树操作与布局全是纯函数（feature/mind，33 项单测），这里只做三件事：
 * 把布局结果摆到屏幕上、把点击翻译成某个节点的操作、把新文档交回编辑器。
 *
 * 节点不是画在 Canvas 上的——每个节点是一个带文字的 Composable。
 * 画在 Canvas 上更省事，但那样无障碍树里什么都没有：读屏软件念不出来，设备验收也测不到。
 * 只有连线画在 Canvas 上（它们没有语义价值）。
 */
@Composable
fun MindEditor(
    mind: MindDoc,
    textSize: NoteTextSize,
    onMindChange: (MindDoc) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val typeScale = textSize.typeScale()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }

    // 节点字号走 NoteTypeScale（与编辑器正文同一个来源），布局测量与文字绘制共用，
    // 盒子因此正好裹住文字：外圈留白全部落在 4dp 网格上（12/8/32/12）
    val labelSp = typeScale.editorBodySp
    val labelLineSp = labelSp * NODE_LINE_HEIGHT
    val config = remember(typeScale, density) {
        val fontPx = with(density) { typeScale.editorBodySp.sp.toPx() }
        MindLayoutConfig(
            fontSize = fontPx,
            lineHeight = fontPx * NODE_LINE_HEIGHT,
            paddingX = with(density) { MindNodePadX.toPx() },
            paddingY = with(density) { MindNodePadY.toPx() },
            levelGap = with(density) { 32.dp.toPx() },
            siblingGap = with(density) { 12.dp.toPx() },
            maxLabelWidth = with(density) { 150.dp.toPx() },
        )
    }
    val layout = remember(mind.root, config) { MindLayout.compute(mind.root, config) }
    val selected = selectedId?.let { mind.root.find(it) }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        ) {
            MindViewTab("导图", mind.view == MindView.MIND) {
                if (mind.view != MindView.MIND) onMindChange(mind.copy(view = MindView.MIND))
            }
            MindViewTab("大纲", mind.view == MindView.OUTLINE) {
                if (mind.view != MindView.OUTLINE) onMindChange(mind.copy(view = MindView.OUTLINE))
            }
            if (selected != null) {
                val label = selected.label.ifBlank { "（空节点）" }
                Text(
                    "已选中：" + label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mind.view) {
                MindView.MIND -> MindCanvas(
                    layout = layout,
                    root = mind.root,
                    labelSp = labelSp,
                    labelLineSp = labelLineSp,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onReparent = { nodeId, newParentId ->
                        onMindChange(mind.copy(root = mind.root.move(nodeId, newParentId)))
                        selectedId = nodeId
                    },
                )
                MindView.OUTLINE -> MindOutline(
                    mind = mind,
                    labelSp = labelSp,
                    labelLineSp = labelLineSp,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onToggleCollapse = { id -> onMindChange(mind.copy(root = mind.root.toggleCollapsed(id))) },
                )
            }
        }

        // 节点操作条：五格等宽（每格 ≥44dp 触控），间距落 4dp 网格
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        ) {
            val current = selected
            val rootSelected = current != null && current.id == mind.root.id
            MindAction(Icons.Outlined.Add, "加子级", enabled = current != null, modifier = Modifier.weight(1f)) {
                val node = current ?: return@MindAction
                val child = MindNode(id = MindIds.newNodeId())
                onMindChange(mind.copy(root = mind.root.insertChild(node.id, child)))
                selectedId = child.id
            }
            MindAction(
                Icons.Outlined.SubdirectoryArrowRight,
                "加同级",
                enabled = current != null && !rootSelected,
                modifier = Modifier.weight(1f),
            ) {
                val node = current ?: return@MindAction
                val sibling = MindNode(id = MindIds.newNodeId())
                onMindChange(mind.copy(root = mind.root.insertSibling(node.id, sibling)))
                selectedId = sibling.id
            }
            MindAction(Icons.Outlined.Edit, "改文字", enabled = current != null, modifier = Modifier.weight(1f)) {
                editing = current?.id
            }
            MindAction(
                Icons.Outlined.UnfoldMore,
                if (current?.collapsed == true) "展开" else "折叠",
                enabled = current != null && current.children.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                val node = current ?: return@MindAction
                onMindChange(mind.copy(root = mind.root.toggleCollapsed(node.id)))
            }
            MindAction(
                Icons.Outlined.DeleteOutline,
                "删除",
                enabled = current != null && !rootSelected,
                modifier = Modifier.weight(1f),
            ) {
                val node = current ?: return@MindAction
                onMindChange(mind.copy(root = mind.root.remove(node.id)))
                selectedId = null
            }
        }
    }

    val editingId = editing
    if (editingId != null) {
        val node = mind.root.find(editingId)
        MindLabelDialog(
            initial = node?.label.orEmpty(),
            onDismiss = { editing = null },
            onConfirm = { text ->
                onMindChange(mind.copy(root = mind.root.updateLabel(editingId, text)))
                editing = null
            },
        )
    }
}

/** 视图切换标签：文字居中放在 ≥44dp 的触控槽里（左右各留 10dp，槽宽自然 ≥44dp） */
@Composable
private fun MindViewTab(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 节点操作格：图标 + 文字整体是一个触控目标（槽位宽度由调用方 weight 给出，高度钉 48dp） */
@Composable
private fun MindAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
            maxLines = 1,
        )
    }
}

/** 导图视图：连线画在 Canvas 上，节点是真的 Composable（无障碍树里看得见、点得到） */
@Composable
private fun MindCanvas(
    layout: MindLayoutResult,
    root: MindNode,
    labelSp: Float,
    labelLineSp: Float,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onReparent: (String, String) -> Unit,
) {
    val density = LocalDensity.current
    val contentW = with(density) { layout.width.toDp() }
    val contentH = with(density) { layout.height.toDp() }
    val haptics = LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onSelect(null) } }
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.size(contentW, contentH)) {
                val edgeColor = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxSize()) {
                    layout.edges.forEach { e ->
                        val path = Path().apply {
                            moveTo(e.fromX, e.fromY)
                            // 三次贝塞尔：控制点取水平中点，与小米笔记的柔和曲线一致
                            cubicTo(
                                e.fromX + e.controlOffset, e.fromY,
                                e.toX - e.controlOffset, e.toY,
                                e.toX, e.toY,
                            )
                        }
                        drawPath(path, edgeColor, style = Stroke(width = 2f))
                    }
                }
                layout.nodes.forEach { n ->
                    val isSelected = n.id == selectedId
                    val isDropTarget = n.id == dropTargetId
                    val isDragging = n.id == draggingId
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(n.x.roundToInt(), n.y.roundToInt()) }
                            .size(with(density) { n.width.toDp() }, with(density) { n.height.toDp() })
                            // 长按拖动换父节点：长按前不碰事件，越过 slop 才接管（与正文块拖拽同一套做法）
                            .pointerInput(n.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val armed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                    val moving = root.find(n.id) ?: return@awaitEachGesture
                                    // 根节点没有父可换
                                    if (n.id == root.id) return@awaitEachGesture
                                    draggingId = n.id
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    var moved = false
                                    val slop = viewConfiguration.touchSlop
                                    val slopSq = slop * slop
                                    val start = armed.position
                                    while (true) {
                                        val change = awaitPointerEvent(PointerEventPass.Initial)
                                            .changes.firstOrNull { it.id == armed.id } ?: break
                                        if (change.changedToUpIgnoreConsumed()) break
                                        if (!moved) {
                                            val dx = change.position.x - start.x
                                            val dy = change.position.y - start.y
                                            if (dx * dx + dy * dy > slopSq) moved = true
                                        }
                                        if (moved) {
                                            change.consume()
                                            // 节点局部坐标 → 画布坐标；命中谁就以谁为新父
                                            val cx = n.x + change.position.x
                                            val cy = n.y + change.position.y
                                            dropTargetId = layout.nodes.firstOrNull { other ->
                                                other.id != n.id &&
                                                    !moving.contains(other.id) &&   // 自己的子孙不能当父（会成环）
                                                    cx >= other.x && cx <= other.right &&
                                                    cy >= other.y && cy <= other.bottom
                                            }?.id
                                        }
                                    }
                                    if (moved) {
                                        dropTargetId?.let { target -> onReparent(n.id, target) }
                                    }
                                    draggingId = null
                                    dropTargetId = null
                                }
                            }
                            .background(
                                when {
                                    isDropTarget -> MaterialTheme.colorScheme.primaryContainer
                                    isDragging -> MaterialTheme.colorScheme.surface
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                MaterialTheme.shapes.small,
                            )
                            .border(
                                width = if (isSelected || isDropTarget) 1.5.dp else 0.dp,
                                color = when {
                                    isDropTarget -> MaterialTheme.colorScheme.primary
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = MaterialTheme.shapes.small,
                            )
                            .clickable { onSelect(n.id) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = n.label.ifBlank { "（空）" },
                            fontSize = labelSp.sp,
                            lineHeight = labelLineSp.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = MindNodePadX),
                        )
                        if (n.collapsed && n.hiddenCount > 0) {
                            Text(
                                "+" + n.hiddenCount,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 大纲视图：缩进树；折叠的行不展开子行（与小米笔记的大纲视图同一套语义） */
@Composable
private fun MindOutline(
    mind: MindDoc,
    labelSp: Float,
    labelLineSp: Float,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onToggleCollapse: (String) -> Unit,
) {
    val rows = remember(mind.root) { mind.root.flattenOutline() }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (row.id == selectedId) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .clickable { onSelect(row.id) }
                    // 行高 48dp（列表行触控标准，也让 18dp 的折叠键自动扩到 48dp 触控）；
                    // 缩进每级 16dp、右缘 16dp，都落在 4dp 网格上
                    .heightIn(min = 48.dp)
                    .padding(start = (row.depth * 16).dp, end = 16.dp),
            ) {
                if (row.hasChildren) {
                    // 折叠键自己占 44dp 的槽位：18dp 图标交给 Compose 自动扩张触控框时会向左探出屏幕
                    //（审计里行左缘变成 1.1dp），文字也贴得紧（用户 2026-09-19 定：改）
                    Box(
                        modifier = Modifier.size(44.dp).clickable { onToggleCollapse(row.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (row.collapsed) Icons.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (row.collapsed) "展开" else "折叠",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(44.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    row.label.ifBlank { "（空）" },
                    fontSize = labelSp.sp,
                    lineHeight = labelLineSp.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MindLabelDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("节点文字") },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it.replace("\n", " ") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
