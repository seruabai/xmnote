package com.purenote.local

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purenote.local.backup.BackupFile
import com.purenote.local.backup.BackupFormatException
import com.purenote.local.backup.BackupIo
import com.purenote.local.data.ChecklistItem
import com.purenote.local.data.DataChanges
import com.purenote.local.data.Folder
import com.purenote.local.data.Note
import com.purenote.local.data.NoteFilter
import com.purenote.local.data.NoteKind
import com.purenote.local.data.NotePrefill
import com.purenote.local.data.NoteRepository
import com.purenote.local.data.RepeatRule
import com.purenote.local.data.SaveResult
import com.purenote.local.data.SortOrder
import com.purenote.local.data.StorageFailure
import com.purenote.local.data.Todo
import com.purenote.local.core.RichDoc
import com.purenote.local.core.TodoDates
import com.purenote.local.feature.mind.MindDoc
import com.purenote.local.feature.notes.EditorEvent
import com.purenote.local.feature.notes.EditorReducer
import com.purenote.local.feature.notes.EditorState
import com.purenote.local.feature.notes.SaveCommand
import com.purenote.local.feature.notes.SaveCoordinator
import com.purenote.local.notify.AndroidAlarmSink
import com.purenote.local.notify.ReminderReconciler
import com.purenote.local.notify.Reminders
import com.purenote.local.sync.AndroidCredentialStore
import com.purenote.local.sync.AndroidNetworkStatus
import com.purenote.local.sync.BackupSyncEngine
import com.purenote.local.sync.CredentialStore
import com.purenote.local.sync.PrefsSyncSettingsStore
import com.purenote.local.sync.SyncSettingsStore
import com.purenote.local.sync.WebDavConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipException

/**
 * 存储代次（规范 §5.1）：阶段 F 会从 library_meta 读取，并在切换/恢复活动存储时更换。
 * 当前是单存储实现，取值稳定即可；它的作用是拒绝"切换之后才到达的旧写入"。
 */
// 存储代次从仓库的活动指针读取（阶段 F）：恢复/s切换后它会变化，
// 旧写入据此被拒绝，而不是写进新库。

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
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 小米笔记设置页中的三档正文尺寸。 */
enum class NoteTextSize { SMALL, DEFAULT, LARGE }

enum class MainTab { NOTES, TODO }

/** 设置页备份任务的状态，UI 据此禁用按钮并弹结果。 */
sealed interface BackupState {
    data object Idle : BackupState
    data object Running : BackupState
    data class Done(val title: String, val summary: String, val warnings: List<String> = emptyList()) : BackupState
    data class Failed(val message: String) : BackupState
}

/**
 * 云同步（H 阶段）在界面上要显示的东西。
 *
 * 刻意只暴露界面真正会画的三件事：开关、远端摘要（账号/服务器/上次成功时间）、一次运行的结果。
 * 不做"同步进度百分比"——WebDAV 的 PUT 没有可靠的回执进度，
 * 编一个百分比出来是在骗用户（与"自动备份只显示实际成功时间"同一条原则）。
 */
sealed interface CloudState {
    data object Idle : CloudState
    data class Running(val text: String) : CloudState
    data class Done(val title: String, val summary: String, val warnings: List<String> = emptyList()) : CloudState
    data class Failed(val message: String) : CloudState
}

