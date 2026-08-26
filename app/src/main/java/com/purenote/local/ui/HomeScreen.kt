package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.purenote.local.MainTab
import com.purenote.local.NoteViewModel
import com.purenote.local.core.PreviewBuilder
import com.purenote.local.data.Note
import com.purenote.local.data.NoteKind
import com.purenote.local.data.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: NoteViewModel) {
    val notes by vm.notes.collectAsState()
    val folders by vm.folders.collectAsState()
    val trashCount by vm.trashCount.collectAsState()
    val filter by vm.filter.collectAsState()
    val searchActive by vm.searchActive.collectAsState()
    val gridMode by vm.gridMode.collectAsState()
    val tab by vm.tab.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()

    // 多选模式
    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var batchMoveOpen by remember { mutableStateOf(false) }
    var newFolderOpen by remember { mutableStateOf(false) }

    var switchMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var fabOpen by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selectedIds.clear()
    }

    BackHandler(enabled = selecting) { exitSelection() }
    BackHandler(enabled = searchActive) { vm.setQuery(""); vm.setSearchActive(false) }
    BackHandler(enabled = fabOpen) { fabOpen = false }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Outlined.Close, "退出多选")
                        }
                    } else if (searchActive) {
                        IconButton(onClick = { vm.setQuery(""); vm.setSearchActive(false) }) {
                            Icon(Icons.Outlined.ArrowBack, "取消搜索")
                        }
                    }
                },
                title = {
                    if (selecting) {
                        Text("已选 ${selectedIds.size} 项", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { switchMenuOpen = true },
                            ) {
                                Text(
                                    text = when {
                                        tab == MainTab.TODO -> "待办"
                                        filter.folderId != null ->
                                            folders.firstOrNull { it.id == filter.folderId }?.name ?: "全部笔记"
                                        else -> "笔记"
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Icon(
                                    Icons.Outlined.ArrowDropDown,
                                    contentDescription = "切换",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = switchMenuOpen,
                                onDismissRequest = { switchMenuOpen = false },
                            ) {
                                DropdownMenuItem(text = { Text("笔记") }, onClick = {
                                    switchMenuOpen = false
                                    vm.switchTab(MainTab.NOTES); vm.selectFolder(vm.filter.value.folderId)
                                })
                                DropdownMenuItem(text = { Text("待办") }, onClick = {
                                    switchMenuOpen = false
                                    vm.switchTab(MainTab.TODO)
                                })
                                DropdownMenuItem(text = { Text("废纸篓 ($trashCount)") }, onClick = {
                                    switchMenuOpen = false
                                    vm.goTrash()
                                })
                                DropdownMenuItem(text = { Text("设置") }, onClick = {
                                    switchMenuOpen = false
                                    vm.goSettings()
                                })
                            }
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = {
                            if (selectedIds.size == notes.size) selectedIds.clear()
                            else { selectedIds.clear(); selectedIds.addAll(notes.map { it.id }) }
                        }) { Text(if (selectedIds.size == notes.size && notes.isNotEmpty()) "取消全选" else "全选") }
                    } else {
                        if (tab == MainTab.NOTES && !searchActive) {
                            Box {
                                IconButton(onClick = { sortMenuOpen = true }) {
                                    Icon(Icons.Outlined.Sort, "排序")
                                }
                                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                    SortItem("按更新时间", sortOrder == SortOrder.BY_UPDATED) {
                                        sortMenuOpen = false; vm.setSortOrder(SortOrder.BY_UPDATED)
                                    }
                                    SortItem("按创建时间", sortOrder == SortOrder.BY_CREATED) {
                                        sortMenuOpen = false; vm.setSortOrder(SortOrder.BY_CREATED)
                                    }
                                }
                            }
                            IconButton(onClick = { vm.setSearchActive(true) }) {
                                Icon(Icons.Outlined.Search, "搜索")
                            }
                            IconButton(onClick = { vm.toggleGrid() }) {
                                Icon(
                                    if (gridMode) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                                    "切换布局",
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, "更多")
                            }
                            DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                                DropdownMenuItem(text = { Text(if (tab == MainTab.TODO) "切换到笔记" else "切换到待办") }, onClick = {
                                    moreMenuOpen = false
                                    if (tab == MainTab.TODO) vm.selectFolder(null) else vm.switchTab(MainTab.TODO)
                                })
                                DropdownMenuItem(text = { Text("设置") }, onClick = {
                                    moreMenuOpen = false; vm.goSettings()
                                })
                                DropdownMenuItem(text = { Text("废纸篓") }, onClick = {
                                    moreMenuOpen = false; vm.goTrash()
                                })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selecting) {
                if (tab == MainTab.NOTES) {
                    val rotation by animateFloatAsState(
                        targetValue = if (fabOpen) 45f else 0f,
                        label = "fabRotate",
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        AnimatedVisibility(
                            visible = fabOpen,
                            enter = fadeIn() + scaleIn(
                                initialScale = 0.6f,
                                transformOrigin = TransformOrigin(1f, 1f),
                            ),
                            exit = fadeOut() + scaleOut(
                                targetScale = 0.6f,
                                transformOrigin = TransformOrigin(1f, 1f),
                            ),
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                FabMenuItem("新建清单", Icons.Outlined.FormatListBulleted) {
                                    fabOpen = false
                                    vm.openEditor(kind = NoteKind.CHECKLIST)
                                }
                                Spacer(Modifier.height(12.dp))
                                FabMenuItem("新建笔记", Icons.Outlined.Edit) {
                                    fabOpen = false
                                    vm.openEditor(kind = NoteKind.TEXT)
                                }
                                Spacer(Modifier.height(14.dp))
                            }
                        }
                        FloatingActionButton(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                            onClick = {
                                if (!fabOpen) fabOpen = true else {
                                    fabOpen = false
                                    vm.openEditor(kind = NoteKind.TEXT)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = if (fabOpen) "关闭" else "新建",
                                modifier = Modifier.rotate(rotation),
                            )
                        }
                    }
                } else {
                    ExtendedFloatingActionButton(onClick = { vm.openNewTodo() }) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("新建待办")
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selecting,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        SelectAction(
                            icon = Icons.Outlined.SelectAll,
                            label = if (selectedIds.size == notes.size && notes.isNotEmpty()) "取消全选" else "全选",
                            modifier = Modifier.weight(1f),
                        ) {
                            if (selectedIds.size == notes.size) selectedIds.clear()
                            else { selectedIds.clear(); selectedIds.addAll(notes.map { it.id }) }
                        }
                        SelectAction(
                            icon = Icons.Outlined.PushPin,
                            label = "置顶",
                            modifier = Modifier.weight(1f),
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            val allPinned = notes.filter { it.id in selectedIds }.all { it.pinned }
                            vm.setPinnedBatch(selectedIds.toList(), !allPinned)
                            exitSelection()
                        }
                        SelectAction(
                            icon = Icons.Outlined.MoveToInbox,
                            label = "分类",
                            modifier = Modifier.weight(1f),
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            batchMoveOpen = selectedIds.isNotEmpty()
                        }
                        SelectAction(
                            icon = Icons.Outlined.DeleteOutline,
                            label = "删除",
                            modifier = Modifier.weight(1f),
                            enabled = selectedIds.isNotEmpty(),
                            tint = MaterialTheme.colorScheme.error,
                        ) {
                            vm.trashNotes(selectedIds.toList())
                            exitSelection()
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (tab == MainTab.NOTES && !selecting) {
                    FolderChipsRow(
                        folders = folders,
                        currentId = filter.folderId,
                        onSelect = { vm.selectFolder(it) },
                        onAddFolder = { newFolderOpen = true },
                        onManage = { vm.goFolders() },
                    )
                }

                Crossfade(targetState = tab, label = "homeTab") { current ->
                    if (current == MainTab.TODO) {
                        TodoPane(vm)
                    } else {
                        NotesBody(
                            vm = vm,
                            notes = notes,
                            folders = folders,
                            searchActive = searchActive,
                            gridMode = gridMode,
                            query = filter.query,
                            selecting = selecting,
                            selectedIds = selectedIds,
                            onStartSelect = { id ->
                                selecting = true
                                selectedIds.add(id)
                            },
                        )
                    }
                }
            }

            if (fabOpen) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { fabOpen = false },
                )
            }
        }
    }

    if (batchMoveOpen) {
        MoveFolderDialog(
            current = null,
            folders = folders,
            onDismiss = { batchMoveOpen = false },
            onPick = { target ->
                vm.moveToFolderBatch(selectedIds.toList(), target)
                batchMoveOpen = false
                exitSelection()
            },
        )
    }

    if (newFolderOpen) {
        NewFolderDialog(
            onCreate = { name ->
                vm.createFolder(name) { ok -> if (ok) newFolderOpen = false }
            },
            onDismiss = { newFolderOpen = false },
        )
    }
}

