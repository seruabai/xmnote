package com.purenote.local.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteViewModel
import com.purenote.local.core.TodoDates
import com.purenote.local.core.TodoGroup
import com.purenote.local.core.TodoGrouper
import com.purenote.local.data.RepeatRule
import com.purenote.local.data.Todo
import java.util.Calendar

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

private data class SubDraft(val text: String, val done: Boolean)

/** 待办列表（嵌入首页主体，小米六分组：已过期/今天/明天/更远/未计划/已完成） */
@Composable
fun TodoPane(vm: NoteViewModel, modifier: Modifier = Modifier) {
    val todos by vm.todos.collectAsState()
    val groups = remember(todos) { TodoGrouper.group(todos) }
    val collapsed = remember { mutableStateMapOf<TodoGroup, Boolean>() }
    val expandedLists = remember { mutableStateMapOf<Long, Boolean>() }

    if (todos.isEmpty()) {
        Column(
            modifier.fillMaxSize(),
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
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 140.dp,
        ),
    ) {
        groups.forEach { (group, list) ->
            if (list.isEmpty()) return@forEach
            val isCollapsed = collapsed[group] == true
            item(key = "header_$group") {
                GroupHeader(
                    group = group,
                    count = list.size,
                    collapsed = isCollapsed,
                    onToggle = { collapsed[group] = !isCollapsed },
                    extra = if (group == TodoGroup.DONE && !isCollapsed) ({ ClearDoneButton(vm) }) else null,
                )
            }
            if (!isCollapsed) {
                itemsIndexed(list, key = { _, t -> t.id }) { _, todo ->
                    TodoCardRow(
                        vm = vm,
                        todo = todo,
                        subs = TodoGrouper.subsOf(todo.id, todos),
                        expanded = expandedLists[todo.id] ?: true,
                        onExpandToggle = { expandedLists[todo.id] = !(expandedLists[todo.id] ?: true) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearDoneButton(vm: NoteViewModel) {
    Text(
        "清除",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .clickable { vm.clearDone() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun GroupHeader(
    group: TodoGroup,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = tween(200),
        label = "headerArrow",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 13.dp, top = 16.dp, bottom = 7.dp),
    ) {
        Text(
            TodoDates.groupTitle(group),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (group == TodoGroup.EXPIRED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开" else "收起",
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier
                .padding(start = 3.dp)
                .size(15.dp)
                .rotate(rotation),
        )
        Spacer(Modifier.weight(1f))
        extra?.invoke()
    }
}

/** 单条待办卡片：白色圆角行 + 方形勾选 + 时间行；左右滑动 = 完成/删除（小米手势） */
@Composable
private fun TodoCardRow(
    vm: NoteViewModel,
    todo: Todo,
    subs: List<Todo>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isListTodo = subs.isNotEmpty()
    val doneCount = subs.count { it.done }

    SwipeTodoRow(
        todo = todo,
        onFinishToggle = { vm.toggleTodo(todo) },
        onDelete = { vm.deleteTodo(todo) },
        modifier = modifier.padding(top = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isListTodo, onClick = onExpandToggle)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
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
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$doneCount/${subs.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (expanded) 0f else -90f,
                        animationSpec = tween(200),
                        label = "expandArrow",
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

            if (isListTodo && expanded) {
                subs.forEachIndexed { idx, sub ->
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 45.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (idx == 0) 0.45f else 0.25f),
                    )
                    SubListRow(vm = vm, sub = sub)
                }
            }
        }
    }
}

@Composable
private fun SubListRow(vm: NoteViewModel, sub: Todo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.openTodoEditor(sub.parentId ?: sub.id) }
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
        IconButton(onClick = { vm.deleteTodo(sub) }, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "删除子待办",
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** 左滑红色删除，右滑蓝色完成/灰色撤销——小米待办签名手势 */
@Composable
private fun SwipeTodoRow(
    todo: Todo,
    onFinishToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onFinishToggle()
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                else -> false
            }
        },
    )
    // 完成切换后行仍留在列表中，用 key 重置滑动状态使其回弹
    key(todo.id, todo.done) {
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                val deleting = state.targetValue == SwipeToDismissBoxValue.EndToStart ||
                    state.dismissDirection == SwipeToDismissBoxValue.EndToStart
                val bg = if (deleting) Color(0xFFFA4039)
                else if (todo.done) Color(0xFFCDCDCD) else Color(0xFF2C94DE)
                val label = if (deleting) "删除" else if (todo.done) "未完成" else "完成"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (deleting) Arrangement.End else Arrangement.Start,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bg, RoundedCornerShape(14.dp))
                        .padding(horizontal = 22.dp),
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
            modifier = modifier,
            content = {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    content()
                }
            },
        )
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

/**
 * 待办编辑页：小米式底部弹层。
 * 主输入框按回车即转为「待办清单」并连续添加子待办；
 * 底部提供 今天/明天 快捷胶囊、设置提醒与黄色「完成」按钮。
 */
@Composable
fun TodoEditScreen(vm: NoteViewModel, todoId: Long) {
    var loaded by remember { mutableStateOf(todoId <= 0) }
    var id by remember { mutableStateOf(todoId) }
    var creating by remember { mutableStateOf(false) }
    var isNewSaved by remember { mutableStateOf(false) }

    var title by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    val subs = remember { mutableStateListOf<SubDraft>() }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var allDay by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(RepeatRule.NONE) }
    var listMode by remember { mutableStateOf(false) }

    var remindOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingFocusIndex by remember { mutableStateOf(-1) }
    val subFocus = remember { mutableMapOf<Int, FocusRequester>() }

    // 新增子行后请求末行焦点
    LaunchedEffect(pendingFocusIndex) {
        if (pendingFocusIndex >= 0) {
            kotlinx.coroutines.delay(80)
            runCatching { subFocus[pendingFocusIndex]?.requestFocus() }
            pendingFocusIndex = -1
        }
    }

    LaunchedEffect(Unit) {
        if (todoId > 0) {
            vm.getTodoOnce(todoId) { todo ->
                if (todo == null) {
                    vm.goHome()
                } else {
                    title = todo.title
                    done = todo.done
                    dueAt = todo.dueAt
                    allDay = todo.allDay
                    repeat = todo.repeat
                    val children = vm.todos.value.filter { it.parentId == todo.id }
                    if (children.isNotEmpty()) {
                        listMode = true
                        children.forEach { subs.add(SubDraft(it.title, it.done)) }
                    }
                }
                loaded = true
            }
        }
    }

    fun persist(afterSave: (Long) -> Unit = {}) {
        if (!loaded) return
        val cleanSubs = subs.filter { it.text.isNotBlank() }
        val effectiveList = listMode && cleanSubs.isNotEmpty()
        val effectiveTitle = when {
            !listMode -> title.trim()
            title.isNotBlank() -> title.trim()
            else -> ""
        }
        if (effectiveTitle.isBlank() && cleanSubs.isEmpty()) return
        val finalTitle = effectiveTitle.ifBlank { "待办清单" }
        if (id > 0) {
            vm.updateTodo(id, finalTitle, dueAt, allDay, repeat)
            if (effectiveList) vm.saveTodoSubs(id, cleanSubs.map { it.text to it.done })
            afterSave(id)
        } else if (!creating) {
            creating = true
            vm.createTodo(
                title = finalTitle,
                dueAt = dueAt,
                allDay = allDay,
                repeat = repeat,
                subs = if (effectiveList) cleanSubs.map { it.text to it.done } else emptyList(),
            ) { newId ->
                id = newId
                creating = false
                isNewSaved = true
                afterSave(newId)
            }
        }
    }

    fun closeSheet() {
        persist()
        vm.goHome()
    }

    BackHandler { closeSheet() }

    fun onTitleInput(input: String) {
        if (input.contains('\n')) {
            // 小米交互：主输入框回车 → 整段转为待办清单的子待办
            val lines = input.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (!listMode) listMode = true
            lines.forEach { subs.add(SubDraft(it, false)) }
            title = ""
            if (subs.isNotEmpty()) pendingFocusIndex = subs.size - 1
        } else {
            title = input
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { closeSheet() },
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* 吞掉点击，避免穿透到遮罩 */ },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Box(
                    Modifier
                        .padding(top = 8.dp, bottom = 2.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                )

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    if (id > 0 || isNewSaved) {
                        MiCheckbox(
                            done = done,
                            size = 20.dp,
                            onClick = {
                                val direction = !done
                                persist {
                                    vm.toggleTodo(
                                        Todo(
                                            id = id, parentId = null, title = title, done = !direction,
                                            doneAt = null, dueAt = dueAt, allDay = allDay, repeat = repeat,
                                            sortIndex = 0, createdAt = 0L, updatedAt = 0L,
                                        ),
                                    )
                                }
                                done = direction
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = ::onTitleInput,
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box {
                                if (title.isEmpty()) {
                                    Text(
                                        if (listMode) "待办清单" else "请输入待办事项…",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f),
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    if (id > 0) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (listMode) {
                    Text(
                        "回车即可连续添加待办",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                LazyColumn(Modifier.weight(1f)) {
                    if (listMode) {
                        itemsIndexed(subs) { index, sub ->
                            val focusReq = subFocus.getOrPut(index) { FocusRequester() }
                            SubEditRow(
                                draft = sub,
                                focusRequester = focusReq,
                                onTextChange = { raw ->
                                    if (raw.contains('\n')) {
                                        val parts = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                                        if (parts.isEmpty()) {
                                            subs[index] = sub.copy(text = "")
                                        } else {
                                            subs[index] = sub.copy(text = parts.first())
                                            parts.drop(1).forEachIndexed { i, extra ->
                                                subs.add(index + 1 + i, SubDraft(extra, false))
                                            }
                                            pendingFocusIndex = subs.size - 1
                                        }
                                    } else {
                                        subs[index] = sub.copy(text = raw)
                                    }
                                },
                                onToggle = { subs[index] = sub.copy(done = !sub.done) },
                                onRemove = {
                                    subs.removeAt(index)
                                },
                            )
                        }
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        subs.add(SubDraft("", false))
                                        pendingFocusIndex = subs.size - 1
                                    }
                                    .padding(vertical = 11.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "添加子待办",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }

                // 底部标签栏：今天/明天快捷胶囊 + 设置提醒 + 完成
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(top = 6.dp, bottom = 10.dp),
                ) {
                    val todayStart = TodoDates.startOfDay(System.currentTimeMillis())
                    val tomorrowStart = TodoDates.startOfDay(System.currentTimeMillis(), 1)
                    QuickDayChip(
                        label = "今天",
                        selected = dueAt != null && TodoDates.startOfDay(dueAt!!) == todayStart,
                    ) {
                        dueAt =
                            if (dueAt != null && TodoDates.startOfDay(dueAt!!) == todayStart) null
                            else TodoDates.dayRemindFromToday(0)
                        allDay = false
                    }
                    Spacer(Modifier.width(8.dp))
                    QuickDayChip(
                        label = "明天",
                        selected = dueAt != null && TodoDates.startOfDay(dueAt!!) == tomorrowStart,
                    ) {
                        dueAt =
                            if (dueAt != null && TodoDates.startOfDay(dueAt!!) == tomorrowStart) null
                            else TodoDates.dayRemindFromToday(1)
                        allDay = false
                    }
                    Spacer(Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { remindOpen = true }
                                .padding(start = 11.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = if (dueAt != null) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                dueAt?.let { TodoDates.formatDue(it, allDay, repeat) } ?: "设置提醒",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (dueAt != null) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            if (dueAt != null) {
                                IconButton(onClick = {
                                    dueAt = null
                                    repeat = RepeatRule.NONE
                                }, modifier = Modifier.size(26.dp)) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "取消提醒时间",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(11.dp))
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    val hasContent = title.isNotBlank() || subs.any { it.text.isNotBlank() }
                    Text(
                        "完成",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (hasContent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        modifier = Modifier
                            .clickable(enabled = hasContent) { closeSheet() }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    if (remindOpen) {
        RemindPickerDialog(
            initialDue = dueAt,
            initialAllDay = allDay,
            initialRepeat = repeat,
            onApply = { d, ad, rp ->
                dueAt = d
                allDay = ad
                repeat = rp
                remindOpen = false
            },
            onClear = {
                dueAt = null
                allDay = false
                repeat = RepeatRule.NONE
                remindOpen = false
            },
            onDismiss = { remindOpen = false },
        )
    }

    if (confirmDelete && id > 0) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条待办？") },
            text = { Text("将同时删除它的全部子待办。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteTodoById(id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun QuickDayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SubEditRow(
    draft: SubDraft,
    focusRequester: FocusRequester,
    onTextChange: (String) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        MiCheckbox(done = draft.done, size = 16.dp, onClick = onToggle)
        BasicTextField(
            value = draft.text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                fontSize = 14.5.sp,
                lineHeight = 20.sp,
                color = if (draft.done) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (draft.done) TextDecoration.LineThrough else null,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (draft.text.isEmpty()) {
                        Text("待办内容", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .padding(horizontal = 10.dp, vertical = 11.dp),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除子待办",
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 40.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}

fun repeatLabel(rule: RepeatRule): String = when (rule) {
    RepeatRule.NONE -> "不重复"
    RepeatRule.DAILY -> "每天"
    RepeatRule.WEEKLY -> "每周"
    RepeatRule.WEEKDAYS -> "周一至周五"
    RepeatRule.WORKDAYS -> "工作日"
    RepeatRule.MONTHLY -> "每月"
    RepeatRule.YEARLY -> "每年"
}

/** 提醒时间对话框：整天开关 + 到期日/时刻 + 重复规则（对应小米 RemindTimePickerDialog） */
@Composable
private fun RemindPickerDialog(
    initialDue: Long?,
    initialAllDay: Boolean,
    initialRepeat: RepeatRule,
    onApply: (Long?, Boolean, RepeatRule) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val base = initialDue ?: TodoDates.dayRemindFromToday(1)

    var pickedDate by remember { mutableStateOf(TodoDates.startOfDay(base)) }
    var minutes by remember {
        mutableStateOf(
            Calendar.getInstance().apply {
                timeInMillis = base
            }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) },
        )
    }
    var allDay by remember { mutableStateOf(initialAllDay) }
    var repeat by remember { mutableStateOf(initialRepeat) }
    var repeatPickOpen by remember { mutableStateOf(false) }

    fun buildDue(): Long {
        if (allDay) return TodoDates.endOfDay(pickedDate)
        return Calendar.getInstance().apply {
            timeInMillis = pickedDate
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val dateText = remember(pickedDate) {
        val c = Calendar.getInstance().apply { timeInMillis = pickedDate }
        "${c.get(Calendar.YEAR)}年${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    }
    val timeText = "%02d:%02d".format(minutes / 60, minutes % 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提醒时间") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("整天", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = allDay,
                        onCheckedChange = { allDay = it },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val c = Calendar.getInstance().apply { timeInMillis = pickedDate }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    pickedDate = TodoDates.startOfDay(
                                        Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis,
                                    )
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH),
                            ).show()
                        }
                        .padding(vertical = 12.dp),
                ) {
                    Text("到期日", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(dateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
                if (!allDay) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                TimePickerDialog(
                                    context,
                                    { _, hh, mm -> minutes = hh * 60 + mm },
                                    minutes / 60,
                                    minutes % 60,
                                    true,
                                ).show()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Text("时刻", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(timeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { repeatPickOpen = true }
                        .padding(vertical = 12.dp),
                ) {
                    Text("重复", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(repeatLabel(repeat), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(buildDue(), allDay, repeat) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (initialDue != null) {
                    TextButton(onClick = onClear) { Text("清除提醒", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )

    if (repeatPickOpen) {
        AlertDialog(
            onDismissRequest = { repeatPickOpen = false },
            title = { Text("重复提醒") },
            text = {
                Column {
                    listOf(
                        RepeatRule.NONE to "不重复",
                        RepeatRule.DAILY to "每天",
                        RepeatRule.WEEKLY to "每周",
                        RepeatRule.WEEKDAYS to "周一至周五",
                        RepeatRule.WORKDAYS to "工作日",
                        RepeatRule.MONTHLY to "每月",
                        RepeatRule.YEARLY to "每年",
                    ).forEach { (rule, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    repeat = rule
                                    repeatPickOpen = false
                                }
                                .padding(vertical = 7.dp),
                        ) {
                            RadioButton(
                                selected = repeat == rule,
                                onClick = {
                                    repeat = rule
                                    repeatPickOpen = false
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { repeatPickOpen = false }) { Text("取消") }
            },
        )
    }
}
