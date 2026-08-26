package com.purenote.local.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.purenote.local.NoteViewModel
import com.purenote.local.data.NoteKind
import com.purenote.local.Screen

@Composable
fun AppRoot(vm: NoteViewModel) {
    val screen by vm.screen.collectAsState()

    LaunchedEffect(Unit) {
        vm.consumePendingOpenTarget()?.let { (kind, id) -> vm.open(kind, id) }
        vm.consumePendingShare()?.let { prefill ->
            vm.openEditorWithPrefill(prefill, kind = NoteKind.TEXT)
        }
    }

    when (val s = screen) {
        is Screen.Editor -> EditorScreen(vm, s)
        Screen.Trash -> TrashScreen(vm)
        Screen.Folders -> FoldersScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Home -> HomeScreen(vm)
        is Screen.TodoEdit -> TodoEditScreen(vm, s.todoId)
    }
}
