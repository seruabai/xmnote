package com.purenote.local.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purenote.local.NoteTextSize
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.core.NoteMarkup
import com.purenote.local.core.PreviewBuilder
import com.purenote.local.data.Note
import com.purenote.local.data.NoteKind

/** 便签纸卡片：流式正文首行为标题；清单带进度与小圆点；支持多选角标 */
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
                    Text(
                        formatNoteDate(note.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!folderName.isNullOrBlank()) {
                        Text(
                            folderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 置顶角标从标题栏右边移到时间栏右边（用户 2026-09-07 要求）
                    PinBadge(note.pinned)
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .size(20.dp),
                )
            }
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
        if (spaced) Spacer(Modifier.width(8.dp)) else Spacer(Modifier.width(6.dp))
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
