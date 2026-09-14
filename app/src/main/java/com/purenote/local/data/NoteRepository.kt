package com.purenote.local.data

import android.content.Context
import android.database.Cursor
import com.purenote.local.backup.BackupFile
import com.purenote.local.backup.BackupIo
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.core.TodoCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** [dbName] 仅测试需要（用独立库文件，避免污染真实数据）；应用内一律用默认库名。 */
class NoteRepository(context: Context, dbName: String = NotesDb.DB_NAME) {

    private val appContext = context.applicationContext
    private val db = NotesDb(appContext, dbName)

    /** 统一事务入口（规范 §6.1）。多步写操作必须整体提交或整体回滚。 */
    private val tx = DatabaseExecutor(db)

    // ---- notes ----

    suspend fun loadNotes(filter: NoteFilter, order: SortOrder = SortOrder.BY_UPDATED): List<Note> =
        withContext(Dispatchers.IO) {
            val selection = StringBuilder("trashed = ?")
            val args = mutableListOf(if (filter.trashed) "1" else "0")
            if (!filter.trashed && filter.folderId != null) {
                selection.append(" AND folder_id = ?")
                args += filter.folderId.toString()
            } else if (!filter.trashed && filter.unclassifiedOnly) {
                selection.append(" AND folder_id IS NULL")
            }
            filter.query.trim().takeIf { it.isNotEmpty() }?.let { q ->
                selection.append(" AND (title LIKE ? OR body LIKE ?)")
                val like = "%$q%"
                args += like
                args += like
            }
            val notes = mutableListOf<Note>()
            db.readableDatabase.rawQuery(
                "SELECT * FROM notes WHERE $selection ORDER BY updated_at DESC",
                args.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) notes += c.toNote()
            }
            if (filter.trashed) {
                notes.sortedByDescending { it.trashedAt ?: 0L }
            } else {
                notes.sortedForHome(order)
            }
        }

    suspend fun getNote(id: Long): Note? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT * FROM notes WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.toNote() else null }
    }

    suspend fun createNote(
        kind: NoteKind,
        title: String,
        body: String,
        items: List<ChecklistItem>,
        images: List<String>,
        colorIndex: Int,
        folderId: Long?,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.insertNote(kind, title, encodeBody(kind, body, items), images.joinToString("\n"), colorIndex, folderId, now)
    }

    suspend fun saveExisting(
        id: Long,
        kind: NoteKind,
        title: String,
        body: String,
        items: List<ChecklistItem>,
        images: List<String>,
        colorIndex: Int,
        folderId: Long?,
        pinned: Boolean,
        remindAt: Long?,
        repeat: RepeatRule = RepeatRule.NONE,
        allDay: Boolean = false,
        /**
         * 幂等键：调用方重试时复用同一个 ID，重复提交不会产生第二次修改（规范 §7）。
         * 阶段性：阶段 D 的 SaveCoordinator 会把它与编辑会话绑定。
         */
        operationId: String = java.util.UUID.randomUUID().toString().replace("-", ""),
        /**
         * 预期修订号。为 null 时在事务内读取当前值（阶段 C 的过渡行为）；
         * 阶段 D 的编辑器会传入自己确认过的修订号，从而真正检测并发覆盖。
         */
        expectedRevision: Long? = null,
    ): SaveResult = try {
        tx.write { database ->
            val now = System.currentTimeMillis()
            val current = NoteStore.readRevision(id, database) ?: return@write SaveResult.NotFound
            val encodedBody = encodeBody(kind, body, items)
            val encodedImages = images.joinToString("\n")
            val base = expectedRevision ?: current

            // 请求身份 = (operationId, noteId, 调用方声明的预期修订, 内容)。
            // **不能把 base 放进来**：expectedRevision 为 null 时 base 取自库中当前值，
            // 第一次提交后 base 就变了，重试会算出不同哈希，幂等判定随之失效。
            val hash = NoteStore.requestHash(
                "note.save", id, expectedRevision ?: "-",
                kind.ordinal, title, encodedBody, encodedImages,
                colorIndex, folderId, pinned, remindAt, repeat.ordinal, allDay,
            )
            // 幂等：同 operationId 且请求一致 → 返回原回执，不重复写
            NoteStore.findOperation(operationId, hash, database)?.let { record ->
                return@write SaveResult.Saved(id, record.resultRevision)
            }

            val values = android.content.ContentValues().apply {
                put("kind", if (kind == NoteKind.CHECKLIST) 1 else 0)
                put("title", title)
                put("body", encodedBody)
                put("images", encodedImages)
                put("color", colorIndex)
                put("folder_id", folderId)
                put("pinned", if (pinned) 1 else 0)
                put("remind_at", remindAt)
                put("repeat_type", repeat.ordinal)
                put("all_day", if (allDay) 1 else 0)
                put("updated_at", now)
            }

            when (val outcome = NoteStore.updateNoteCas(id, base, values, database)) {
                is CasOutcome.Updated -> {
                    // 规范 §5.3：每次成功提交留一份完整快照（第一版不做压缩/清理）
                    NoteStore.insertNoteVersion(
                        noteId = id,
                        revision = outcome.revision,
                        snapshotJson = noteSnapshotJson(
                            id, outcome.revision, kind, title, encodedBody, encodedImages,
                            colorIndex, folderId, pinned, remindAt, repeat.ordinal, allDay, now,
                        ),
                        reason = "save",
                        operationId = operationId,
                        now = now,
                        database = database,
                    )
                    NoteStore.recordOperation(
                        operationId, hash, "note", id, outcome.revision, now, database,
                    )
                    SaveResult.Saved(id, outcome.revision)
                }
                is CasOutcome.Conflict -> SaveResult.Conflict(outcome.actualRevision)
                CasOutcome.NotFound -> SaveResult.NotFound
            }
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        // 取消不是失败：必须原样传播，否则界面会把"被取消"显示成"保存失败"，
        // 而调用方可能已经提交，重试就会造成重复写入（规范 §6.1/§15）。
        throw cancellation
    } catch (t: Throwable) {
        // 只归类可理解的错误；原始异常留在边界做诊断，不吞、不伪报成功
        SaveResult.Failed(StorageFailure.of(t))
    }

    suspend fun setColor(id: Long, colorIndex: Int) = withContext(Dispatchers.IO) {
        db.setColor(id, colorIndex)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        db.setPinned(id, pinned, System.currentTimeMillis())
    }

    suspend fun setReminder(id: Long, remindAt: Long?, repeat: RepeatRule = RepeatRule.NONE, allDay: Boolean = false) =
        withContext(Dispatchers.IO) {
            db.setReminder(id, remindAt, repeat.ordinal, allDay)
        }

    suspend fun moveToFolder(id: Long, folderId: Long?) = withContext(Dispatchers.IO) {
        db.moveToFolder(id, folderId, System.currentTimeMillis())
    }

    suspend fun trashNote(id: Long) = withContext(Dispatchers.IO) {
        db.trashNote(id, System.currentTimeMillis())
    }

    suspend fun restoreNote(id: Long) = withContext(Dispatchers.IO) { db.restoreNote(id) }

    suspend fun deleteForever(id: Long) = withContext(Dispatchers.IO) { db.deleteForever(id) }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) { db.emptyTrash() }

    suspend fun purgeExpiredTrash(maxAgeMs: Long) = withContext(Dispatchers.IO) {
        db.purgeExpiredTrash(maxAgeMs, System.currentTimeMillis())
    }

    suspend fun allFutureReminders(): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Pair<Long, Long>>()
        val now = System.currentTimeMillis()
        db.readableDatabase.rawQuery(
            "SELECT id, remind_at FROM notes WHERE trashed = 0 AND remind_at IS NOT NULL AND remind_at > ?",
            arrayOf(now.toString()),
        ).use { c ->
            while (c.moveToNext()) list += c.getLong(0) to c.getLong(1)
        }
        list
    }

    // ---- folders ----

    suspend fun loadFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Folder>()
        db.readableDatabase.rawQuery("SELECT * FROM folders ORDER BY created_at ASC", null).use { c ->
            while (c.moveToNext()) {
                list += Folder(id = c.getLong(0), name = c.getString(1), createdAt = c.getLong(2))
            }
        }
        list
    }

    /** 返回 null 表示重名未创建 */
    suspend fun createFolder(name: String): Long? = withContext(Dispatchers.IO) {
        val id = db.insertFolder(name, System.currentTimeMillis())
        if (id <= 0L) null else id
    }

    suspend fun renameFolder(id: Long, newName: String) = withContext(Dispatchers.IO) {
        db.renameFolder(id, newName)
    }

    /** 删除分类并把其下笔记移出分类：整体一个事务（规范 §2）。 */
    suspend fun deleteFolder(id: Long) = tx.write { database -> db.deleteFolder(id, database) }

    suspend fun folderCounts(): Map<Long, Int> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<Long, Int>()
        db.readableDatabase.rawQuery(
            "SELECT folder_id, COUNT(*) FROM notes WHERE trashed = 0 AND folder_id IS NOT NULL GROUP BY folder_id",
            null,
        ).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getInt(1)
        }
        map
    }

    // ---- todos ----

    suspend fun loadTodos(): List<Todo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Todo>()
        db.readableDatabase.rawQuery("SELECT * FROM todos WHERE trashed = 0", null).use { c ->
            while (c.moveToNext()) list += c.toTodo()
        }
        list
    }

    /** 废纸篓中的待办只列顶层项，子项随父项一起恢复/删除 */
    suspend fun loadTrashedTodos(): List<Todo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Todo>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM todos WHERE trashed = 1 AND parent_id IS NULL ORDER BY trashed_at DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) list += c.toTodo()
        }
        list
    }

    suspend fun trashedTodoCount(): Int = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM todos WHERE trashed = 1 AND parent_id IS NULL",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun getTodo(id: Long): Todo? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT * FROM todos WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toTodo() else null
        }
    }

    suspend fun createTodo(parentId: Long?, title: String, dueAt: Long?, allDay: Boolean, repeat: Int): Long =
        withContext(Dispatchers.IO) {
            db.insertTodo(parentId, title.trim(), dueAt, allDay, repeat, sortIndex = 0, now = System.currentTimeMillis())
        }

    suspend fun quickAddTodo(title: String, dueAt: Long? = null): Long =
        createTodo(null, title, dueAt, allDay = false, repeat = 0)

    suspend fun updateTodo(id: Long, title: String, dueAt: Long?, allDay: Boolean, repeat: Int) =
        withContext(Dispatchers.IO) {
            db.updateTodo(id, title.trim(), dueAt, allDay, repeat, now = System.currentTimeMillis())
        }

    /**
     * 勾选父待办时同步所有子任务；切换子待办时则按全部子项重新计算父待办状态。
     * 这样无论变更来自 App 还是桌面悬浮层，父子完成状态都遵循同一规则。
     */
    suspend fun setTodoDone(todo: Todo, done: Boolean) = tx.write { database ->
        val now = System.currentTimeMillis()
        db.setTodoDone(todo.id, done, now, database)
        if (!todo.isSubtask) {
            // 勾选父项要连带全部子项：两条写入必须同成同败，否则会留下
            // "父项已完成、子项仍未完成"的自相矛盾状态（规范 §6.1）
            db.setDoneForChildren(todo.id, done, now)
        } else {
            todo.parentId?.let { syncParentDoneFromChildren(it, now, database) }
        }
    }

    /**
     * 用编辑器中的子任务列表整体替换某父待办的子任务。
     *
     * 规范 §2：原实现"先删全部子任务 → 逐条插入 → 回写父项状态"三步都是裸语句，
     * 中途失败（磁盘满、进程被杀）会永久丢掉用户原有的全部子任务。
     * 现在整体在一个事务内，且父项状态依据事务内的实际记录计算。
     */
    suspend fun replaceSubs(parentId: Long, subs: List<Pair<String, Boolean>>) =
        tx.write { database ->
            val now = System.currentTimeMillis()
            db.deleteSubsOf(parentId, database)
            subs.filter { it.first.isNotBlank() }.forEach { (text, done) ->
                val id = db.insertTodo(
                    parentId, text.trim(), null, false, 0,
                    sortIndex = 0, now = now, database = database,
                )
                if (done) db.setTodoDone(id, true, now, database)
            }
            syncParentDoneFromChildren(parentId, now, database)
        }

    /** 无子项时保留父待办自己的状态；存在子项时，父项仅在所有子项完成后才完成。 */
    private fun syncParentDoneFromChildren(parentId: Long, now: Long, database: android.database.sqlite.SQLiteDatabase) {
        // 必须用事务传入的这**一个**连接：WAL 下 readableDatabase 可能是另一条连接，
        // 看不到本事务内刚插入的子项，父项状态会算错（规范 §6.1）。
        database.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN done = 0 THEN 1 ELSE 0 END) FROM todos WHERE parent_id = ?",
            arrayOf(parentId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val childCount = cursor.getInt(0)
            val incompleteCount = cursor.getInt(1)
            TodoCompletion.parentDone(childCount, incompleteCount)?.let { parentDone ->
                db.setTodoDone(parentId, parentDone, now, database)
            }
        }
    }

    /** 完成重复待办后推进到下一次到期 */
    suspend fun rescheduleRepeat(id: Long, nextDue: Long, allDay: Boolean) = withContext(Dispatchers.IO) {
        db.setTodoDue(id, nextDue, allDay, System.currentTimeMillis())
    }

    suspend fun reorderTodos(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        orderedIds.forEachIndexed { index, id ->
            db.updateTodoSortIndex(id, index, now)
        }
    }

    /** 删除待办及其全部子项：整体一个事务，避免只删掉子项就中断而留下孤儿（规范 §2）。 */
    suspend fun deleteTodoTree(id: Long) = tx.write { database -> db.deleteTodoTree(id, database) }

    suspend fun trashTodoTree(id: Long): List<Long> = withContext(Dispatchers.IO) {
        db.trashTodoTree(id, System.currentTimeMillis())
    }

    suspend fun restoreTodoTree(id: Long) = withContext(Dispatchers.IO) { db.restoreTodoTree(id) }

    suspend fun emptyTodoTrash() = withContext(Dispatchers.IO) { db.emptyTodoTrash() }

    suspend fun purgeExpiredTodoTrash(maxAgeMs: Long) = withContext(Dispatchers.IO) {
        db.purgeExpiredTodoTrash(maxAgeMs, System.currentTimeMillis())
    }

    suspend fun trashCompletedTodos(): List<Long> = withContext(Dispatchers.IO) {
        db.trashCompletedTodos(System.currentTimeMillis())
    }

    suspend fun allFutureTodoReminders(): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Pair<Long, Long>>()
        val now = System.currentTimeMillis()
        db.readableDatabase.rawQuery(
            "SELECT id, remind_at FROM todos WHERE trashed = 0 AND done = 0 AND remind_at IS NOT NULL AND remind_at > ?",
            arrayOf(now.toString()),
        ).use { c ->
            while (c.moveToNext()) list += c.getLong(0) to c.getLong(1)
        }
        list
    }

    // ---- 备份 / 恢复 ----
    //
    // UI 与后续同步层一律通过这些入口，不直接接触 NotesDb（沿用 AGENTS.md 分层）。

    private val backupIo: BackupIo by lazy { BackupIo(appContext) }

    /** 导出整库（数据库 + 图片/录音附件）到 [target]。 */
    suspend fun exportBackup(target: File, appVersion: String): BackupIo.ExportResult =
        backupIo.export(target, db, appVersion)

    /** 合并导入；按 uuid 判重、updatedAt 新者胜，重复导入幂等。 */
    suspend fun importBackup(source: InputStream): BackupIo.ImportResult =
        backupIo.import(source, db)

    /** 只解析备份内容（用于导入前预览），不改数据库。 */
    suspend fun readBackup(source: InputStream): BackupFile =
        backupIo.readBackup(source)

    // ---- 测试用只读探针（不影响生产路径）----

    /** 当前修订号；记录不存在时返回 null（规范 §7 的 expectedRevision 基线） */
    suspend fun noteRevision(noteId: Long): Long? = tx.read { database ->
        NoteStore.readRevision(noteId, database)
    }

    internal fun debugRevision(noteId: Long): Long =
        db.readableDatabase.rawQuery("SELECT revision FROM notes WHERE id = ?", arrayOf(noteId.toString()))
            .use { c -> c.moveToFirst(); c.getLong(0) }

    internal fun debugVersionCount(noteId: Long): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM note_versions WHERE note_id = ?", arrayOf(noteId.toString()))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    // ---- helpers ----

    /**
     * 历史快照（规范 §5.3）：存的是**原始存储值**，不经过有损的 UI 模型往返，
     * 因此恢复历史时正文与附件引用都能逐字还原。
     */
    private fun noteSnapshotJson(
        id: Long, revision: Long, kind: NoteKind, title: String, body: String, images: String,
        colorIndex: Int, folderId: Long?, pinned: Boolean, remindAt: Long?,
        repeatOrdinal: Int, allDay: Boolean, now: Long,
    ): String = org.json.JSONObject().apply {
        put("noteId", id)
        put("revision", revision)
        put("kind", kind.ordinal)
        put("title", title)
        put("body", body)
        put("images", images)
        put("colorIndex", colorIndex)
        put("folderId", folderId ?: org.json.JSONObject.NULL)
        put("pinned", pinned)
        put("remindAt", remindAt ?: org.json.JSONObject.NULL)
        put("repeat", repeatOrdinal)
        put("allDay", allDay)
        put("at", now)
    }.toString()

    private fun encodeBody(kind: NoteKind, body: String, items: List<ChecklistItem>): String =
        when (kind) {
            NoteKind.TEXT -> body
            NoteKind.CHECKLIST -> ChecklistCodec.encode(items)
        }

    private fun Cursor.toNote(): Note {
        val kind = if (getInt(NotesDb.COL_KIND) == 1) NoteKind.CHECKLIST else NoteKind.TEXT
        val rawBody = getString(NotesDb.COL_BODY) ?: ""
        return Note(
            id = getLong(NotesDb.COL_ID),
            uuid = getString(NotesDb.COL_UUID) ?: "",
            kind = kind,
            title = getString(NotesDb.COL_TITLE) ?: "",
            body = if (kind == NoteKind.TEXT) rawBody else "",
            items = if (kind == NoteKind.CHECKLIST) ChecklistCodec.decode(rawBody) else emptyList(),
            images = (getString(NotesDb.COL_IMAGES) ?: "")
                .split('\n')
                .filter { it.isNotBlank() },
            colorIndex = getInt(NotesDb.COL_COLOR),
            folderId = if (isNull(NotesDb.COL_FOLDER)) null else getLong(NotesDb.COL_FOLDER),
            pinned = getInt(NotesDb.COL_PINNED) == 1,
            trashed = getInt(NotesDb.COL_TRASHED) == 1,
            trashedAt = if (isNull(NotesDb.COL_TRASHED_AT)) null else getLong(NotesDb.COL_TRASHED_AT),
            remindAt = if (isNull(NotesDb.COL_REMIND_AT)) null else getLong(NotesDb.COL_REMIND_AT),
            repeat = RepeatRule.fromOrdinal(getInt(NotesDb.COL_NOTE_REPEAT)),
            allDay = getInt(NotesDb.COL_NOTE_ALL_DAY) == 1,
            createdAt = getLong(NotesDb.COL_CREATED),
            updatedAt = getLong(NotesDb.COL_UPDATED),
        )
    }

    private fun Cursor.toTodo(): Todo {
        val due = if (isNull(NotesDb.T_DUE_AT)) null else getLong(NotesDb.T_DUE_AT)
        val legacyRemind = if (isNull(NotesDb.COL_REMIND_AT)) null else getLong(NotesDb.COL_REMIND_AT)
        return Todo(
            id = getLong(NotesDb.COL_ID),
            uuid = getString(NotesDb.T_UUID) ?: "",
            parentId = if (isNull(NotesDb.T_PARENT)) null else getLong(NotesDb.T_PARENT),
            title = getString(NotesDb.COL_TITLE) ?: "",
            done = getInt(NotesDb.T_DONE) == 1,
            doneAt = if (isNull(NotesDb.T_DONE_AT)) null else getLong(NotesDb.T_DONE_AT),
            dueAt = due ?: legacyRemind,
            allDay = getInt(NotesDb.T_ALL_DAY) == 1,
            repeat = RepeatRule.fromOrdinal(getInt(NotesDb.T_REPEAT)),
            remindAt = due ?: legacyRemind,
            sortIndex = getInt(NotesDb.T_SORT),
            createdAt = getLong(NotesDb.COL_CREATED),
            updatedAt = getLong(NotesDb.COL_UPDATED),
            trashed = getInt(NotesDb.T_TRASHED) == 1,
            trashedAt = if (isNull(NotesDb.T_TRASHED_AT)) null else getLong(NotesDb.T_TRASHED_AT),
        )
    }

    private fun Cursor.getInt(column: String): Int =
        getInt(getColumnIndexOrThrow(column))

    private fun Cursor.getLong(column: String): Long =
        getLong(getColumnIndexOrThrow(column))

    private fun Cursor.getString(column: String): String? =
        getString(getColumnIndexOrThrow(column))

    private fun Cursor.isNull(column: String): Boolean =
        isNull(getColumnIndexOrThrow(column))
}
