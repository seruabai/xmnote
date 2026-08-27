package com.purenote.local

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.DataChanges
import com.purenote.local.data.Folder
import com.purenote.local.data.Note
import com.purenote.local.data.NoteFilter
import com.purenote.local.data.NoteKind
import com.purenote.local.data.NotePrefill
import com.purenote.local.data.NoteRepository
import com.purenote.local.data.RepeatRule
import com.purenote.local.data.SortOrder
import com.purenote.local.data.Todo
import com.purenote.local.core.TodoDates
import com.purenote.local.notify.Reminders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Trash : Screen
    data object Folders : Screen
    data object Settings : Screen
    data class Editor(
        val noteId: Long,
        val kind: NoteKind,
        val folderId: Long?,
        val prefill: NotePrefill = NotePrefill(),
    ) : Screen

    /** todoId = -1 表示新建 */
    data class TodoEdit(val todoId: Long) : Screen
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 小米笔记设置页中的三档正文尺寸。 */
enum class NoteTextSize { SMALL, DEFAULT, LARGE }

enum class MainTab { NOTES, TODO }

class NoteViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: NoteRepository = (app as PureNoteApp).repository
    private val prefs = app.getSharedPreferences("pure_prefs", Context.MODE_PRIVATE)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _tab = MutableStateFlow(MainTab.NOTES)
    val tab: StateFlow<MainTab> = _tab.asStateFlow()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _todos = MutableStateFlow<List<Todo>>(emptyList())
    val todos: StateFlow<List<Todo>> = _todos.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _folderCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val folderCounts: StateFlow<Map<Long, Int>> = _folderCounts.asStateFlow()

    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount.asStateFlow()

    private val _filter = MutableStateFlow(NoteFilter())
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val searchActiveInternal = MutableStateFlow(false)
    val searchActive: StateFlow<Boolean> = searchActiveInternal.asStateFlow()

    private val gridInternal = MutableStateFlow(prefs.getBoolean(KEY_GRID, true))
    val gridMode: StateFlow<Boolean> = gridInternal.asStateFlow()

    private val textSizeInternal = MutableStateFlow(
        runCatching { NoteTextSize.valueOf(prefs.getString(KEY_TEXT_SIZE, null) ?: "DEFAULT") }
            .getOrDefault(NoteTextSize.DEFAULT),
    )
    val noteTextSize: StateFlow<NoteTextSize> = textSizeInternal.asStateFlow()

    private val strongReminderInternal = MutableStateFlow(prefs.getBoolean(KEY_STRONG_REMINDER, false))
    val strongReminder: StateFlow<Boolean> = strongReminderInternal.asStateFlow()

    private val sortInternal = MutableStateFlow(
        runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT, null) ?: "BY_UPDATED") }
            .getOrDefault(SortOrder.BY_UPDATED),
    )
    val sortOrder: StateFlow<SortOrder> = sortInternal.asStateFlow()

    private val themeInternal = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = themeInternal.asStateFlow()

    /** 从通知点开时记录待打开的目标 */
    var pendingOpenTarget: Pair<String, Long>? = null

    /** 分享进来的预填内容 */
    var pendingShare: NotePrefill? = null

    fun consumePendingShare(): NotePrefill? = pendingShare.also { pendingShare = null }

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            screen.collect { if (it is Screen.Home) refresh() }
        }
        viewModelScope.launch {
            DataChanges.events.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _notes.value = repo.loadNotes(_filter.value, sortInternal.value)
            _todos.value = repo.loadTodos()
            _folders.value = repo.loadFolders()
            _folderCounts.value = repo.folderCounts()
            _trashCount.value = repo.loadNotes(NoteFilter(trashed = true)).size
        }
    }

    // ---- 导航 ----

    fun switchTab(tab: MainTab) {
        _tab.value = tab
        if (tab == MainTab.TODO) {
            searchActiveInternal.value = false
            viewModelScope.launch { _todos.value = repo.loadTodos() }
        } else {
            refresh()
        }
    }

    fun openEditor(note: Note? = null, kind: NoteKind = NoteKind.TEXT) {
        _screen.value = if (note != null) {
            Screen.Editor(note.id, note.kind, note.folderId)
        } else {
            Screen.Editor(-1L, kind, _filter.value.folderId.takeIf { !searchActiveInternal.value })
        }
    }

    fun openEditorWithPrefill(prefill: NotePrefill, kind: NoteKind = NoteKind.TEXT) {
        _tab.value = MainTab.NOTES
        _screen.value = Screen.Editor(-1L, kind, _filter.value.folderId, prefill)
    }

    fun open(targetKind: String, targetId: Long) {
        viewModelScope.launch {
            when (targetKind) {
                Reminders.KIND_TODO -> {
                    _tab.value = MainTab.TODO
                    _screen.value = Screen.TodoEdit(targetId)
                }
                else -> repo.getNote(targetId)?.let { openEditor(it) }
            }
        }
    }

    fun openTodoEditor(todoId: Long) {
        _tab.value = MainTab.TODO
        _screen.value = Screen.TodoEdit(todoId)
    }

    fun openNewTodo() {
        _tab.value = MainTab.TODO
        _screen.value = Screen.TodoEdit(-1L)
    }

    fun deleteTodoById(id: Long) {
        viewModelScope.launch {
            val existed = repo.getTodo(id) != null
            if (existed) {
                repo.deleteTodoTree(id)
                Reminders.cancel(getApplication(), Reminders.KIND_TODO, id)
            }
            if (_screen.value is Screen.TodoEdit) _screen.value = Screen.Home
            refresh()
        }
    }

    fun consumePendingOpenTarget(): Pair<String, Long>? =
        pendingOpenTarget.also { pendingOpenTarget = null }

    fun goHome() {
        _screen.value = Screen.Home
        if (_filter.value.trashed || searchActiveInternal.value || _filter.value.query.isNotEmpty()) {
            _filter.value = NoteFilter(folderId = _filter.value.folderId.takeIf { _tab.value == MainTab.NOTES })
            searchActiveInternal.value = false
        }
        refresh()
    }

    fun goTrash() {
        _filter.value = NoteFilter(trashed = true)
        searchActiveInternal.value = false
        _screen.value = Screen.Trash
        refresh()
    }

    fun goFolders() {
        _screen.value = Screen.Folders
        refresh()
    }

    fun goSettings() {
        _screen.value = Screen.Settings
    }

    fun selectFolder(folderId: Long?) {
        _tab.value = MainTab.NOTES
        _filter.value = NoteFilter(folderId = folderId)
        _screen.value = Screen.Home
        refresh()
    }

    fun selectUnclassified() {
        _tab.value = MainTab.NOTES
        _filter.value = NoteFilter(unclassifiedOnly = true)
        _screen.value = Screen.Home
        refresh()
    }

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
        viewModelScope.launch { _notes.value = repo.loadNotes(_filter.value, sortInternal.value) }
    }

    fun setSearchActive(active: Boolean) {
        searchActiveInternal.value = active
        if (!active && _filter.value.query.isNotEmpty()) {
            _filter.value = _filter.value.copy(query = "")
            refresh()
        }
    }

    // ---- 布局/排序/主题 ----

    fun toggleGrid() {
        setGridMode(!gridInternal.value)
    }

    fun setGridMode(grid: Boolean) {
        gridInternal.value = grid
        prefs.edit().putBoolean(KEY_GRID, grid).apply()
    }

    fun setSortOrder(order: SortOrder) {
        sortInternal.value = order
        prefs.edit().putString(KEY_SORT, order.name).apply()
        refresh()
    }

    fun setThemeMode(mode: ThemeMode) {
        themeInternal.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setNoteTextSize(size: NoteTextSize) {
        textSizeInternal.value = size
        prefs.edit().putString(KEY_TEXT_SIZE, size.name).apply()
    }

    fun setStrongReminder(enabled: Boolean) {
        strongReminderInternal.value = enabled
        prefs.edit().putBoolean(KEY_STRONG_REMINDER, enabled).apply()
    }

    // ---- 笔记操作 ----

    fun getNoteOnce(noteId: Long, onLoaded: (Note?) -> Unit) {
        viewModelScope.launch { onLoaded(repo.getNote(noteId)) }
    }

    fun getTodoOnce(todoId: Long, onLoaded: (Todo?) -> Unit) {
        viewModelScope.launch { onLoaded(repo.getTodo(todoId)) }
    }

    fun createNote(
        kind: NoteKind,
        title: String,
        body: String,
        items: List<ChecklistItem>,
        images: List<String>,
        colorIndex: Int,
        folderId: Long?,
        remindAt: Long?,
        onDone: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = repo.createNote(kind, title, body, items, images, colorIndex, folderId)
            if (remindAt != null) {
                repo.setReminder(id, remindAt)
                Reminders.schedule(getApplication(), Reminders.KIND_NOTE, id, remindAt)
            }
            refresh()
            onDone(id)
        }
    }

    fun updateNote(
        noteId: Long,
        kind: NoteKind,
        title: String,
        body: String,
        items: List<ChecklistItem>,
        images: List<String>,
        colorIndex: Int,
        folderId: Long?,
        pinned: Boolean,
        remindAt: Long?,
    ) {
        viewModelScope.launch {
            repo.saveExisting(
                noteId, kind, title, body, items, images, colorIndex, folderId, pinned, remindAt,
            )
            if (remindAt == null) Reminders.cancel(getApplication(), Reminders.KIND_NOTE, noteId)
            else Reminders.schedule(getApplication(), Reminders.KIND_NOTE, noteId, remindAt)
            refresh()
        }
    }

    fun setNoteColor(note: Note, colorIndex: Int) {
        viewModelScope.launch {
            repo.setColor(note.id, colorIndex)
            refresh()
        }
    }

    fun togglePin(note: Note) {
        viewModelScope.launch {
            repo.setPinned(note.id, !note.pinned)
            refresh()
        }
    }

    fun moveToFolder(noteId: Long, folderId: Long?) {
        viewModelScope.launch {
            repo.moveToFolder(noteId, folderId)
            refresh()
        }
    }

    fun trash(noteId: Long) {
        viewModelScope.launch {
            repo.trashNote(noteId)
            Reminders.cancel(getApplication(), Reminders.KIND_NOTE, noteId)
            if (_screen.value is Screen.Editor) _screen.value = Screen.Home
            refresh()
        }
    }

    /** 多选模式批量删除 */
    fun trashNotes(ids: Collection<Long>) {
        viewModelScope.launch {
            ids.forEach { id ->
                repo.trashNote(id)
                Reminders.cancel(getApplication(), Reminders.KIND_NOTE, id)
            }
            refresh()
        }
    }

    /** 多选模式批量置顶/取消置顶 */
    fun setPinnedBatch(ids: Collection<Long>, pinned: Boolean) {
        viewModelScope.launch {
            ids.forEach { repo.setPinned(it, pinned) }
            refresh()
        }
    }

    /** 多选模式批量移动分类 */
    fun moveToFolderBatch(ids: Collection<Long>, folderId: Long?) {
        viewModelScope.launch {
            ids.forEach { repo.moveToFolder(it, folderId) }
            refresh()
        }
    }

    fun restore(note: Note) {
        viewModelScope.launch {
            repo.restoreNote(note.id)
            refresh()
        }
    }

    fun deleteForever(note: Note) {
        viewModelScope.launch {
            repo.deleteForever(note.id)
            refresh()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            repo.emptyTrash()
            refresh()
        }
    }

    fun setReminder(reminderTarget: Note, at: Long?) {
        viewModelScope.launch {
            repo.setReminder(reminderTarget.id, at)
            if (at == null) Reminders.cancel(getApplication(), Reminders.KIND_NOTE, reminderTarget.id)
            else Reminders.schedule(getApplication(), Reminders.KIND_NOTE, reminderTarget.id, at)
            refresh()
        }
    }

    // ---- 待办操作（小米式：单一提醒时间 + 重复规则 + 待办清单） ----

    fun createTodo(
        title: String,
        dueAt: Long?,
        allDay: Boolean,
        repeat: RepeatRule,
        subs: List<Pair<String, Boolean>>,
        onDone: (Long) -> Unit = {},
    ) {
        viewModelScope.launch {
            val id = repo.createTodo(null, title, dueAt, allDay, repeat.ordinal)
            val validSubs = subs.filter { it.first.isNotBlank() }
            if (validSubs.isNotEmpty()) repo.replaceSubs(id, validSubs)
            scheduleTodoAlarm(id, dueAt)
            refresh()
            onDone(id)
        }
    }

    fun updateTodo(id: Long, title: String, dueAt: Long?, allDay: Boolean, repeat: RepeatRule) {
        viewModelScope.launch {
            repo.updateTodo(id, title, dueAt, allDay, repeat.ordinal)
            scheduleTodoAlarm(id, dueAt)
            refresh()
        }
    }

    /** 用编辑器中的子任务列表整体替换（小米待办清单的回车连续添加） */
    fun saveTodoSubs(parentId: Long, subs: List<Pair<String, Boolean>>) {
        viewModelScope.launch {
            repo.replaceSubs(parentId, subs)
            refresh()
        }
    }

    fun addSubTodo(parentId: Long, title: String) {
        viewModelScope.launch {
            repo.createTodo(parentId, title, null, false, 0)
            refresh()
        }
    }

    fun toggleTodo(todo: Todo) {
        viewModelScope.launch {
            if (!todo.done && todo.repeat != RepeatRule.NONE && todo.dueAt != null && !todo.isSubtask) {
                // 重复待办：完成后推进到下一次到期，保持未完成
                TodoDates.nextOccurrence(todo.dueAt, todo.repeat)?.let { next ->
                    repo.rescheduleRepeat(todo.id, next, todo.allDay)
                    scheduleTodoAlarm(todo.id, next)
                    refresh()
                    return@launch
                }
            }
            val markingDone = !todo.done
            repo.setTodoDone(todo, markingDone)
            if (markingDone) {
                Reminders.cancel(getApplication(), Reminders.KIND_TODO, todo.id)
            } else {
                scheduleTodoAlarm(todo.id, todo.dueAt)
            }
            // 小米行为：最后一个子待办完成 → 整个待办清单完成
            if (markingDone && todo.isSubtask) {
                val all = repo.loadTodos()
                val siblings = all.filter { it.parentId == todo.parentId }
                if (siblings.isNotEmpty() && siblings.all { it.done }) {
                    all.firstOrNull { it.id == todo.parentId }?.let { parent ->
                        repo.setTodoDone(parent, true)
                        Reminders.cancel(getApplication(), Reminders.KIND_TODO, parent.id)
                    }
                }
            }
            refresh()
        }
    }

    fun deleteTodo(todo: Todo) {
        viewModelScope.launch {
            repo.deleteTodoTree(todo.id)
            Reminders.cancel(getApplication(), Reminders.KIND_TODO, todo.id)
            if (_screen.value is Screen.TodoEdit) _screen.value = Screen.Home
            refresh()
        }
    }

    fun clearDone() {
        viewModelScope.launch {
            repo.clearDoneTodos()
            refresh()
        }
    }

    private fun scheduleTodoAlarm(id: Long, dueAt: Long?) {
        if (dueAt != null && dueAt > System.currentTimeMillis()) {
            Reminders.schedule(getApplication(), Reminders.KIND_TODO, id, dueAt)
        } else {
            Reminders.cancel(getApplication(), Reminders.KIND_TODO, id)
        }
    }

    // ---- 分类 ----

    fun createFolder(name: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = name.isNotBlank() && repo.createFolder(name) != null
            refresh()
            onDone(ok)
        }
    }

    fun renameFolder(folder: Folder, newName: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = newName.isNotBlank() && repo.renameFolder(folder.id, newName) > 0
            refresh()
            onDone(ok)
        }
    }

    fun deleteFolder(folder: Folder) {
        viewModelScope.launch {
            repo.deleteFolder(folder.id)
            if (_filter.value.folderId == folder.id) {
                _filter.value = NoteFilter()
            }
            refresh()
        }
    }

    private companion object {
        const val KEY_GRID = "layout_grid"
        const val KEY_THEME = "theme_mode"
        const val KEY_SORT = "sort_order"
        const val KEY_TEXT_SIZE = "note_text_size"
        const val KEY_STRONG_REMINDER = "strong_reminder"
    }
}
