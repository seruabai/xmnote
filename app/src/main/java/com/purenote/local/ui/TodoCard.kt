package com.purenote.local.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteViewModel
import com.purenote.local.core.TodoDates
import com.purenote.local.data.Todo

/** 小米待办同款方角勾选框：未勾为描边方块，勾选后墨色填充 + 纸色对勾 */
@Composable
fun MiCheckbox(done: Boolean, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.onSurface else Color.Transparent,
        animationSpec = tween(150),
        label = "miCheckFill",
    )
    val stroke = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = fill,
        border = BorderStroke(1.6.dp, stroke),
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已完成",
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(size * 0.68f),
                )
            }
        }
    }
}

/**
 * 单条待办卡片（只读展示）：白色圆角行 + 方形勾选 + 时间行 + 清单展开。
 * 点击卡片开底部弹窗编辑；左滑露出圆形红删除键。
 * 长按进入多选模式：左侧变拖动手柄，右侧变选中圆圈，点卡片切换选中。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodoCardRow(
    vm: NoteViewModel,
    todo: Todo,
    subs: List<Todo>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    dragHandle: Modifier = Modifier,
) {
    val isListTodo = subs.isNotEmpty()
    val doneCount = subs.count { it.done }

    if (selectionMode) {
        // 多选模式不走左滑删除，直接整卡可点选，左侧拖动手柄支持上下排序。
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surface,
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggleSelect, onLongClick = onToggleSelect),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp),
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = "拖动排序",
                    tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = dragHandle.size(24.dp),
                )
                Text(
                    todo.title.ifBlank { "待办清单" },
                    style = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                if (isListTodo) {
                    Text(
                        "$doneCount/${subs.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        return
    }
    RevealDeleteRow(
        revealed = revealed,
        onRevealChange = onRevealChange,
        onDelete = { vm.deleteTodo(todo) },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (revealed) onRevealChange(false)
                                else vm.openTodoSheet(todo.id)
                            },
                            onLongClick = onLongPress,
                        )
                        .padding(horizontal = 19.dp, vertical = 23.dp),
                ) {
                    MiCheckbox(done = todo.done, size = 19.dp, onClick = { vm.toggleTodo(todo) })
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            todo.title.ifBlank { "待办清单" },
                            style = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 21.sp,
                                color = if (todo.done) MaterialTheme.colorScheme.outlineVariant
                                else MaterialTheme.colorScheme.onSurface,
                            ),
                            textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                            maxLines = if (isListTodo) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        if (todo.dueAt != null) {
                            DueTimeText(todo = todo)
                        }
                    }
                    if (isListTodo) {
                        val arrowRotation by animateFloatAsState(
                            targetValue = if (expanded) 0f else -90f,
                            animationSpec = tween(200),
                            label = "expandArrow",
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(onClick = onExpandToggle)
                                .padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                        ) {
                            Text(
                                "$doneCount/${subs.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (expanded) "收起清单" else "展开清单",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(18.dp)
                                    .rotate(arrowRotation),
                            )
                        }
                    }
                }
                if (isListTodo && expanded) {
                    subs.forEachIndexed { idx, sub ->
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 45.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (idx == 0) 0.45f else 0.25f),
                        )
                        SubListRow(
                            vm = vm,
                            sub = sub,
                            onEdit = { vm.openTodoSheet(todo.id, focusSubId = sub.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 非编辑态的子待办行：子项属于清单内容，删除直接移除不进废纸篓 */
@Composable
private fun SubListRow(vm: NoteViewModel, sub: Todo, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 45.dp, end = 10.dp),
    ) {
        MiCheckbox(done = sub.done, size = 15.dp, onClick = { vm.toggleTodo(sub) })
        Text(
            sub.title,
            style = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp),
            textDecoration = if (sub.done) TextDecoration.LineThrough else null,
            color = if (sub.done) MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 9.dp, vertical = 9.dp),
        )
        IconButton(onClick = { vm.deleteTodoForever(sub) }, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "删除子待办",
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** 时间行：时钟图标 + 小米格式文案；过期整行红、完成灰 */
@Composable
private fun DueTimeText(todo: Todo) {
    val expired = todo.isExpired()
    val color = when {
        todo.done -> MaterialTheme.colorScheme.outlineVariant
        expired -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            TodoDates.formatDue(todo.dueAt ?: 0L, todo.allDay, todo.repeat),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
