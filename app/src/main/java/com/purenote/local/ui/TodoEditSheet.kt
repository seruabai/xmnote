package com.purenote.local.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteViewModel
import com.purenote.local.core.TodoDates
import com.purenote.local.data.RepeatRule
import java.util.UUID

private data class SheetRow(
    val text: String,
    val done: Boolean,
    val key: String = UUID.randomUUID().toString(),
    val sourceId: Long? = null,
)

/**
 * 待办底部弹窗编辑器：新建与编辑统一入口。
 * 单条模式只有一行，首行回车转为"标题 + 待办项"清单模式，标题默认"待办清单"；
 * 清单模式下任何一行回车都在下方插入新行并聚焦。
 * 关闭（完成/下滑/返回/点罩）时按规则保存，全空则直接丢弃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditSheet(vm: NoteViewModel, todoId: Long, onClose: () -> Unit) {
    val allTodos by vm.todos.collectAsState()
    val focusSubId by vm.todoSheetFocusSubId.collectAsState()
    val creating = todoId <= 0
    var loaded by remember(todoId) { mutableStateOf(creating) }
    var title by remember(todoId) { mutableStateOf("") }
    val rows = remember(todoId) { mutableStateListOf<SheetRow>() }
    var listMode by remember(todoId) { mutableStateOf(false) }
    var dueAt by remember(todoId) { mutableStateOf<Long?>(null) }
    var allDay by remember(todoId) { mutableStateOf(false) }
    var repeat by remember(todoId) { mutableStateOf(RepeatRule.NONE) }
    var origDone by remember(todoId) { mutableStateOf(false) }
    var remindOpen by remember(todoId) { mutableStateOf(false) }
    var pendingFocusKey by remember(todoId) { mutableStateOf<String?>(null) }
    var pendingTitleFocus by remember(todoId) { mutableStateOf(false) }
    val rowFocus = remember(todoId) { mutableMapOf<String, FocusRequester>() }
    val titleFocus = remember(todoId) { FocusRequester() }

    // 弹窗与输入法同动：首帧就请求聚焦，让输入法动画和弹窗进入动画并发，
    // imePadding 逐帧跟随键盘高度，弹窗始终贴在键盘顶部；收起时同理落回底部。
    // 之前 delay(90) 等弹窗落定才弹键盘，是"弹窗→键盘→弹窗再跳"三段式的根因。
    LaunchedEffect(pendingFocusKey) {
        pendingFocusKey?.let { key ->
            withFrameNanos { }
            runCatching { rowFocus[key]?.requestFocus() }
            pendingFocusKey = null
        }
    }
    LaunchedEffect(pendingTitleFocus) {
        if (pendingTitleFocus) {
            withFrameNanos { }
            runCatching { titleFocus.requestFocus() }
            pendingTitleFocus = false
        }
    }

    LaunchedEffect(todoId) {
        if (creating) {
            val first = SheetRow("", false)
            rows.add(first)
            loaded = true
            pendingFocusKey = first.key
        } else {
            vm.getTodoOnce(todoId) { t ->
                if (t == null || t.trashed) {
                    onClose()
                    return@getTodoOnce
                }
                title = t.title
                dueAt = t.dueAt
                allDay = t.allDay
                repeat = t.repeat
                origDone = t.done
                val children = allTodos.filter { it.parentId == t.id }
                if (children.isNotEmpty()) {
                    listMode = true
                    rows.addAll(children.map { SheetRow(it.title, it.done, sourceId = it.id) })
                } else {
                    rows.add(SheetRow(t.title, t.done, sourceId = null))
                }
                loaded = true
                if (listMode) {
                    val target = rows.firstOrNull { it.sourceId == focusSubId }?.key
                    if (target != null) pendingFocusKey = target
                    else if (focusSubId == null) pendingTitleFocus = true
                    else pendingFocusKey = rows.firstOrNull()?.key
                } else {
                    pendingFocusKey = rows.firstOrNull()?.key
                }
            }
        }
    }

    /** 某行回车：单条模式先转为清单（该行变为待办项），再在下方插入空行并聚焦。 */
    fun enterFromRow(index: Int) {
        listMode = true
        val next = SheetRow("", false)
        rows.add(index + 1, next)
        pendingFocusKey = next.key
    }

    fun saveAndClose() {
        val items = rows.map { it.text.trim() to it.done }.filter { it.first.isNotBlank() }
        if (creating) {
            if (title.isBlank() && items.isEmpty()) {
                onClose()
                return
            }
            if (!listMode && title.isBlank() && items.size <= 1) {
                val first = items.firstOrNull()
                if (first == null) {
                    onClose()
                    return
                }
                vm.createTodo(first.first, dueAt, allDay, repeat, emptyList()) { id ->
                    if (first.second) vm.setTodoDoneById(id, true)
                }
            } else {
                vm.createTodo(title.trim().ifBlank { "待办清单" }, dueAt, allDay, repeat, items)
            }
        } else {
            if (title.isBlank() && items.isEmpty()) {
                // 全清空视为放弃本次编辑，不断尾删除
                onClose()
                return
            }
            if (!listMode) {
                val first = items.firstOrNull()
                if (first == null) {
                    onClose()
                    return
                }
                vm.updateTodo(todoId, first.first, dueAt, allDay, repeat)
                if (first.second != origDone) vm.setTodoDoneById(todoId, first.second)
            } else {
                vm.updateTodo(todoId, title.trim().ifBlank { "待办清单" }, dueAt, allDay, repeat)
                vm.saveTodoSubs(todoId, items)
            }
        }
        onClose()
    }

    ModalBottomSheet(
        onDismissRequest = { saveAndClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        if (!loaded) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            ) {
                Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 22.dp),
            ) {
                if (listMode) {
                    SheetTitleField(
                        value = title,
                        onValueChange = { title = it },
                        onNext = { pendingFocusKey = rows.firstOrNull()?.key },
                        focusRequester = titleFocus,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                rows.forEachIndexed { idx, row ->
                    SheetItemRow(
                        row = row,
                        singleStyle = !listMode,
                        focusRequester = rowFocus.getOrPut(row.key) { FocusRequester() },
                        onTextChange = { rows[idx] = row.copy(text = it) },
                        onToggle = { rows[idx] = row.copy(done = !row.done) },
                        onNext = { enterFromRow(idx) },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SheetReminderPill(
                        dueAt = dueAt,
                        allDay = allDay,
                        repeat = repeat,
                        onPick = { remindOpen = true },
                        onClear = {
                            dueAt = null
                            allDay = false
                            repeat = RepeatRule.NONE
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "完成",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { saveAndClose() }
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
}

/** 清单模式的标题栏：回车跳到第一行；聚焦时光标强制到末尾，点哪行光标就在该行末尾。 */
@Composable
private fun SheetTitleField(
    value: String,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit,
    focusRequester: FocusRequester,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (tfv.text != value) tfv = tfv.copy(text = value)
    BasicTextField(
        value = tfv,
        onValueChange = {
            tfv = it
            if (it.text != value) onValueChange(it.text)
        },
        textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        decorationBox = { inner ->
            Box {
                if (tfv.text.isEmpty()) {
                    Text(
                        "待办清单",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) tfv = tfv.copy(selection = TextRange(tfv.text.length)) },
    )
}

/** 弹窗里的一行：勾选框 + 输入栏，回车在下方加一行。空行保存时丢弃，所以不需要删除键。 */
@Composable
private fun SheetItemRow(
    row: SheetRow,
    singleStyle: Boolean,
    focusRequester: FocusRequester,
    onTextChange: (String) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val fontSize = if (singleStyle) 16.sp else 15.sp
    var tfv by remember(row.key) { mutableStateOf(TextFieldValue(row.text, TextRange(row.text.length))) }
    if (tfv.text != row.text) tfv = tfv.copy(text = row.text)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        MiCheckbox(
            done = row.done,
            size = if (singleStyle) 19.dp else 17.dp,
            onClick = onToggle,
        )
        BasicTextField(
            value = tfv,
            onValueChange = {
                tfv = it
                if (it.text != row.text) onTextChange(it.text)
            },
            textStyle = TextStyle(
                fontSize = fontSize,
                lineHeight = 22.sp,
                color = if (row.done) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (row.done) TextDecoration.LineThrough else null,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            decorationBox = { inner ->
                Box {
                    if (tfv.text.isEmpty()) {
                        Text(
                            "待办内容",
                            fontSize = fontSize,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .padding(vertical = 10.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) tfv = tfv.copy(selection = TextRange(tfv.text.length)) },
        )
    }
}

/** 左下提醒小胶囊：无时间显示"设置提醒"，有时间显示格式化文案 + 清除键。 */
@Composable
private fun SheetReminderPill(
    dueAt: Long?,
    allDay: Boolean,
    repeat: RepeatRule,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onPick).padding(start = 11.dp),
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
                IconButton(onClick = onClear, modifier = Modifier.size(26.dp)) {
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
}
