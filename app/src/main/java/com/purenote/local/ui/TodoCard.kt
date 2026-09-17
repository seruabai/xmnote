package com.purenote.local.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteViewModel
import com.purenote.local.core.TodoDates
import com.purenote.local.data.Todo
import kotlinx.coroutines.delay

/**
 * 小米待办同款方角勾选框：未勾为描边方块，勾选后墨色填充 + 纸色对勾。
 *
 * 用 [toggleable] 而不是 clickable：这样它在无障碍树里是一个**真正的勾选框**
 * （有 role=Checkbox 与 checked 状态）。此前只有 clickable，读屏软件念不出
 * "这是勾选框、勾没勾"，设备验收也定位不到它——清单编辑器与待办卡片都在用这个控件。
 */
@Composable
fun MiCheckbox(done: Boolean, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 光学对齐（用户 2026-09-17：勾选栏没跟文字对齐，字体大小不同都要中线对齐）：
    // 行盒中心并不是汉字墨水的中心——汉字在 em 盒里偏下，居中摆放的方框看上去会"偏高"。
    // 这里按尺寸比例下移一点点（5.5%），任何字号下框的视觉中心都压在文字中线上。
    // 用比例而不是固定 dp：三档正文尺寸切换时同样成立。
    // 比例值由设备像素测量反推：安卓 13 模拟器(440dpi)上以 21dp 框实测——
    // 5.5% 时框比文字墨水中线高 3px，11.5% 时低 3px，取 8.5% 两者中心重合
    // （measure_ink 脚本量的是框描边中心 vs 同行文字墨水中心）。
    val opticalNudge = with(LocalDensity.current) { (size * 0.07f).toPx() }
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
            .offset { IntOffset(0, opticalNudge.roundToInt()) }
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { onClick() }),
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

/**
 * 单条待办卡片（只读展示）：白色圆角行 + 方形勾选 + 时间行 + 清单展开。
 * 点击卡片开底部弹窗编辑；左滑露出圆形红删除键。
 * 长按进入多选模式：左侧变拖动手柄，右侧变选中圆圈，点卡片切换选中。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodoCardRow(
    vm: NoteViewModel,
    todo: Todo,
    subs: List<Todo>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    dragHandle: Modifier = Modifier,
) {
    val isListTodo = subs.isNotEmpty()
    val doneCount = subs.count { it.done }

    if (selectionMode) {
        // 多选模式不走左滑删除，直接整卡可点选，左侧三条杠手柄支持上下排序。
        // iOS 编辑模式：未选中白卡，选中浅灰底，左侧三条杠手柄，右侧圆圈（选中主色底白勾）。
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surface,
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggleSelect, onLongClick = onToggleSelect),
        ) {
            // 与常态共用同一套内外边距、同一个正文组件：进出多选时每张卡片的高度与标题
            // 基线都不许变，否则整列会往上走/往下落一下（用户 2026-09-17 反馈）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    EqualHandle(modifier = dragHandle)
                }
                TodoCardBody(todo, subs, doneCount, isListTodo, expanded, onExpandToggle)
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        return
    }
    RevealDeleteRow(
        revealed = revealed,
        onRevealChange = onRevealChange,
        onDelete = { vm.deleteTodo(todo) },
        modifier = modifier,
    ) { slide ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (revealed) onRevealChange(false)
                                else vm.openTodoSheet(todo.id)
                            },
                            onLongClick = onLongPress,
                        )
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                ) {
                    // 左滑露删除键时勾选栏直接消失：它本来就被卡片拖着走，看着像要被删掉
                    // （用户 2026-09-17）。收起而不是留白，标题顺势左移，视线跟着删除键走。
                    if (slide < 0.02f) {
                        // 槽位宽度与多选态的三条杠手柄一致：两种状态下标题起点是同一个 x
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            MiCheckbox(done = todo.done, size = 19.dp, onClick = { vm.toggleTodo(todo) })
                        }
                    }
                    TodoCardBody(todo, subs, doneCount, isListTodo, expanded, onExpandToggle)
                }
                if (isListTodo && expanded) {
                    subs.forEachIndexed { idx, sub ->
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 45.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (idx == 0) 0.45f else 0.25f),
                        )
                        SubListRow(
                            vm = vm,
                            sub = sub,
                            slide = slide,
                            onEdit = { vm.openTodoSheet(todo.id, focusSubId = sub.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 卡片正文（标题 + 可选到期时间 + 清单展开控件）：多选态与常态共用同一份， */
/** 进出多选时每张卡片的高度与标题基线都不变，整列不会"往上走"（用户 2026-09-17 反馈）。 */
@Composable
private fun RowScope.TodoCardBody(
    todo: Todo,
    subs: List<Todo>,
    doneCount: Int,
    isListTodo: Boolean,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
) {
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
        Text(
            todo.title.ifBlank { "待办清单" },
            style = TextStyle(
                fontSize = 16.sp,
                lineHeight = 21.sp,
                color = if (todo.done) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.onSurface,
                // 去掉字体自带的上下内边距：行盒贴住字形，勾选框的居中才是"看着居中"
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
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
        val arrowRotation by animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(200),
            label = "expandArrow",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onExpandToggle)
                .padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
        ) {
            Text(
                "$doneCount/" + subs.size,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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
}

/** 多选模式左侧的三条杠拖动手柄（对标小米笔记实机：三条浅灰横线）。 */
@Composable
private fun EqualHandle(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.width(28.dp).height(24.dp),
    ) {
        listOf(0, 1, 2).forEach { i ->
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)),
            )
            if (i < 2) Spacer(Modifier.height(4.dp))
        }
    }
}

/** 非编辑态的子待办行：子项属于清单内容，删除直接移除不进废纸篓 */
@Composable
private fun SubListRow(vm: NoteViewModel, sub: Todo, slide: Float = 0f, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 45.dp, end = 10.dp),
    ) {
        if (slide < 0.02f) {
            MiCheckbox(done = sub.done, size = 15.dp, onClick = { vm.toggleTodo(sub) })
        }
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
        IconButton(onClick = { vm.deleteTodoForever(sub) }, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "删除子待办",
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** 时间行：时钟图标 + 小米格式文案；过期整行红、完成灰 */
@Composable
private fun DueTimeText(todo: Todo) {
    // 每分钟跳一次驱动重组，"今天/明天"这类相对文案才不会在卡片数据不变时一直显示旧值
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            nowTick = System.currentTimeMillis()
        }
    }
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
            TodoDates.formatDue(todo.dueAt ?: 0L, todo.allDay, todo.repeat, nowTick),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
