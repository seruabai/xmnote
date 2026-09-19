package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteViewModel
import com.purenote.local.data.Note
import com.purenote.local.data.Todo

/**
 * 废纸篓：笔记与待办分上下两区展示。
 * 笔记 id 与待办 id 来自不同表可能重复，所以选中态用两个集合分别记录。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(vm: NoteViewModel) {
    val notes by vm.notes.collectAsState()
    val todos by vm.trashedTodos.collectAsState()
    var confirmBatch by remember { mutableStateOf(false) }
    var batchAction by remember { mutableStateOf<BatchAction?>(null) }

    val selectedNoteIds = remember { mutableStateListOf<Long>() }
    val selectedTodoIds = remember { mutableStateListOf<Long>() }
    val selectedCount = selectedNoteIds.size + selectedTodoIds.size
    val selectionMode = selectedCount > 0
    val allSelected = selectedNoteIds.size == notes.size && selectedTodoIds.size == todos.size
    val isEmpty = notes.isEmpty() && todos.isEmpty()

    fun exitSelection() {
        selectedNoteIds.clear()
        selectedTodoIds.clear()
    }

    fun toggleSelectAll() {
        if (allSelected) {
            exitSelection()
        } else {
            selectedNoteIds.clear()
            selectedNoteIds.addAll(notes.map { it.id })
            selectedTodoIds.clear()
            selectedTodoIds.addAll(todos.map { it.id })
        }
    }

    BackHandler {
        if (selectionMode) exitSelection() else vm.goHome()
    }

    Scaffold(
        bottomBar = {
            if (selectionMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // 撑满 44dp 卡面：TextButton 默认 40dp 最小高，单看节点小于 44dp 触控标准
                            TextButton(
                                onClick = { batchAction = BatchAction.RESTORE; confirmBatch = true },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Text("恢复", color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
                            }
                        }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            TextButton(
                                onClick = { batchAction = BatchAction.DELETE; confirmBatch = true },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.onError, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            // 页头照设置页：返回字形落 16dp 正文线(48dp 触控区向左外溢 12dp)；多选态沿用同一行高，进/出不跳
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            ) {
                IconButton(
                    onClick = { if (selectionMode) exitSelection() else vm.goHome() },
                    modifier = Modifier.offset(x = (-12).dp),
                ) {
                    Icon(Icons.Outlined.ArrowBack, if (selectionMode) "取消选择" else "返回")
                }
                if (selectionMode) {
                    Text(
                        "已选 $selectedCount",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::toggleSelectAll, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                } else {
                    Text(
                        "废纸篓",
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(end = 48.dp),
                    )
                }
            }
            if (isEmpty) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("废纸篓是空的", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "删除的笔记和待办会在这里保留 30 天",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    // 横向 16dp 边距由外层列给，这里只留列表末尾留白
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (notes.isNotEmpty()) {
                        item(key = "header_notes") {
                            TrashSectionTitle("笔记（${notes.size}）")
                        }
                        items(notes, key = { "n${it.id}" }) { note ->
                            val isSelected = note.id in selectedNoteIds
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            if (selectionMode) {
                                                if (isSelected) selectedNoteIds.remove(note.id)
                                                else selectedNoteIds.add(note.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (isSelected) selectedNoteIds.remove(note.id)
                                            else selectedNoteIds.add(note.id)
                                        },
                                    ).padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    AnimatedVisibility(
                                        visible = selectionMode,
                                        enter = fadeIn() + expandHorizontally(),
                                        exit = fadeOut() + shrinkHorizontally(),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TrashCheck(selected = isSelected)
                                            Spacer(Modifier.width(12.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            note.title.ifBlank { "(无标题)" },
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            formatTrashTime(note.updatedAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (todos.isNotEmpty()) {
                        item(key = "header_todos") {
                            TrashSectionTitle("待办（${todos.size}）")
                        }
                        items(todos, key = { "t${it.id}" }) { todo ->
                            val isSelected = todo.id in selectedTodoIds
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            if (selectionMode) {
                                                if (isSelected) selectedTodoIds.remove(todo.id)
                                                else selectedTodoIds.add(todo.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (isSelected) selectedTodoIds.remove(todo.id)
                                            else selectedTodoIds.add(todo.id)
                                        },
                                    ).padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    AnimatedVisibility(
                                        visible = selectionMode,
                                        enter = fadeIn() + expandHorizontally(),
                                        exit = fadeOut() + shrinkHorizontally(),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TrashCheck(selected = isSelected)
                                            Spacer(Modifier.width(12.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            todo.title.ifBlank { "(无标题)" },
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            formatTrashTime(todo.trashedAt ?: todo.updatedAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "在废纸篓保留超过 30 天的内容会被自动清除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmBatch) {
        val action = batchAction
        val noteCount = selectedNoteIds.size
        val todoCount = selectedTodoIds.size
        val target = buildString {
            if (noteCount > 0) append("$noteCount 条笔记")
            if (todoCount > 0) {
                if (isNotEmpty()) append("、")
                append("$todoCount 条待办")
            }
        }
        AlertDialog(
            onDismissRequest = { confirmBatch = false },
            title = { Text(if (action == BatchAction.RESTORE) "恢复$target？" else "彻底删除$target？") },
            text = {
                Text(
                    if (action == BatchAction.RESTORE) "选中的内容将移回原来的位置。"
                    else "选中的内容将被永久移除，无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatch = false
                    val noteIds = selectedNoteIds.toList()
                    val todoIds = selectedTodoIds.toList()
                    exitSelection()
                    if (action == BatchAction.RESTORE) {
                        noteIds.forEach { id -> notes.find { it.id == id }?.let { vm.restore(it) } }
                        todoIds.forEach { id -> todos.find { it.id == id }?.let { vm.restoreTodo(it) } }
                    } else {
                        noteIds.forEach { id -> notes.find { it.id == id }?.let { vm.deleteForever(it) } }
                        todoIds.forEach { id -> todos.find { it.id == id }?.let { vm.deleteTodoForever(it) } }
                    }
                }) {
                    Text(
                        if (action == BatchAction.RESTORE) "恢复" else "删除",
                        color = if (action == BatchAction.RESTORE) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatch = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TrashSectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 15.sp,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** 选择模式下卡片左侧的勾选圆圈：选中为主色填充 + 白勾，未选中为空心描边。 */
@Composable
private fun TrashCheck(selected: Boolean) {
    val fill by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "trashCheckFill",
    )
    val stroke = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .border(1.5.dp, stroke, CircleShape)
            .background(fill, CircleShape),
    ) {
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private enum class BatchAction { RESTORE, DELETE }
