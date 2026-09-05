package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.MainTab
import com.purenote.local.NoteTextSize
import com.purenote.local.NoteViewModel
import com.purenote.local.data.Folder
import com.purenote.local.data.Note
import com.purenote.local.data.NoteKind

/** 小米笔记式主页：大标题、常驻搜索、宫格卡片、黄色 FAB 与底部双标签。 */
@Composable
fun HomeScreen(vm: NoteViewModel) {
    val notes by vm.notes.collectAsState()
    val folders by vm.folders.collectAsState()
    val filter by vm.filter.collectAsState()
    val tab by vm.tab.collectAsState()
    val gridMode by vm.gridMode.collectAsState()
    val noteTextSize by vm.noteTextSize.collectAsState()

    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var moveOpen by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selectedIds.clear()
    }

    BackHandler(enabled = selecting) { exitSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!selecting) {
                FloatingActionButton(
                    onClick = {
                        if (tab == MainTab.NOTES) vm.openEditor(kind = NoteKind.TEXT)
                        else vm.openNewTodo()
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp),
                    modifier = Modifier.size(58.dp),
                ) {
                    Icon(Icons.Outlined.Add, "添加", modifier = Modifier.size(35.dp))
                }
            }
        },
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    allSelected = notes.isNotEmpty() && selectedIds.size == notes.size,
                    enabled = selectedIds.isNotEmpty(),
                    onAll = {
                        if (selectedIds.size == notes.size) selectedIds.clear()
                        else {
                            selectedIds.clear()
                            selectedIds.addAll(notes.map { it.id })
                        }
                    },
                    onPin = {
                        val allPinned = notes.filter { it.id in selectedIds }.all { it.pinned }
                        vm.setPinnedBatch(selectedIds.toList(), !allPinned)
                        exitSelection()
                    },
                    onMove = { moveOpen = true },
                    onDelete = {
                        vm.trashNotes(selectedIds.toList())
                        exitSelection()
                    },
                )
            } else {
                MiBottomNavigation(selected = tab, onSelect = vm::switchTab)
            }
        },
    ) { scaffoldPadding ->
        Column(Modifier.padding(scaffoldPadding).fillMaxSize()) {
            if (tab == MainTab.NOTES) {
                NotesHeader(
                    query = filter.query,
                    folders = folders,
                    selectedFolderId = filter.folderId,
                    unclassifiedOnly = filter.unclassifiedOnly,
                    selecting = selecting,
                    selectionCount = selectedIds.size,
                    onQuery = vm::setQuery,
                    onFolder = vm::selectFolder,
                    onUnclassified = vm::selectUnclassified,
                    onFolders = vm::goFolders,
                    onSettings = vm::goSettings,
                    onCreateFolder = vm::createFolder,
                    onCloseSelection = ::exitSelection,
                )
                if (notes.isEmpty()) {
                    EmptyState("还没有笔记", "点击右下角 + 开始记录")
                } else {
                    val onLongPress: (Note) -> Unit = { note ->
                        if (!selecting) selecting = true
                        if (note.id !in selectedIds) selectedIds.add(note.id)
                    }
                    val onToggle: (Note) -> Unit = { note ->
                        if (note.id in selectedIds) selectedIds.remove(note.id)
                        else selectedIds.add(note.id)
                    }
                    if (gridMode) {
                        NotesMasonry(
                            notes = notes,
                            folders = folders,
                            noteTextSize = noteTextSize,
                            selectedIds = selectedIds,
                            selecting = selecting,
                            onOpen = vm::openEditor,
                            onLongPress = onLongPress,
                            onToggleSelected = onToggle,
                        )
                    } else {
                        NotesList(
                            notes = notes,
                            folders = folders,
                            noteTextSize = noteTextSize,
                            selectedIds = selectedIds,
                            selecting = selecting,
                            onOpen = vm::openEditor,
                            onLongPress = onLongPress,
                            onToggleSelected = onToggle,
                        )
                    }
                }
            } else {
                TodoHeader(onSettings = vm::goSettings)
                TodoPane(vm, modifier = Modifier.weight(1f))
            }
        }
    }

    if (moveOpen) {
        MoveFolderDialog(
            current = null,
            folders = folders,
            onDismiss = { moveOpen = false },
            onPick = { folder ->
                vm.moveToFolderBatch(selectedIds.toList(), folder)
                moveOpen = false
                exitSelection()
            },
        )
    }
}

