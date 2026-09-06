package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteViewModel
import com.purenote.local.data.Note

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(vm: NoteViewModel) {
    val notes by vm.notes.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<Note?>(null) }
    var confirmBatch by remember { mutableStateOf(false) }
    var batchAction by remember { mutableStateOf<BatchAction?>(null) }

    val selectedIds = remember { mutableStateListOf<Long>() }
    val selectionMode = selectedIds.isNotEmpty()

    fun exitSelection() { selectedIds.clear() }
    fun toggleSelectAll() {
        if (selectedIds.size == notes.size) selectedIds.clear()
        else { selectedIds.clear(); selectedIds.addAll(notes.map { it.id }) }
    }

    BackHandler {
        if (selectionMode) exitSelection() else vm.goHome()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = ::exitSelection) {
                            Icon(Icons.Outlined.ArrowBack, "取消选择")
                        }
                    },
                    title = { Text("已选 ${selectedIds.size}") },
                    actions = {
                        TextButton(onClick = ::toggleSelectAll) {
                            Text(if (selectedIds.size == notes.size) "取消全选" else "全选")
                        }
                    },
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = { vm.goHome() }) {
                            Icon(Icons.Outlined.ArrowBack, "返回")
                        }
                    },
                    title = { Text("废纸篓", style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(onClick = { confirmEmpty = true }, enabled = notes.isNotEmpty()) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                "清空",
                                tint = if (notes.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            TextButton(onClick = { batchAction = BatchAction.RESTORE; confirmBatch = true }) {
                                Text("恢复", color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(0.15f))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            TextButton(onClick = { batchAction = BatchAction.DELETE; confirmBatch = true }) {
                                Text("删除", color = MaterialTheme.colorScheme.onError, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
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
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("废纸篓是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "删除的笔记会在这里保留 30 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    val isSelected = note.id in selectedIds
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        if (isSelected) selectedIds.remove(note.id) else selectedIds.add(note.id)
                                        if (selectedIds.isEmpty()) exitSelection()
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectedIds.add(note.id)
                                    } else {
                                        if (isSelected) selectedIds.remove(note.id) else selectedIds.add(note.id)
                                        if (selectedIds.isEmpty()) exitSelection()
                                    }
                                },
                            ).padding(horizontal = 16.dp, vertical = 13.dp),
                        ) {
                            Text(
                                note.title.ifBlank { "(无标题)" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            if (note.title.isNotBlank() && note.body.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    note.body.replace('\n', ' '),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatNoteTime(note.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TextButton(onClick = { vm.restore(note) }) { Text("恢复") }
                                TextButton(onClick = { confirmOne = note }) {
                                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
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

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空废纸篓？") },
            text = { Text("所有被删除的笔记将被永久移除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.emptyTrash()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("取消") }
            },
        )
    }

    confirmOne?.let { note ->
        AlertDialog(
            onDismissRequest = { confirmOne = null },
            title = { Text("彻底删除？") },
            text = { Text("「${note.title.ifBlank { "无标题" }}」将被永久移除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOne = null
                    vm.deleteForever(note)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOne = null }) { Text("取消") }
            },
        )
    }

    if (confirmBatch) {
        val action = batchAction
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmBatch = false },
            title = { Text(if (action == BatchAction.RESTORE) "恢复 $count 条笔记？" else "彻底删除 $count 条笔记？") },
            text = {
                Text(
                    if (action == BatchAction.RESTORE) "选中的笔记将移回笔记列表。"
                    else "选中的笔记将被永久移除，无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatch = false
                    val ids = selectedIds.toList()
                    exitSelection()
                    if (action == BatchAction.RESTORE) {
                        ids.forEach { id -> notes.find { it.id == id }?.let { vm.restore(it) } }
                    } else {
                        ids.forEach { id -> notes.find { it.id == id }?.let { vm.deleteForever(it) } }
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

private enum class BatchAction { RESTORE, DELETE }