@Composable
private fun SortItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (checked) "✓ $label" else label) },
        onClick = onClick,
    )
}

/** FAB 展开项：文字标签 + 圆形小按钮 */
@Composable
private fun FabMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** 分类横滑行（小米笔记首页样式，选中为黄底） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderChipsRow(
    folders: List<com.purenote.local.data.Folder>,
    currentId: Long?,
    onSelect: (Long?) -> Unit,
    onAddFolder: () -> Unit,
    onManage: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        item(key = "all") {
            MiChip(selected = currentId == null, label = "全部笔记", onClick = { onSelect(null) })
        }
        items(folders, key = { it.id }) { folder ->
            MiChip(selected = currentId == folder.id, label = folder.name, onClick = { onSelect(folder.id) })
        }
        item(key = "add") {
            MiChip(selected = false, label = "＋ 分类", onClick = onAddFolder)
        }
        item(key = "manage") {
            MiChip(selected = false, label = "管理", onClick = onManage)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(50),
        label = { Text(label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
private fun NotesBody(
    vm: NoteViewModel,
    notes: List<Note>,
    folders: List<com.purenote.local.data.Folder>,
    searchActive: Boolean,
    gridMode: Boolean,
    query: String,
    selecting: Boolean,
    selectedIds: MutableList<Long>,
    onStartSelect: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = searchActive,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val focusRequester = remember { FocusRequester() }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                placeholder = { Text("搜索标题与内容") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .focusRequester(focusRequester),
            )
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(120)
                runCatching { focusRequester.requestFocus() }
            }
        }

        if (notes.isEmpty()) {
            EmptyNotes(searchActive = searchActive)
        } else {
            NotesListArea(
                vm = vm, notes = notes, folders = folders,
                searchActive = searchActive, gridMode = gridMode,
                selecting = selecting, selectedIds = selectedIds, onStartSelect = onStartSelect,
            )
        }
    }
}

@Composable
private fun EmptyNotes(searchActive: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 120.dp),
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
                    if (searchActive) Icons.Outlined.ManageSearch else Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (searchActive) "没有匹配的笔记" else "还没有笔记",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!searchActive) {
            Spacer(Modifier.height(4.dp))
            Text(
                "点右下角按钮开始记录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** 粗略估算卡片高度，用于把新卡片放进较矮的一列 */
private fun estimateCardHeight(note: Note): Int {
    var h = 78
    if (note.images.isNotEmpty()) h += 150
    h += when (note.kind) {
        NoteKind.TEXT -> {
            val rest = PreviewBuilder.splitTitle(note.body).second
            ((rest.length / 15).coerceAtMost(6)) * 19
        }
        NoteKind.CHECKLIST -> {
            val pending = note.items.count { !it.done }.coerceAtMost(4)
            pending * 21 + 22
        }
    }
    return h
}

@Composable
private fun NotesListArea(
    vm: NoteViewModel,
    notes: List<Note>,
    folders: List<com.purenote.local.data.Folder>,
    searchActive: Boolean,
    gridMode: Boolean,
    selecting: Boolean,
    selectedIds: MutableList<Long>,
    onStartSelect: (Long) -> Unit,
) {
    fun openOrToggle(note: Note) {
        if (selecting) {
            if (note.id in selectedIds) selectedIds.remove(note.id) else selectedIds.add(note.id)
        } else {
            vm.openEditor(note)
        }
    }

    fun longPress(note: Note) {
        if (!selecting) onStartSelect(note.id) else openOrToggle(note)
    }

    if (gridMode && !searchActive) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            val left = mutableListOf<Note>()
            val right = mutableListOf<Note>()
            var lh = 0
            var rh = 0
            notes.forEach { n ->
                val eh = estimateCardHeight(n)
                if (lh <= rh) { left += n; lh += eh } else { right += n; rh += eh }
            }
            listOf(left, right).forEach { columnNotes ->
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 130.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(columnNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            folderName = folders.firstOrNull { it.id == note.folderId }?.name,
                            onClick = { openOrToggle(note) },
                            onLongPress = { longPress(note) },
                            selected = note.id in selectedIds,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 130.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val pinnedCount = notes.count { it.pinned }
            itemsIndexed(notes, key = { _, n -> n.id }) { index, note ->
                if (index == 0 && pinnedCount > 0) {
                    SectionLabel("置顶", MaterialTheme.colorScheme.secondary)
                }
                if (index == pinnedCount && pinnedCount in 1 until notes.size) {
                    SectionLabel("其他笔记", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NoteCard(
                    note = note,
                    folderName = folders.firstOrNull { it.id == note.folderId }?.name,
                    onClick = { openOrToggle(note) },
                    onLongPress = { longPress(note) },
                    selected = note.id in selectedIds,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 2.dp),
    ) {
        Icon(
            Icons.Outlined.PushPin,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分类") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 多选底栏的操作项：图标 + 文字 */
@Composable
private fun SelectAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) tint else tint.copy(alpha = 0.38f),
        )
    }
}
