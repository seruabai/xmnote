package com.purenote.local.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 左滑露出删除键：卡片最多左移一键宽度，右侧露出圆形红删除键，
 * 卡片本身不整个划走。点删除键删除，点卡片其他区域回弹。
 */
@Composable
fun RevealDeleteRow(
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cardShape = RoundedCornerShape(18.dp)
    val revealPx = with(LocalDensity.current) { 78.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 用状态直读代替 pointerInput(revealed) 重启：reveal 变化不再重建手势检测，拖拽不被打断
    val currentRevealed by rememberUpdatedState(revealed)

    fun snapTo(reveal: Boolean) {
        scope.launch { offset.animateTo(if (reveal) -revealPx else 0f, tween(180)) }
    }

    // 其他卡片露出时把这一张收回
    LaunchedEffect(revealed) { snapTo(revealed) }

    Box(modifier.clip(cardShape)) {
        // matchParentSize 跟内容等高，避免无限高度约束下背景层错位导致按钮对不齐卡片。
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.matchParentSize(),
        ) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = offset.value < -revealPx / 2
                            snapTo(target)
                            onRevealChange(target)
                        },
                        onDragCancel = { snapTo(currentRevealed) },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(-revealPx, 0f))
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}
