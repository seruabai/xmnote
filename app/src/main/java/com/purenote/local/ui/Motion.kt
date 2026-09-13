package com.purenote.local.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 动效令牌（全应用统一，用户 2026-09-13 要求"慢速、高级"）：
 * - 节奏偏慢但果断：转场 420ms 左右，微交互 120-200ms，位移全部走 spring 物理曲线
 * - 层级用动效解释：推入有视差(旧页缩小变暗)、弹层从底部升起、按压有回弹
 * - 阻尼 0.8-1.0 区间：只有轻微过冲，不做夸张弹跳（Apple 克制原则）
 */
object Motion {

    // ---- 时长（ms）----
    /** 页面推入：慢而稳 */
    const val SCREEN_IN = 420
    /** 页面退出：比推入快一档，不抢戏 */
    const val SCREEN_OUT = 320
    /** 弹层（底部面板/预览）升起 */
    const val SHEET = 380
    /** 内容展开/收起（样式面板、工具栏） */
    const val EXPAND = 300
    /** 微交互（按压、勾选、图标弹跳） */
    const val FAST = 160
    /** 淡入淡出基线 */
    const val FADE = 240

    // ---- 缓动 ----
    /** iOS 式减速：快出缓停，用于元素入场 */
    val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    /** 强调入场：先快后极缓 */
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** 退场：缓入快出 */
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    // ---- 弹簧 ----

    /** 页面推入弹簧：轻微过冲（阻尼 0.86），慢刚度 380——"推到位再稳一下"的高级感 */
    fun <T> screenSpring() = spring<T>(dampingRatio = 0.86f, stiffness = 380f)

    /** 弹层升起弹簧：更柔，几乎无过冲 */
    fun <T> sheetSpring() = spring<T>(dampingRatio = 0.95f, stiffness = 340f)

    /** 微交互弹簧：按压回弹/图标弹跳，中速中弹 */
    fun <T> pressSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** 兜底 tween（需要确定时长语义的场景） */
    fun <T> screenTween(duration: Int = SCREEN_IN) = tween<T>(duration, easing = Emphasized)
}

/**
 * 弹层入场动效助手：缩放 0.92→1（sheetSpring）+ 淡入，包住 Dialog 的根内容即可。
 * 关闭由 Dialog 自身卸载完成，不做出场动画（卸载是同步的）。
 */
@Composable
fun MotionDialogEnter(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.92f,
        animationSpec = Motion.sheetSpring(),
        label = "dlgScale",
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(Motion.FADE),
        label = "dlgAlpha",
    )
    Box(Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = enterAlpha
    }) { content() }
}
