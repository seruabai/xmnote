package com.purenote.local.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.purenote.local.NoteViewModel
import com.purenote.local.data.NoteKind
import com.purenote.local.Screen

/** 屏幕层级：用于决定转场滑动方向（前进右滑、后退左滑）。 */
private fun Screen.depth(): Int = when (this) {
    Screen.Home -> 0
    Screen.Trash, Screen.Folders, Screen.Settings -> 1
    is Screen.Editor -> 2
}

/**
 * 页面转场（Motion 令牌，慢速高级）：
 * - 推入：新页从右整幅滑入（screenSpring 轻微过冲），旧页向左视差移动 1/3 并淡出——层级感
 * - 返回：反向进行，旧页在最上层滑出（iOS 导航栈语义）
 * 淡入淡出用固定短 tween 保证不拖泥带水，位移全部走 spring 物理曲线。
 */
@Composable
fun AppRoot(vm: NoteViewModel) {
    val screen by vm.screen.collectAsState()

    LaunchedEffect(Unit) {
        vm.consumePendingOpenTarget()?.let { (kind, id) -> vm.open(kind, id) }
        vm.consumePendingShare()?.let { prefill ->
            vm.openEditorWithPrefill(prefill, kind = NoteKind.TEXT)
        }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState.depth() >= initialState.depth()
            if (forward) {
                (slideInHorizontally(Motion.screenSpring()) { it } +
                    fadeIn(tween(Motion.FADE))) togetherWith
                    (slideOutHorizontally(Motion.screenSpring()) { -it / 3 } +
                        fadeOut(tween(Motion.SCREEN_OUT, easing = Motion.EaseIn)))
            } else {
                (slideInHorizontally(Motion.screenSpring()) { -it / 3 } +
                    fadeIn(tween(Motion.FADE))) togetherWith
                    (slideOutHorizontally(Motion.screenSpring()) { it } +
                        fadeOut(tween(Motion.SCREEN_OUT, easing = Motion.EaseIn)))
            }
        },
        label = "screenTransition",
    ) { s ->
        // 层级深的页面盖在浅的上面：推入时新页在最上滑入；返回时旧页在最上滑出
        Box(Modifier.zIndex(if (s.depth() >= screen.depth()) 1f else 0f)) {
            when (s) {
                is Screen.Editor -> EditorScreen(vm, s)
                Screen.Trash -> TrashScreen(vm)
                Screen.Folders -> FoldersScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Home -> HomeScreen(vm)
            }
        }
    }
}
