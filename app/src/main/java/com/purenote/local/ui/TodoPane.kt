package com.purenote.local.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.purenote.local.NoteViewModel
import com.purenote.local.core.TodoGrouper
import com.purenote.local.data.Todo

/** 待办主页：连续大卡片列表。同一时间最多一张卡片露出删除键，新建/编辑统一走底部弹窗。 */
@Composable
fun TodoPane(vm: NoteViewModel, modifier: Modifier = Modifier) {
    val todos by vm.todos.collectAsState()
    val sheetId by vm.todoSheetId.collectAsState()
    val expandedLists = remember { mutableStateMapOf<Long, Boolean>() }
    var revealedId by remember { mutableStateOf<Long?>(null) }
    val rootTodos = remember(todos) {
        todos.filter { !it.isSubtask }.sortedWith(
            compareBy<Todo> { it.done }.thenBy { it.sortIndex }.thenByDescending { it.updatedAt },
        )
    }

    BackHandler(enabled = revealedId != null) { revealedId = null }

    if (rootTodos.isEmpty()) {
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
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, top = 2.dp, bottom = 110.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rootTodos, key = { it.id }) { todo ->
                TodoCardRow(
                    vm = vm,
                    todo = todo,
                    subs = TodoGrouper.subsOf(todo.id, todos),
                    expanded = expandedLists[todo.id] ?: false,
                    onExpandToggle = { expandedLists[todo.id] = !(expandedLists[todo.id] ?: false) },
                    revealed = revealedId == todo.id,
                    onRevealChange = { reveal ->
                        revealedId = if (reveal) todo.id else revealedId?.takeIf { it != todo.id }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    sheetId?.let { TodoEditSheet(vm = vm, todoId = it, onClose = { vm.closeTodoSheet() }) }
}
