package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.purenote.local.NoteViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: NoteViewModel) {
    val notes by vm.notes.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<com.purenote.local.data.Note?>(null) }

    BackHandler { vm.goHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { vm.goHome() }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                title = {
                    Text("废纸篓", style = MaterialTheme.typography.titleMedium)
                },
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
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
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
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
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
}
