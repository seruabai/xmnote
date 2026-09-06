package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.purenote.local.NoteViewModel
import com.purenote.local.core.TodoGrouper
import com.purenote.local.data.Todo
import kotlin.math.roundToInt

/** 待办主页：连续大卡片列表。同一时间最多一张卡片露出删除键，新建/编辑统一走底部弹窗。长按进入多选，左侧手柄拖动排序。 */
@Composable
fun TodoPane(vm: NoteViewModel, modifier: Modifier = Modifier, onSelectionChange: (Boolean) -> Unit = {}) {
    val todos by vm.todos.collectAsState()
    val sheetId by vm.todoSheetId.collectAsState()
    val expandedLists = remember { mutableStateMapOf<Long, Boolean>() }
    var revealedId by remember { mutableStateOf<Long?>(null) }

    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var draggingId by remember { mutableStateOf<Long?>(null) }

    fun exitSelection() {
        selecting = false
        selectedIds.clear()
        onSelectionChange(false)
    }

    // 离开组合（切 Tab）时必须上报退出多选，否则 HomeScreen 的 todoSelecting 卡在 true，FAB 永久消失
    val latestSelectionCallback by rememberUpdatedState(onSelectionChange)
    DisposableEffect(Unit) {
        onDispose { latestSelectionCallback(false) }
    }

    BackHandler(enabled = selecting) { exitSelection() }
    BackHandler(enabled = !selecting && revealedId != null) { revealedId = null }

    val rootTodos = remember(todos) {
        todos.filter { !it.isSubtask }.sortedWith(
            compareBy<Todo> { it.done }.thenBy { it.sortIndex }.thenByDescending { it.updatedAt },
        )
    }
    // 拖拽中的可视顺序：平时跟随 rootTodos，拖拽时本地交换，松手后持久化 sortIndex。
    val displayTodos = remember { mutableStateListOf<Todo>() }
    // 松手到 Flow 回流之间有个异步窗口，用 pendingOrder 顶住，防止列表先闪回旧序再跳新序
    var pendingOrder by remember { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(rootTodos, draggingId) {
        if (draggingId == null) {
            val pending = pendingOrder
            val ordered: List<Todo> = if (pending != null && pending != rootTodos.map { it.id }) {
                val byId = rootTodos.associateBy { it.id }
                val head = pending.mapNotNull { byId[it] }
                head + rootTodos.filter { it.id !in pending.toSet() }
            } else {
                if (pending != null) pendingOrder = null
                rootTodos
            }
            displayTodos.clear()
            displayTodos.addAll(ordered)
        } else {
            val ids = displayTodos.map { it.id }.toSet()
            rootTodos.filter { it.id !in ids }.forEach { displayTodos.add(it) }
            displayTodos.removeAll { gone -> rootTodos.none { it.id == gone.id } }
        }
    }

    fun persistOrder() {
        if (displayTodos.map { it.id } != rootTodos.map { it.id }) {
            pendingOrder = displayTodos.map { it.id }
            vm.reorderTodos(displayTodos.map { it.id })
        }
    }

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                IconButton(onClick = ::exitSelection) {
                    Icon(
                        Icons.Outlined.Close,
                        "退出多选",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "已选择${selectedIds.size}项",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    if (selectedIds.size == displayTodos.size) selectedIds.clear()
                    else {
                        selectedIds.clear()
                        selectedIds.addAll(displayTodos.map { it.id })
                    }
                }) {
                    Icon(
                        Icons.Outlined.DoneAll,
                        "全选",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (displayTodos.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "没有待办",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "点右下角新建一条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                val density = LocalDensity.current
                val stepPx = remember(density) { with(density) { 76.dp.toPx() } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp, end = 14.dp, top = 2.dp, bottom = 110.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(displayTodos, key = { it.id }) { todo ->
                        var dragOffset by remember(todo.id) { mutableFloatStateOf(0f) }
                        val dragging = draggingId == todo.id
                        Box(
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                                // 拖拽中的项关掉位移动画，避免与手动 dragOffset 叠加跳动
                                .animateItem(
                                    placementSpec = if (dragging) null else spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    ),
                                ),
                        ) {
                            TodoCardRow(
                                vm = vm,
                                todo = todo,
                                subs = TodoGrouper.subsOf(todo.id, todos),
                                expanded = expandedLists[todo.id] ?: false,
                                onExpandToggle = { expandedLists[todo.id] = !(expandedLists[todo.id] ?: false) },
                                revealed = revealedId == todo.id,
                                onRevealChange = { reveal ->
                                    revealedId = if (reveal) todo.id else revealedId?.takeIf { it != todo.id }
                                },
                                selectionMode = selecting,
                                selected = todo.id in selectedIds,
                                onLongPress = {
                                    if (!selecting) {
                                        selecting = true
                                        onSelectionChange(true)
                                    }
                                    if (todo.id !in selectedIds) selectedIds.add(todo.id)
                                },
                                onToggleSelect = {
                                    if (todo.id in selectedIds) selectedIds.remove(todo.id)
                                    else selectedIds.add(todo.id)
                                    if (selectedIds.isEmpty()) exitSelection()
                                },
                                dragHandle = if (selecting) {
                                    Modifier.pointerInput(todo.id) {
                                        detectVerticalDragGestures(
                                            onDragStart = { draggingId = todo.id },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffset = 0f
                                                persistOrder()
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                            onVerticalDrag = { _, dragAmount ->
                                                dragOffset += dragAmount
                                                val idx = displayTodos.indexOfFirst { it.id == todo.id }
                                                if (idx < 0) return@detectVerticalDragGestures
                                                if (dragOffset > stepPx / 2 && idx < displayTodos.lastIndex) {
                                                    displayTodos.add(idx + 1, displayTodos.removeAt(idx))
                                                    dragOffset -= stepPx
                                                } else if (dragOffset < -stepPx / 2 && idx > 0) {
                                                    displayTodos.add(idx - 1, displayTodos.removeAt(idx))
                                                    dragOffset += stepPx
                                                }
                                            },
                                        )
                                    }
                                } else Modifier,
                            )
                        }
                    }
                }
            }
        }
        if (selecting) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp, top = 4.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(enabled = selectedIds.isNotEmpty()) {
                        val targets = displayTodos.filter { it.id in selectedIds }
                        vm.deleteTodos(targets)
                        exitSelection()
                    }.padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "删除",
                        tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(30.dp),
                    )
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }

    sheetId?.let { TodoEditSheet(vm = vm, todoId = it, onClose = { vm.closeTodoSheet() }) }
}
