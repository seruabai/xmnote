package com.purenote.local.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteTextSize
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.core.DateFormats
import com.purenote.local.core.NoteMarkup
import com.purenote.local.core.PreviewBuilder
import com.purenote.local.data.Note
import com.purenote.local.data.NoteKind

/**
 * 便签纸卡片：流式正文首行为标题；清单带进度与小圆点；支持多选勾选框。
 *
 * 进出多选**卡片几何不许变**（用户 2026-09-19："笔记目录下的长按进入的多选界面，布局应该与
 * 原本未长按情况下相同"）：所以多选态新增的东西一个都不参与卡片高度计算 —— 勾选框是叠在
 * 卡片右下角的一层 Box，行尾只让出一个"宽度"当空位。待办页早先踩过同一个坑（见 ui/TodoCard.kt
 * 里抽出的 TodoCardBody 与钉死高度的页头动作行），这里是同一个口径。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    folderName: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    textSize: NoteTextSize = NoteTextSize.DEFAULT,
    selected: Boolean = false,
    /** 多选态：右下角显示勾选框，时间栏右端为它让出空位 */
    selecting: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    // 按压缩放反馈：按下 0.97，松手 tween 回弹（抖音式触感，克制的幅度）
    val cardInteraction = remember { MutableInteractionSource() }
    val cardPressed by cardInteraction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (cardPressed) 0.97f else 1f,
        animationSpec = tween(Motion.FAST, easing = Motion.EaseOut),
        label = "cardPress",
    )
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .noteSharedBounds(key = "note-${note.id}")   // 卡片 → 编辑器共享元素(hero)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .combinedClickable(
                interactionSource = cardInteraction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        colors = CardDefaults.cardColors(containerColor = noteContainerColor(note.colorIndex)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box {
            // 内外边距全部落在 4dp 网格上：15dp/10dp/7dp/5dp 这类值会让同一列卡片的
            // 文本基线看着"差半个像素"（用户 2026-09-17 要求按大厂标准重排）
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                val visibleImages = note.images.filterNot { it.startsWith("aud:") }
                if (visibleImages.isNotEmpty()) {
                    AsyncThumb(
                        fileName = visibleImages.first(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(bottom = 12.dp),
                    )
                }

                when (note.kind) {
                    NoteKind.TEXT -> TextCardBody(note, textSize)
                    NoteKind.CHECKLIST -> ChecklistCardBody(note, textSize)
                    // 脑图卡片显示大纲投影（note.body 已是逐行节点文字）
                    NoteKind.MIND -> TextCardBody(note, textSize)
                }

                // 元信息（日期/分类/图钉）钉在卡片底部：同一行的卡片等高时，
                // 这些字会落在同一条水平线上，而不是跟着内容长短浮动
                Spacer(Modifier.weight(1f, fill = true))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 左下角那一格：设了提醒就整格换成提醒日期时间，没设才是笔记日期（要求 4/5）
                    NoteStampCell(note, Modifier.weight(1f))
                    if (!folderName.isNullOrBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            folderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 给分类名封顶：它再长也不许把左下角那一格挤成零宽（那格是日期/提醒）
                            modifier = Modifier.widthIn(max = 96.dp),
                        )
                    }
                    // 置顶角标从标题栏右边移到时间栏右边（用户 2026-09-07 要求）
                    PinBadge(note.pinned)
                    // 行尾永远留着勾选框那一格（只有宽度、不出高度）：进多选时行内元素一个都不挪，
                    // 左下角那一格的可用宽度也不变 —— 否则时间格里"年"会跟着多选状态忽隐忽现
                    // （用户 2026-09-19 要求 1"布局应该与原本未长按情况下相同"）。
                    // 待办页同款做法：见 ui/TodoCard.kt"槽位宽度与多选态的三条杠手柄一致"。
                    // 置顶图标因此恒定落在勾选框左侧（要求 3）。
                    Spacer(Modifier.width(SelectionCheckSlot))
                }
            }
            if (selecting) {
                // 勾选框钉在卡片右下角（用户 2026-09-19 要求 2："勾选框应该出现在最右下角"）。
                // 叠在卡片上而不是放进内容 Column：放进去会撑高卡片，进出多选整列都会动。
                // 命中区 48dp（硬性要求 ≥44dp）：MiCheckbox 是自绘的、不带 Material 的 48dp 最小
                // 触控尺寸，所以命中区由外层的 toggleable 提供；内层视觉 clearAndSetSemantics 抹掉，
                // 无障碍树里只留一个真正的勾选框节点（否则会多出一个 18dp 的"无名小框"）。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 48dp：比硬性要求的 44dp 再宽一点，正好是 Material 最小触控尺寸 ——
                        // 低于 48dp 时 Compose 会把这个节点的无障碍边界向外扩张，
                        // 报出来的框就会溢出卡片右下角，设备审计量到的就不是真实命中区了。
                        .size(48.dp)
                        .toggleable(
                            value = selected,
                            role = Role.Checkbox,
                            onValueChange = { onToggleSelect() },
                        )
                        // 一个节点把"是什么、勾没勾、点了干什么"讲全：分开写会变成两个无障碍节点
                        // （一个是无名复选框、一个是纯文字），读屏要念两遍、审计也找不准。
                        .clearAndSetSemantics {
                            contentDescription = "选择笔记"
                            role = Role.Checkbox
                            toggleableState = ToggleableState(selected)
                            onClick { onToggleSelect(); true }
                        },
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Box(Modifier.clearAndSetSemantics {}) {
                        MiCheckbox(
                            done = selected,
                            size = 18.dp,
                            onClick = onToggleSelect,
                            // 内缩 16dp：勾选框右下缘与内容 16dp 栅格线重合，正好在"最右下角"
                            modifier = Modifier.padding(end = 16.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 多选态行尾为勾选框让出的宽度：18dp 勾选框 + 4dp 间距（都是 4dp 网格上的值） */
private val SelectionCheckSlot = 22.dp

/** 提醒图标连同它前面的间距：12dp 图标 + 4dp 间距，放不下时这一格会先撤图标（见 NoteStampCell） */
private val ReminderIconSpan = 16.dp

/**
 * 卡片左下角那一格（用户 2026-09-19 要求 4/5）：
 * - 设了提醒：整格换成提醒的日期时间，前面的小图标是"这是提醒不是笔记日期"的记号；
 * - 没设提醒：仍是原来的笔记日期。
 *
 * 写不写年、带不带图标，都由这一格**量出来的真实可用宽度**决定（用 TextMeasurer 量同一份
 * 字体的实际像素宽，而不是按"跨年才显示年"之类的规则猜）。降级顺序：
 *   带年+图标 → 带年（撤图标）→ 省年+图标 → 省年（撤图标）
 * 先保文案完整、再保图标；任何一步都不压缩字距、不换行 —— 用户 2026-09-19："如果年塞不进去，
 * 不强制塞入年份在时间栏"、"不要为了塞年份把字挤掉或换行；宽度够时才带年份"。
 * 这一格的宽度随分类名长短、宫格/列表模式变化，所以必须实测量宽，不能写死。
 */
@Composable
private fun NoteStampCell(note: Note, modifier: Modifier = Modifier) {
    val remindAt = note.remindAt
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val style = MaterialTheme.typography.labelSmall
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val capPx = with(density) { maxWidth.toPx().toInt() }
        val iconPx = with(density) { ReminderIconSpan.toPx().toInt() }
        val full = when {
            remindAt == null -> DateFormats.yearMonthDay(note.updatedAt)
            note.allDay -> DateFormats.yearMonthDay(remindAt)
            else -> DateFormats.yearMonthDayHourMinute(remindAt)
        }
        val short = when {
            remindAt == null -> DateFormats.monthDay(note.updatedAt)
            note.allDay -> DateFormats.monthDay(remindAt)
            else -> DateFormats.monthDay(remindAt) + " " + DateFormats.hourMinute(remindAt)
        }
        val widths = remember(capPx, full, short, style, measurer) {
            fun w(s: String) = measurer.measure(
                AnnotatedString(s),
                style = style,
                maxLines = 1,
                softWrap = false,
            ).size.width
            w(full) to w(short)
        }
        val (wFull, wShort) = widths
        val hasReminder = remindAt != null
        val showIcon: Boolean
        val text: String
        when {
            !hasReminder -> {
                showIcon = false
                text = if (wFull <= capPx) full else short
            }
            wFull + iconPx <= capPx -> {
                showIcon = true; text = full
            }
            wFull <= capPx -> {
                showIcon = false; text = full
            }
            wShort + iconPx <= capPx -> {
                showIcon = true; text = short
            }
            else -> {
                showIcon = false; text = short
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showIcon) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = "提醒",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextCardBody(note: Note, textSize: NoteTextSize) {
    val typeScale = textSize.typeScale()
    val plain = NoteMarkup.stripHeadingMarkers(note.body)
    val (firstLine, rest) = PreviewBuilder.splitTitle(plain)
    val head = note.title.ifBlank { firstLine }
    val preview = if (note.title.isBlank()) rest else plain
    if (head.isNotBlank()) {
        Text(
            head,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = typeScale.cardTitleSp.sp,
                lineHeight = typeScale.cardTitleLineHeightSp.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
    }
    if (preview.isNotBlank()) {
        Text(
            PreviewBuilder.textPreview(NoteMarkup.previewText(preview)),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = typeScale.cardBodySp.sp,
                lineHeight = typeScale.cardBodyLineHeightSp.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChecklistCardBody(note: Note, textSize: NoteTextSize) {
    val typeScale = textSize.typeScale()
    val (done, total) = ChecklistCodec.progress(note.items)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$done/$total",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            modifier = Modifier.weight(1f).height(4.dp),
        )
        PinBadge(note.pinned, spaced = true)
    }
    Spacer(Modifier.height(8.dp))
    val pending = note.items.filter { !it.done }.take(4)
    if (pending.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "全部已完成",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = typeScale.cardBodySp.sp,
                    lineHeight = typeScale.cardBodyLineHeightSp.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    } else {
        pending.forEach { item ->
            // 不加逐条微间距：行距交给行高，条目之间才是同一个节奏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = typeScale.cardBodySp.sp,
                        lineHeight = typeScale.cardBodyLineHeightSp.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PinBadge(pinned: Boolean, spaced: Boolean = false) {
    if (pinned) {
        if (spaced) Spacer(Modifier.width(8.dp)) else Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Outlined.PushPin,
            contentDescription = "已置顶",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** 多选模式的圆形勾选框（供列表行复用） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectDot(selected: Boolean, onClick: () -> Unit) {
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = if (selected) "已选中" else "未选中",
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier
            .size(22.dp)
            .combinedClickable(onClick = onClick),
    )
}