@Composable
private fun NotesHeader(
    query: String,
    folders: List<Folder>,
    selectedFolderId: Long?,
    unclassifiedOnly: Boolean,
    selecting: Boolean,
    selectionCount: Int,
    onQuery: (String) -> Unit,
    onFolder: (Long?) -> Unit,
    onUnclassified: () -> Unit,
    onFolders: () -> Unit,
    onSettings: () -> Unit,
    onCreateFolder: (String, (Boolean) -> Unit) -> Unit,
    onCloseSelection: () -> Unit,
) {
    var addFolderOpen by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var newFolderError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        if (selecting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    "退出多选",
                    modifier = Modifier.size(28.dp).clickable(onClick = onCloseSelection),
                )
                Spacer(Modifier.width(20.dp))
                Text("已选 $selectionCount 项", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            }
            return@Column
        }

        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = "分类",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(31.dp).clickable(onClick = onFolders),
            )
            Spacer(Modifier.width(24.dp))
            MiSettingsButton(onClick = onSettings)
        }

        Text(
            "笔记",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 39.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(start = 27.dp, top = 25.dp, bottom = 22.dp),
        )

        SearchPill(query = query, onQuery = onQuery)

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        ) {
            item("all") {
                CategoryChip("全部", selectedFolderId == null && !unclassifiedOnly) { onFolder(null) }
            }
            items(folders, key = { it.id }) { folder ->
                CategoryChip(folder.name, selectedFolderId == folder.id) { onFolder(folder.id) }
            }
            if (folders.none { it.name == "未分类" }) {
                item("uncategorized") { CategoryChip("未分类", unclassifiedOnly, onUnclassified) }
            }
            item("add_folder") {
                AddCategoryChip(
                    onClick = {
                        newFolderName = ""
                        newFolderError = null
                        addFolderOpen = true
                    },
                )
            }
        }
    }

    if (addFolderOpen) {
        AlertDialog(
            onDismissRequest = { addFolderOpen = false },
            title = { Text("新建分类") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = {
                        newFolderName = it
                        newFolderError = null
                    },
                    placeholder = { Text("分类名称") },
                    singleLine = true,
                    isError = newFolderError != null,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.isNotBlank(),
                    onClick = {
                        onCreateFolder(newFolderName.trim()) { ok ->
                            if (ok) {
                                addFolderOpen = false
                            } else {
                                newFolderError = "名称重复或为空"
                            }
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { addFolderOpen = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AddCategoryChip(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(42.dp).clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "新建分类",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "新建分类",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TodoHeader(onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            MiSettingsButton(onClick = onSettings)
        }
        Text(
            "待办",
            fontSize = 39.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(start = 27.dp, top = 25.dp, bottom = 19.dp),
        )
    }
}

@Composable
private fun SearchPill(query: String, onQuery: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(52.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 17.dp)) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(27.dp),
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "搜索笔记",
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "清空搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable { onQuery("") },
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(42.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MiSettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(modifier.size(32.dp).clickable(onClick = onClick)) {
        val stroke = 2.1.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.42f
        val path = Path()
        repeat(6) { index ->
            val angle = Math.toRadians((index * 60.0) - 30.0)
            val point = Offset(
                cx + (radius * kotlin.math.cos(angle)).toFloat(),
                cy + (radius * kotlin.math.sin(angle)).toFloat(),
            )
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        drawPath(path, Color(0xFF222222), style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(
            color = Color(0xFF222222),
            radius = size.minDimension * 0.115f,
            center = Offset(cx, cy),
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun NotesMasonry(
    notes: List<Note>,
    folders: List<Folder>,
    noteTextSize: NoteTextSize,
    selectedIds: MutableList<Long>,
    selecting: Boolean,
    onOpen: (Note) -> Unit,
    onLongPress: (Note) -> Unit,
    onToggleSelected: (Note) -> Unit,
) {
    val left = mutableListOf<Note>()
    val right = mutableListOf<Note>()
    var leftHeight = 0
    var rightHeight = 0
    notes.forEach { note ->
        val estimate = 95 + (note.body.length / 16).coerceAtMost(5) * 18 + if (note.images.isNotEmpty()) 135 else 0
        if (leftHeight <= rightHeight) {
            left += note
            leftHeight += estimate
        } else {
            right += note
            rightHeight += estimate
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        listOf(left, right).forEach { columnNotes ->
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(columnNotes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        folderName = folders.firstOrNull { it.id == note.folderId }?.name,
                        textSize = noteTextSize,
                        selected = note.id in selectedIds,
                        onClick = { if (selecting) onToggleSelected(note) else onOpen(note) },
                        onLongPress = { onLongPress(note) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesList(
    notes: List<Note>,
    folders: List<Folder>,
    noteTextSize: NoteTextSize,
    selectedIds: MutableList<Long>,
    selecting: Boolean,
    onOpen: (Note) -> Unit,
    onLongPress: (Note) -> Unit,
    onToggleSelected: (Note) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteCard(
                note = note,
                folderName = folders.firstOrNull { it.id == note.folderId }?.name,
                textSize = noteTextSize,
                selected = note.id in selectedIds,
                onClick = { if (selecting) onToggleSelected(note) else onOpen(note) },
                onLongPress = { onLongPress(note) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
    ) {
        Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun MiBottomNavigation(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(modifier = Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp)) {
            BottomItem(
                label = "笔记",
                selected = selected == MainTab.NOTES,
                icon = Icons.Outlined.FormatListBulleted,
                modifier = Modifier.weight(1f),
            ) { onSelect(MainTab.NOTES) }
            BottomItem(
                label = "待办",
                selected = selected == MainTab.TODO,
                icon = Icons.Outlined.CheckBox,
                modifier = Modifier.weight(1f),
            ) { onSelect(MainTab.TODO) }
        }
    }
}

@Composable
private fun BottomItem(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = if (selected) Color.Black else Color.Transparent,
            modifier = Modifier.size(29.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) Color.White else Color(0xFF9B9B9B),
                    modifier = Modifier.size(23.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (selected) Color.Black else Color(0xFF9B9B9B),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun SelectionBar(
    allSelected: Boolean,
    enabled: Boolean,
    onAll: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(88.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            SelectionItem(
                icon = Icons.Rounded.DoneAll,
                label = if (allSelected) "取消全选" else "全选",
                modifier = Modifier.weight(1f),
                enabled = true,
                active = allSelected,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onAll,
            )
            SelectionItem(
                Icons.Rounded.PushPin,
                "置顶",
                Modifier.weight(1f),
                enabled,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onPin,
            )
            SelectionItem(
                Icons.Rounded.Folder,
                "分类",
                Modifier.weight(1f),
                enabled,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onMove,
            )
            SelectionItem(
                Icons.Rounded.DeleteOutline,
                "删除",
                Modifier.weight(1f),
                enabled,
                accent = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun SelectionItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    active: Boolean = false,
    accent: Color,
    onClick: () -> Unit,
) {
    val enabledAlpha = if (enabled) 1f else 0.32f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = accent.copy(alpha = if (active) 0.20f else 0.11f),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = accent.copy(alpha = enabledAlpha),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = enabledAlpha),
        )
    }
}
