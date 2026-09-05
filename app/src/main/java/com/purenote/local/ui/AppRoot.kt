package com.purenote.local.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.purenote.local.NoteViewModel
import com.purenote.local.data.NoteKind
import com.purenote.local.Screen

/** 屏幕层级：用于决定转场滑动方向（前进右滑、后退左滑）。 */
private fun Screen.depth(): Int = when (this) {
    Screen.Home -> 0
    Screen.Trash, Screen.Folders, Screen.Settings, is Screen.TodoEdit -> 1
    is Screen.Editor -> 2
}

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
            val enter = if (forward) {
                (slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                ) + fadeIn(animationSpec = tween(300))).togetherWith(
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(300),
                    ) + fadeOut(animationSpec = tween(300))
                )
            } else {
                (slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                ) + fadeIn(animationSpec = tween(300))).togetherWith(
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(300),
                    ) + fadeOut(animationSpec = tween(300))
                )
            }
            enter
        },
        label = "screenTransition",
    ) { s ->
        when (s) {
            is Screen.Editor -> EditorScreen(vm, s)
            Screen.Trash -> TrashScreen(vm)
            Screen.Folders -> FoldersScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            Screen.Home -> HomeScreen(vm)
            is Screen.TodoEdit -> TodoEditScreen(vm, s.todoId)
        }
    }
}
