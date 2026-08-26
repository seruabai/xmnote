package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.purenote.local.NoteViewModel
import com.purenote.local.data.Folder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(vm: NoteViewModel) {
    val folders by vm.folders.collectAsState()
    val counts by vm.folderCounts.collectAsState()

    var addName by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteTarget by remember { mutableStateOf<Folder?>(null) }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

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
                    Text("管理分类", style = MaterialTheme.typography.titleMedium)
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.weight(1f)) {
                if (folders.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 56.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.size(80.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                            modifier = Modifier.size(30.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "还没有分类，添加一个吧",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            folders.forEachIndexed { idx, folder ->
                                if (idx > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 62.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            renameTarget = folder
                                            nameInput = folder.name
                                            errorText = null
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(38.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        folder.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${counts[folder.id] ?: 0}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    IconButton(onClick = {
                                        renameTarget = folder
                                        nameInput = folder.name
                                        errorText = null
                                    }) {
                                        Icon(
                                            Icons.Outlined.DriveFileRenameOutline,
                                            "重命名",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    IconButton(onClick = { deleteTarget = folder }, modifier = Modifier.width(40.dp)) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = addName,
                    onValueChange = { addName = it; errorText = null },
                    placeholder = { Text("新分类名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    isError = errorText != null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = CircleShape,
                    color = if (addName.isBlank()) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(enabled = addName.isNotBlank()) {
                            vm.createFolder(addName.trim()) { ok ->
                                if (!ok) errorText = "名称重复或为空"
                                else addName = ""
                            }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "添加分类",
                            tint = if (addName.isBlank()) MaterialTheme.colorScheme.outlineVariant
                            else MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名分类") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameFolder(target, nameInput) { ok -> if (ok) renameTarget = null }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分类「${target.name}」？") },
            text = { Text("分类下的笔记不会被删除，会移动到「无分类」。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deleteFolder(target)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
