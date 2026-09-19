package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FactCheck
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
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
    var todoSelecting by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selectedIds.clear()
    }

    BackHandler(enabled = selecting) { exitSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!selecting && !todoSelecting) {
                // 按压缩放反馈：按下 0.9，松手 spring 回弹（Motion 令牌）
                val fabInteraction = remember { MutableInteractionSource() }
                val fabPressed by fabInteraction.collectIsPressedAsState()
                val fabScale by animateFloatAsState(
                    targetValue = if (fabPressed) 0.9f else 1f,
                    animationSpec = Motion.pressSpring(),
                    label = "fabPress",
                )
                Column(horizontalAlignment = Alignment.End) {
                    // 脑图入口：单独一颗小按钮而不是把主按钮改成菜单——主按钮"一下就是新笔记"
                    // 是既有肌肉记忆，不该被改成两步
                    if (tab == MainTab.NOTES) {
                        SmallFloatingActionButton(
                            onClick = { vm.openEditor(kind = NoteKind.MIND) },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            Icon(Icons.Outlined.AccountTree, "新建脑图", modifier = Modifier.size(22.dp))
                        }
                    }
                    FloatingActionButton(
                        onClick = {
                            if (tab == MainTab.NOTES) vm.openEditor(kind = NoteKind.TEXT)
                            else vm.openTodoSheet(-1L)
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                        interactionSource = fabInteraction,
                        modifier = Modifier
                            .size(56.dp)
                            .graphicsLayer {
                                scaleX = fabScale
                                scaleY = fabScale
                            },
                    ) {
                        Icon(Icons.Outlined.Add, "添加", modifier = Modifier.size(28.dp))
                    }
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
                    allSelected = notes.isNotEmpty() && selectedIds.size == notes.size,
                    onQuery = vm::setQuery,
                    onFolder = vm::selectFolder,
                    onUnclassified = vm::selectUnclassified,
                    onFolders = vm::goFolders,
                    onSettings = vm::goSettings,
                    onCreateFolder = vm::createFolder,
                    onExitSelection = ::exitSelection,
                    onSelectAll = {
                        if (selectedIds.size == notes.size) selectedIds.clear()
                        else {
                            selectedIds.clear()
                            selectedIds.addAll(notes.map { it.id })
                        }
                    },
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
                TodoPane(
                    vm,
                    modifier = Modifier.weight(1f),
                    onSelectionChange = { todoSelecting = it },
                    onSettings = vm::goSettings,
                )
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

/**
 * 笔记页头：动作行高度在两个状态下**钉死同一个值**（用户 2026-09-19："笔记目录下的长按进入的
 * 多选界面，布局应该与原本未长按情况下相同"）。改成"多选时把标题/搜索/分类都收起来"会让页头
 * 矮一大截，长按后整个列表往上跳 —— 待办页头（见本文件 TodoHeader）就是这么修的，这里同口径：
 * 行高固定 32dp、只换行内图标，标题行/搜索框/分类胶囊在两种状态下原样保留。
 */
@Composable
private fun NotesHeader(
    query: String,
    folders: List<Folder>,
    selectedFolderId: Long?,
    unclassifiedOnly: Boolean,
    selecting: Boolean,
    selectionCount: Int,
    allSelected: Boolean,
    onQuery: (String) -> Unit,
    onFolder: (Long?) -> Unit,
    onUnclassified: () -> Unit,
    onFolders: () -> Unit,
    onSettings: () -> Unit,
    onCreateFolder: (String, (Boolean) -> Unit) -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
) {
    var addFolderOpen by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var newFolderError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            // 32dp = 齿轮按钮的原尺寸：常态行高本来就是 32dp，钉死它，多选态换图标才不掉高度。
            // 视觉右缘 = 屏宽 - 16dp，与 16dp 内容栅格对齐（用户要求的内容左右 16dp）。
            modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 16.dp),
        ) {
            if (selecting) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "退出多选",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp).clickable(onClick = onExitSelection),
                )
                // 权重把两个图标推到行的两端：左退出、右全选，中间留白
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.FactCheck,
                    contentDescription = if (allSelected) "取消全选" else "全选",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp).clickable(onClick = onSelectAll),
                )
            } else {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = "分类",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(31.dp).clickable(onClick = onFolders),
                )
                Spacer(Modifier.width(24.dp))
                MiSettingsButton(onClick = onSettings)
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "笔记",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 34.sp,
                lineHeight = 41.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            )
            // 已选计数挂在标题右边（和待办页头同一个位置）：它不是新起一行，标题行高度不变
            if (selecting) {
                Text(
                    "已选 $selectionCount 项",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, bottom = 24.dp),
                )
            }
        }

        SearchPill(query = query, onQuery = onQuery)

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            // 胶囊间距 8dp（4dp 网格；原来 10dp 是页面上唯一一处非网格间距）
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        // 44dp：既是 4dp 网格上的值，也守住 44dp 最小触控标准（与"新建分类"胶囊同高）
        modifier = Modifier.height(44.dp).clickable(onClick = onClick),
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