/** 已保存的云同步配置（密码只回显账号，不显示密码本身）。 */
data class CloudConfigState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val remoteDir: String = WebDavConfig.DEFAULT_DIR,
    val account: String = "",
    val hasPassword: Boolean = false,
    val lastSuccessAt: Long = 0L,
    val lastBytes: Long = 0L,
    val lastNoteCount: Int = 0,
    /** 当前网络是否可用（用于在按钮上给出可读提示，而不是等超时） */
    val online: Boolean = true,
)

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

    private val _trashedTodos = MutableStateFlow<List<Todo>>(emptyList())
    val trashedTodos: StateFlow<List<Todo>> = _trashedTodos.asStateFlow()

    /**
     * 待办底部弹窗编辑器的目标：null = 关闭，-1 = 新建，其余为待编辑的待办 id。
     * 新建与编辑统一走弹窗，不再有全屏编辑页。
     * focusSubId 用于点子行直接定位到该行末尾，避免每次都落在第一行行首。
     */
    private val _todoSheetId = MutableStateFlow<Long?>(null)
    val todoSheetId: StateFlow<Long?> = _todoSheetId.asStateFlow()

    private val _todoSheetFocusSubId = MutableStateFlow<Long?>(null)
    val todoSheetFocusSubId: StateFlow<Long?> = _todoSheetFocusSubId.asStateFlow()

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

    /**
     * 刷新期间若有新的刷新请求，取消旧的那次，只让最后一次的结果落地。
     * 原来每次调用各起一个协程并发写 6 个 StateFlow，两次快速操作（如连续改颜色/置顶）
     * 可能后发先至，用旧数据覆盖新结果，表现为「偶尔显示不对，重进又好了」。
     */
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val notes = repo.loadNotes(_filter.value, sortInternal.value)
            val todos = repo.loadTodos()
            val trashedTodos = repo.loadTrashedTodos()
            val folders = repo.loadFolders()
            val folderCounts = repo.folderCounts()
            val trashCount = repo.loadNotes(NoteFilter(trashed = true)).size + repo.trashedTodoCount()
            // 全部读完后一起提交，取消发生在读取阶段时不会留下半套数据
            if (!isActive) return@launch
            _notes.value = notes
            _todos.value = todos
            _trashedTodos.value = trashedTodos
            _folders.value = folders
            _folderCounts.value = folderCounts
            _trashCount.value = trashCount
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
                    val todo = repo.getTodo(targetId)
                    if (todo == null) {
                        goHome()
                    } else if (todo.trashed) {
                        goTrash()
                    } else {
                        _tab.value = MainTab.TODO
                        _screen.value = Screen.Home
                        _todoSheetId.value = targetId
                    }
                }
                else -> repo.getNote(targetId)?.let { openEditor(it) }
            }
        }
    }

    /** 打开待办底部弹窗：todoId = -1 表示新建；focusSubId 指定 initially 聚焦的子行 */
    fun openTodoSheet(todoId: Long, focusSubId: Long? = null) {
        _tab.value = MainTab.TODO
        if (_screen.value != Screen.Home) _screen.value = Screen.Home
        _todoSheetFocusSubId.value = focusSubId
        _todoSheetId.value = todoId
    }

    fun closeTodoSheet() {
        _todoSheetId.value = null
        _todoSheetFocusSubId.value = null
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

    /**
     * 搜索防抖：连续输入时只查最后一次。
     * 搜索走 LIKE 全表扫描，笔记多时逐字查会明显拖慢输入。
     */
    private var searchJob: Job? = null

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _notes.value = repo.loadNotes(_filter.value, sortInternal.value)
        }
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
        doc: RichDoc,
        items: List<ChecklistItem>,
        images: List<String>,
        colorIndex: Int,
        folderId: Long?,
        remindAt: Long?,
        repeat: RepeatRule = RepeatRule.NONE,
        allDay: Boolean = false,
        mind: MindDoc? = null,
        onDone: (Long) -> Unit,
    ) {
        lastSaveJob = viewModelScope.launch {
            // insertOrThrow：创建失败会抛异常，绝不能把 -1 当成有效 ID 继续用（规范 §7）
            val id = try {
                repo.createNote(kind, title, doc, items, images, colorIndex, folderId, mind)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                reduceEditor(EditorEvent.SaveFailed(describe(StorageFailure.of(t))))
                refresh()
                return@launch
            }
            // 新建成功后把会话基线重置到 revision=1：
            // 否则编辑器仍以为基线是 0，下一次保存的 CAS 必然冲突（规范 §7/§8）。
            reduceEditor(EditorEvent.Loaded(content = "", revision = 1L))
            if (remindAt != null) {
                repo.setReminder(id, remindAt, repeat, allDay)
                // 规范 §9：期望已在 setReminder 的事务里落库，这里只把它推给平台
                reconciler.applyPending()
            }
            refresh()
            onDone(id)
        }
    }

    /**
     * 编辑器会话状态（规范 §8）。界面据此显示 待保存 / 保存中 / 保存失败 / 冲突，
     * 并据此决定下一次 CAS 的 expectedRevision。
     */
    private val _editorState = MutableStateFlow(EditorReducer.initial("", ""))
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    /** 同一条笔记最多一个写入在途（规范 §8） */
    private val saveCoordinator = SaveCoordinator.forRepository(repo)

    /** 提醒协调器（规范 §9）：数据提交与系统提醒分离 */
    private val reconciler = ReminderReconciler(repo, AndroidAlarmSink(getApplication()))

    /** 最近一次提交的任务，供 flushAndAwait 使用（规范 §8：主动返回前等待提交完成） */
    private var lastSaveJob: Job? = null

    /**
     * 规范 §8：主动返回时执行 flushAndAwait——等待最新编辑代次真正提交后再离开。
     * 不做这一步就会出现"点了返回、界面已经退出、内容其实没落库"。
     * 系统杀进程不保证执行该流程（规范已声明）。
     */
    suspend fun awaitPendingSaves() {
        lastSaveJob?.join()
    }

    private fun reduceEditor(event: EditorEvent) {
        _editorState.update { EditorReducer.reduce(it, event) }
    }

    /**
     * 进入编辑器时建立会话基线：读取当前修订号作为 expectedRevision。
     * 规范 §8：只有同一会话自己已确认的连续提交，才能推进下一次命令的预期修订。
     */
    fun beginEditorSession(noteId: Long) {
        viewModelScope.launch {
            val sessionId = java.util.UUID.randomUUID().toString().replace("-", "")
            val base = EditorReducer.initial(sessionId = sessionId, storeEpoch = repo.storeEpoch)
            val revision = if (noteId > 0) repo.noteRevision(noteId) else null
            _editorState.value = if (noteId > 0 && revision == null) {
                EditorReducer.reduce(base, EditorEvent.LoadFailed)
            } else {
                EditorReducer.reduce(base, EditorEvent.Loaded(content = "", revision = revision ?: 0L))
            }
        }
    }

    /** 用户改了内容：代次 +1，界面转为"待保存" */
    fun markEditorEdited() {
        reduceEditor(EditorEvent.Edited(_editorState.value.content))
    }

    fun updateNote(
        noteId: Long,
        kind: NoteKind,
        title: String,
        doc: RichDoc,
        items: List<ChecklistItem>,
        images: List<String>,
        colorIndex: Int,
        folderId: Long?,
        pinned: Boolean,
        remindAt: Long?,
        repeat: RepeatRule = RepeatRule.NONE,
        allDay: Boolean = false,
        mind: MindDoc? = null,
    ) {
        val state = _editorState.value
        // 记录已不存在 / 加载失败：不写入，也不要把内容伪装成已保存（规范 §8）
        if (state.readOnly) return
        lastSaveJob = viewModelScope.launch {
            // 规范 §8：每次提交携带**编辑器自己确认过的**修订号作为 expectedRevision，
            // 这样并发覆盖才会被真正检测到（而不是每次读一个最新值再盲写）。
            // 会话基线可能还没建立（beginEditorSession 是异步读修订号的）。
            // 此时绝不能静默丢弃这一笔——那正是"点了返回，内容没存"的来源。
            // 回退为读取当前修订号（阶段 C 的过渡行为）；基线就绪后一律使用
            // 编辑器自己确认过的 committedRevision，CAS 才有意义。
            val expectedRevision = if (state.loaded) {
                state.committedRevision
            } else {
                repo.noteRevision(noteId) ?: return@launch
            }
            val command = SaveCommand(
                noteId = noteId,
                operationId = java.util.UUID.randomUUID().toString().replace("-", ""),
                sessionId = state.sessionId,
                storeEpoch = state.storeEpoch,
                editGeneration = state.editGeneration,
                expectedRevision = expectedRevision,
                kind = kind, title = title, doc = doc, mind = mind, items = items, images = images,
                colorIndex = colorIndex, folderId = folderId, pinned = pinned,
                remindAt = remindAt, repeat = repeat, allDay = allDay,
            )
            reduceEditor(EditorEvent.SaveStarted(command.editGeneration))
            when (val result = saveCoordinator.save(command)) {
                is SaveResult.Saved -> {
                    reduceEditor(EditorEvent.SaveSucceeded(command.editGeneration, result.revision))
                    // 规范 §9：期望的提醒状态已经在**保存事务里**落库，这里只负责把它推给平台。
                    // 协调器会逐个核对修订号，过期期望不会覆盖新提醒。
                    reconciler.applyPending()
                }
                is SaveResult.Conflict -> reduceEditor(EditorEvent.SaveConflicted(result.actualRevision))
                SaveResult.StoreChanged -> reduceEditor(
                    EditorEvent.SaveFailed("存储已切换（可能刚完成恢复），本次内容未写入"),
                )
                SaveResult.NotFound -> reduceEditor(EditorEvent.SaveFailed("笔记已不存在"))
                is SaveResult.Failed -> reduceEditor(EditorEvent.SaveFailed(describe(result.reason)))
            }
            refresh()
        }
    }

    private fun describe(reason: StorageFailure): String = when (reason) {
        StorageFailure.CONFLICT -> "这条笔记已在别处被修改"
        StorageFailure.CORRUPTED -> "数据库处于保全状态，已停止写入"
        StorageFailure.NO_SPACE -> "存储空间不足"
        StorageFailure.LOCKED -> "数据库被占用"
        StorageFailure.PERMISSION -> "没有写入权限"
        StorageFailure.UNKNOWN_RESULT -> "保存结果未知"
        StorageFailure.UNKNOWN -> "保存失败"
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
            repo.setReminder(reminderTarget.id, at, reminderTarget.repeat, reminderTarget.allDay)
            // 规范 §9：at == null 也会落一条"期望无提醒"，由协调器统一取消，不在这里分叉判断
            reconciler.applyPending()
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
            applyTodoReminders()
            refresh()
            onDone(id)
        }
    }

    fun updateTodo(id: Long, title: String, dueAt: Long?, allDay: Boolean, repeat: RepeatRule) {
        viewModelScope.launch {
            repo.updateTodo(id, title, dueAt, allDay, repeat.ordinal)
            applyTodoReminders()
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
                    applyTodoReminders()
                    refresh()
                    return@launch
                }
            }
            val markingDone = !todo.done
            repo.setTodoDone(todo, markingDone)
            if (markingDone) {
                Reminders.cancel(getApplication(), Reminders.KIND_TODO, todo.id)
            } else {
                applyTodoReminders()
            }
            // 父项状态已由仓库按全部子项统一重算；这里同步父项提醒状态。
            if (todo.isSubtask) {
                todo.parentId?.let { parentId ->
                    repo.getTodo(parentId)?.let { parent ->
                        if (parent.done) {
                            Reminders.cancel(getApplication(), Reminders.KIND_TODO, parent.id)
                        } else {
                            applyTodoReminders()
                        }
                    }
                }
            }
            refresh()
        }
    }

    /** 待办删除进废纸篓（整树，含子项）；子项的小×走 deleteTodoForever 直接移除 */
    fun deleteTodo(todo: Todo) {
        viewModelScope.launch {
            if (!todo.trashed) {
                repo.trashTodoTree(todo.id).forEach { trashedId ->
                    Reminders.cancel(getApplication(), Reminders.KIND_TODO, trashedId)
                }
            }
            refresh()
        }
    }

    /** 彻底删除（废纸篓内操作，或行内子项移除，不可恢复） */
    fun deleteTodoForever(todo: Todo) {
        viewModelScope.launch {
            repo.deleteTodoTree(todo.id)
            Reminders.cancel(getApplication(), Reminders.KIND_TODO, todo.id)
            refresh()
        }
    }

    fun restoreTodo(todo: Todo) {
        viewModelScope.launch {
            repo.restoreTodoTree(todo.id)
            repo.getTodo(todo.id)?.let { restored ->
                if (!restored.done) applyTodoReminders()
            }
            refresh()
        }
    }

    fun emptyTodoTrash() {
        viewModelScope.launch {
            repo.emptyTodoTrash()
            refresh()
        }
    }

    fun clearDone() {
        viewModelScope.launch {
            repo.trashCompletedTodos().forEach { trashedId ->
                Reminders.cancel(getApplication(), Reminders.KIND_TODO, trashedId)
            }
            refresh()
        }
    }

    /** 按展示顺序持久化顶层待办排序（长按多选模式下拖动手柄排序用） */
    fun reorderTodos(orderedIds: List<Long>) {
        viewModelScope.launch {
            repo.reorderTodos(orderedIds)
            _todos.value = repo.loadTodos()
        }
    }

    /** 批量删除待办（长按多选底部删除键用，走废纸篓整树） */
    fun deleteTodos(todos: Collection<Todo>) {
        viewModelScope.launch {
            todos.forEach { todo ->
                if (!todo.trashed) {
                    repo.trashTodoTree(todo.id).forEach { trashedId ->
                        Reminders.cancel(getApplication(), Reminders.KIND_TODO, trashedId)
                    }
                }
            }
            refresh()
        }
    }

    /** 按 id 设置完成态（弹窗新建单条待办时勾选了完成态用），内部复用 toggle 的提醒同步 */
    fun setTodoDoneById(id: Long, done: Boolean) {
        viewModelScope.launch {
            repo.getTodo(id)?.let { todo ->
                if (todo.done != done) toggleTodo(todo)
            }
        }
    }

    /**
     * 规范 §9：待办的提醒期望已经在仓库的 createTodo / updateTodo / setTodoDone
     * **事务里**登记好了，这里只负责把它推给平台。
     *
     * 原来这里自己判断"时间还没到就 schedule、否则 cancel"——那是把期望直接算在了
     * ViewModel 里。现在期望以数据库为准，协调器逐个核对修订号，
     * 过期期望不会覆盖新提醒（"旧任务不能覆盖新提醒"）。
     */
    private fun applyTodoReminders() {
        viewModelScope.launch { reconciler.applyPending() }
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

    // ---- 备份 / 恢复 ----
    //
    // SAF 的 Uri 不能当 File 用，导出先落 cacheDir 临时文件再经 contentResolver 拷过去。
    // InputStream 只能消费一次，预览与导入各自重新 openInputStream。

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    fun dismissBackupResult() {
        if (_backupState.value !is BackupState.Running) _backupState.value = BackupState.Idle
    }

    fun exportBackup(uri: Uri) {
        if (!backupStartable()) return
        viewModelScope.launch {
            _backupState.value = BackupState.Running
            val ctx = getApplication<Application>()
            val tmp = File(ctx.cacheDir, "purenote-backup-export-${System.currentTimeMillis()}.${BackupIo.EXTENSION}")
            try {
                _backupState.value = withContext(Dispatchers.IO) {
                    val result = repo.exportBackup(tmp, packageVersion())
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("无法写入所选位置")

                    // 规范 §11.3：SAF 提供者不保证原子 rename、可靠 fsync 或立即可读回。
                    // 写完后必须重新打开并核对字节；核不上就如实说"尚未验证"，
                    // 绝不能因为"写调用成功了"就把这份外部副本当成有效备份
                    // （它可能是下一页就被覆盖的旧有效副本的替代品）。
                    val unverified = verifyExternalCopy(ctx, uri, tmp)
                    BackupState.Done(
                        title = if (unverified == null) "导出完成" else "导出完成（尚未验证）",
                        summary = result.summary +
                            if (result.missingAttachments > 0) {
                                "。有 ${result.missingAttachments} 个附件文件缺失，未能打包"
                            } else {
                                ""
                            } +
                            if (unverified != null) "。$unverified" else "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _backupState.value = BackupState.Failed("导出失败：${backupFailureReason(e)}")
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * 读回外部副本并核对大小与 SHA-256。
     * @return null 表示已核实；否则返回给用户看的"尚未验证"说明。
     */
    private fun verifyExternalCopy(ctx: Context, uri: Uri, tmp: File): String? = try {
        val expectedSize = tmp.length()
        val readBackSize = ctx.contentResolver.openInputStream(uri)?.use { countBytes(it) }
        when {
            readBackSize == null -> "已写出，但无法读取回来验证"
            readBackSize != expectedSize ->
                "已写出，但读回大小不符（写出 $expectedSize，读回 $readBackSize）"
            else -> {
                val written = tmp.inputStream().use { sha256Of(it) }
                val readBack = ctx.contentResolver.openInputStream(uri)?.use { sha256Of(it) }
                if (readBack != null && readBack == written) null else "已写出，但读回校验值不符"
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "已写出，但验证失败：${e.message ?: e::class.java.simpleName}"
    }

    private fun countBytes(input: java.io.InputStream): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun sha256Of(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 只解析备份内容供 UI 弹确认框，不写库。 */
    fun previewBackup(uri: Uri, onReady: (BackupFile) -> Unit) {
        if (!backupStartable()) return
        viewModelScope.launch {
            _backupState.value = BackupState.Running
            val ctx = getApplication<Application>()
            try {
                val backup = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { repo.readBackup(it) }
                        ?: throw IllegalStateException("无法读取所选文件")
                }
                _backupState.value = BackupState.Idle
                onReady(backup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _backupState.value = BackupState.Failed("读取备份失败：${backupFailureReason(e)}")
            }
        }
    }

    /**
     * 完整恢复（规范 §12）：建新一代存储 -> 导入 -> 验证 -> 原子切换活动指针。
     * 与 [importBackup] 的区别：导入是**合并**进当前库，恢复是**换一代**整体替换，
     * 旧代文件与附件一律保留，任何中断都不会损坏当前可用的那一代。
     */
    fun restoreBackup(uri: Uri) {
        if (!backupStartable()) return
        viewModelScope.launch {
            _backupState.value = BackupState.Running
            val ctx = getApplication<Application>()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { repo.restoreIntoNewStore(it) }
                        ?: NoteRepository.RestoreOutcome.Failed("无法读取所选文件（授权可能已失效）")
                }
                _backupState.value = when (outcome) {
                    is NoteRepository.RestoreOutcome.Ok -> {
                        refresh()
                        BackupState.Done(
                            title = "恢复完成",
                            summary = "已切换到新的存储代次（新增 ${outcome.inserted}、更新 ${outcome.updated}）。" +
                                "旧数据仍完整保留，可继续用于排查。",
                            warnings = outcome.warnings,
                        )
                    }
                    is NoteRepository.RestoreOutcome.Failed ->
                        BackupState.Failed("恢复失败：${outcome.message}。当前数据未被改动。")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _backupState.value = BackupState.Failed("恢复失败：${backupFailureReason(e)}。当前数据未被改动。")
            }
        }
    }

    // ---- 云同步（规范 §17 阶段 H） ----
    //
    // 界面只发三个动作：保存配置 / 测试连接 / 立即同步。
    // 引擎负责顺序与校验，这里只做状态搬运和把异常翻成人话。

    private val _cloudState = MutableStateFlow<CloudState>(CloudState.Idle)
    val cloudState: StateFlow<CloudState> = _cloudState.asStateFlow()

    private val _cloudConfig = MutableStateFlow(CloudConfigState())
    val cloudConfig: StateFlow<CloudConfigState> = _cloudConfig.asStateFlow()

    /** 载入已保存的云同步配置（设置页展开时调用一次即可）。 */
    fun loadCloudConfig() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val settings = PrefsSyncSettingsStore(ctx)
            val remote = settings.loadRemote()
            val creds = AndroidCredentialStore(ctx).load()
            val net = AndroidNetworkStatus(ctx)
            val meta = settings.loadMeta()
            _cloudConfig.value = CloudConfigState(
                available = remote != null && creds != null,
                enabled = remote?.enabled ?: false,
                serverUrl = remote?.serverUrl.orEmpty(),
                remoteDir = remote?.remoteDir ?: WebDavConfig.DEFAULT_DIR,
                account = creds?.account.orEmpty(),
                hasPassword = creds != null,
                lastSuccessAt = meta.lastSuccessAt,
                lastBytes = meta.lastBytes,
                lastNoteCount = meta.lastNoteCount,
                online = net.isOnline(),
            )
        }
    }

    /**
     * 保存配置。密码单独走 Keystore；保存失败必须如实告知，不能静默降级为明文。
     * @param testAfterSave 保存后立即测一次连接
     */
    fun saveCloudConfig(
        serverUrl: String,
        remoteDir: String,
        account: String,
        password: String,
        enabled: Boolean,
        testAfterSave: Boolean = false,
    ) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val creds = AndroidCredentialStore(ctx)
            val previous = creds.load()
            val accountChanged = previous?.account != account
            val keepPassword = password.isEmpty()

            val effective = if (keepPassword && previous != null && !accountChanged) {
                previous
            } else {
                CredentialStore.Credentials(account, password)
            }
            if (effective.account.isBlank() || effective.appPassword.isBlank()) {
                _cloudState.value = CloudState.Failed("请同时填写账号与应用密码（只填一项无法连接）")
                return@launch
            }

            val saved = creds.save(effective)
            if (!saved) {
                _cloudState.value = CloudState.Failed(
                    "无法安全保存密码（Keystore 不可用）。为避免把密码明文写进设备，本次未保存。",
                )
                return@launch
            }

            PrefsSyncSettingsStore(ctx).saveRemote(
                SyncSettingsStore.RemoteConfig(
                    serverUrl = serverUrl.trim(),
                    remoteDir = remoteDir.trim().ifEmpty { WebDavConfig.DEFAULT_DIR },
                    enabled = enabled,
                ),
            )
            loadCloudConfig()
            // 账号或密码变了，上一次的"连接可用"结论就作废，逼一次重新测试
            if (accountChanged || !keepPassword) _cloudState.value = CloudState.Idle
            if (testAfterSave) testCloudConnection() else {
                if (_cloudState.value is CloudState.Idle) toastCloud("已保存云同步设置")
            }
        }
    }

    fun setCloudEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val settings = PrefsSyncSettingsStore(ctx)
            val remote = settings.loadRemote()
            if (remote == null) {
                _cloudState.value = CloudState.Failed("请先填写服务器地址、账号与应用密码")
                loadCloudConfig()
                return@launch
            }
            settings.saveRemote(remote.copy(enabled = enabled))
            loadCloudConfig()
            if (enabled) {
                _cloudState.value = CloudState.Idle
                toastCloud("已开启云同步（只在你点击「立即同步」时才会上传）")
            }
        }
    }

    fun testCloudConnection() {
        if (_cloudState.value is CloudState.Running) return
        viewModelScope.launch {
            _cloudState.value = CloudState.Running("正在连接服务器…")
            _cloudState.value = repo.cloudTestConnection().fold(
                onSuccess = { count ->
                    CloudState.Done("连接成功", "远端目录可访问，现有 $count 个文件。")
                },
                onFailure = { CloudState.Failed(cloudReason(it)) },
            )
        }
    }

    /** 立即同步：导出 -> 上传 -> 读回校验 -> 只有校验通过才算成功。 */
    fun syncCloudNow(allowIncomplete: Boolean = false) {
        if (_cloudState.value is CloudState.Running) return
        viewModelScope.launch {
            val result = repo.cloudSync(allowIncomplete) { engineState ->
                if (engineState is BackupSyncEngine.State.Running) {
                    _cloudState.value = CloudState.Running(engineState.detail.ifBlank { "正在同步…" })
                }
            }
            result.fold(
                onSuccess = { outcome ->
                    _cloudState.value = CloudState.Done(
                        title = "云端备份完成",
                        summary = outcome.remotePath + "（" + (outcome.bytes / 1024) + " KB，笔记 " +
                            outcome.noteCount + " 条，已读回校验）",
                        warnings = outcome.warnings,
                    )
                    loadCloudConfig()
                },
                onFailure = { e ->
                    _cloudState.value = CloudState.Failed(cloudReason(e))
                    loadCloudConfig()
                },
            )
        }
    }

    fun clearCloudConfig() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            AndroidCredentialStore(ctx).clear()
            PrefsSyncSettingsStore(ctx).clearRemote()
            _cloudState.value = CloudState.Idle
            loadCloudConfig()
            toastCloud("已清除云同步配置与凭据")
        }
    }

    fun dismissCloudResult() {
        if (_cloudState.value !is CloudState.Running) _cloudState.value = CloudState.Idle
    }

    private fun cloudReason(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName

    private fun toastCloud(text: String) {
        android.widget.Toast.makeText(getApplication(), text, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun importBackup(uri: Uri) {
        if (!backupStartable()) return
        viewModelScope.launch {
            _backupState.value = BackupState.Running
            val ctx = getApplication<Application>()
            try {
                val result = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { repo.importBackup(it) }
                        ?: throw IllegalStateException("无法读取所选文件")
                }
                _backupState.value = BackupState.Done(
                    title = "导入完成",
                    summary = result.summary,
                    warnings = result.warnings,
                )
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _backupState.value = BackupState.Failed("导入失败：${backupFailureReason(e)}")
            }
        }
    }

    private fun backupStartable(): Boolean = _backupState.value !is BackupState.Running

    /** 项目已关 buildConfig，版本号从 PackageManager 取 */
    private fun packageVersion(): String {
        val app = getApplication<Application>()
        val pm = app.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: ""
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, 0).versionName ?: ""
        }
    }

    /** 把底层异常翻成用户能读懂的原因，前缀（导出失败/导入失败）由调用方拼。 */
    private fun backupFailureReason(e: Exception): String = when (e) {
        is BackupFormatException -> e.message ?: "备份包格式不正确"
        is ZipException -> "所选文件不是有效的备份包（.${BackupIo.EXTENSION}）"
        is FileNotFoundException -> "无法读取所选文件"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
    }

    private companion object {
        const val KEY_GRID = "layout_grid"
        const val KEY_THEME = "theme_mode"
        const val KEY_SORT = "sort_order"
        const val KEY_TEXT_SIZE = "note_text_size"
        const val KEY_STRONG_REMINDER = "strong_reminder"
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