/**
 * 待办页头（2026-09-17 按用户反馈改）：
 * - 长按进入多选后，"退出多选"的 X 放到**待办大字上方**那一行（原来挤在列表顶上）；
 * - 同一行的右上角由设置齿轮**换成全选**，退出多选再换回齿轮。
 */
@Composable
internal fun TodoHeader(
    onSettings: () -> Unit,
    selecting: Boolean = false,
    selectedCount: Int = 0,
    allSelected: Boolean = false,
    onExitSelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        // 这一行高度在两个状态下必须一模一样：进/出多选时页头一高一矮，下面整个列表就会
        // 上移/下落一下（用户 2026-09-17 反馈"长按之后整体界面会往上走"）。齿轮 32dp，
        // 退出/全选图标同样 32dp，行高钉死 32dp。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 16.dp),
        ) {
            if (selecting) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "退出多选",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp).clickable(onClick = onExitSelection),
                )
            }
            Spacer(Modifier.weight(1f))
            if (selecting) {
                Icon(
                    Icons.Outlined.FactCheck,
                    contentDescription = if (allSelected) "取消全选" else "全选",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp).clickable(onClick = onSelectAll),
                )
            } else {
                MiSettingsButton(onClick = onSettings)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "待办",
                fontSize = 34.sp,
                lineHeight = 41.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
            )
            if (selecting) {
                Text(
                    "已选 " + selectedCount + " 项",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchPill(query: String, onQuery: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 17.dp)) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(27.dp),
            )
            Spacer(Modifier.width(12.dp))
            // 字段与占位符共用同一份样式(显式行高,禁止继承主题行高导致首行错位)
            val searchStyle = TextStyle(
                fontSize = 17.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = searchStyle,
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "搜索笔记",
                                style = searchStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                                ),
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
        // 44dp：既是 4dp 网格上的值，也守住 44dp 最小触控标准（与"新建分类"胶囊同高）
        modifier = Modifier.height(44.dp).clickable(onClick = onClick),
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
private fun MiSettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val gearTint = MaterialTheme.colorScheme.onSurface
    // 自绘 Canvas 默认在无障碍树里没有身份：补上描述，读屏与设备验收都能找到它
    Canvas(
        modifier.size(32.dp)
            .semantics { contentDescription = "设置" }
            .clickable(onClick = onClick),
    ) {
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
        drawPath(path, gearTint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(
            color = gearTint,
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
    val folderNames = remember(folders) { folders.associate { it.id to it.name } }

    // 单页滚动：早先是左右各自一个 LazyColumn（瀑布流分列），两半各滚各的——
    // 用户 2026-09-17 明确要求"下滑一次就是整页一起动"，故换成一张网格。
    // 代价是同一行的两张卡片等高（瀑布流的高矮错落没了），换来的是滚动只有一个、不会错位。
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        // 间距取 12dp（4dp 网格）；卡片撑满所在行，同一行的两张卡上下缘对齐——
        // 不撑满时短卡会在行里留缺口，整片网格的下缘是锯齿状的（用户要求的大厂版面不允许）
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteCard(
                note = note,
                folderName = note.folderId?.let(folderNames::get),
                textSize = noteTextSize,
                selected = note.id in selectedIds,
                selecting = selecting,
                onClick = { if (selecting) onToggleSelected(note) else onOpen(note) },
                onLongPress = { onLongPress(note) },
                onToggleSelect = { onToggleSelected(note) },
                modifier = Modifier.animateItem(),
            )
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
    val folderNames = remember(folders) { folders.associate { it.id to it.name } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteCard(
                note = note,
                folderName = note.folderId?.let(folderNames::get),
                textSize = noteTextSize,
                selected = note.id in selectedIds,
                selecting = selecting,
                onClick = { if (selecting) onToggleSelected(note) else onOpen(note) },
                onLongPress = { onLongPress(note) },
                onToggleSelect = { onToggleSelected(note) },
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
    // iOS 标签栏风格：选中用系统蓝 tint，未选中用次级灰，无胶囊背景；选中图标 spring 弹跳
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = Motion.pressSpring(),
        label = "tabBounce",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
