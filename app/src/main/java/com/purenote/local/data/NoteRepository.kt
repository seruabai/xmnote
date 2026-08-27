package com.purenote.local.data

import android.content.Context
import android.database.Cursor
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.core.TodoCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NoteRepository(context: Context) {

    private val db = NotesDb(context.applicationContext)

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
    ): Boolean = withContext(Dispatchers.IO) {
        db.updateNote(
            id = id,
            kind = kind,
            title = title,
            encodedBody = encodeBody(kind, body, items),
            images = images.joinToString("\n"),
            colorIndex = colorIndex,
            folderId = folderId,
            pinned = pinned,
            remindAt = remindAt,
            now = System.currentTimeMillis(),
        ) > 0
    }

    suspend fun setColor(id: Long, colorIndex: Int) = withContext(Dispatchers.IO) {
        db.setColor(id, colorIndex)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        db.setPinned(id, pinned, System.currentTimeMillis())
    }

    suspend fun setReminder(id: Long, remindAt: Long?) = withContext(Dispatchers.IO) {
        db.setReminder(id, remindAt)
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

    suspend fun deleteFolder(id: Long) = withContext(Dispatchers.IO) { db.deleteFolder(id) }

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
        db.readableDatabase.rawQuery("SELECT * FROM todos", null).use { c ->
            while (c.moveToNext()) list += c.toTodo()
        }
        list
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
            db.updateTodo(id, title.trim(), dueAt, allDay, repeat, sortIndex = 0, now = System.currentTimeMillis())
        }

    /**
     * 勾选父待办时同步所有子任务；切换子待办时则按全部子项重新计算父待办状态。
     * 这样无论变更来自 App 还是桌面悬浮层，父子完成状态都遵循同一规则。
     */
    suspend fun setTodoDone(todo: Todo, done: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.setTodoDone(todo.id, done, now)
        if (!todo.isSubtask) {
            db.setDoneForChildren(todo.id, done, now)
        } else {
            todo.parentId?.let { syncParentDoneFromChildren(it, now) }
        }
    }

    /** 用编辑器中的子任务列表整体替换某父待办的子任务 */
    suspend fun replaceSubs(parentId: Long, subs: List<Pair<String, Boolean>>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.deleteSubsOf(parentId)
        subs.filter { it.first.isNotBlank() }.forEach { (text, done) ->
            val id = db.insertTodo(parentId, text.trim(), null, false, 0, sortIndex = 0, now = now)
            if (done) db.setTodoDone(id, true, now)
        }
        syncParentDoneFromChildren(parentId, now)
    }

    /** 无子项时保留父待办自己的状态；存在子项时，父项仅在所有子项完成后才完成。 */
    private fun syncParentDoneFromChildren(parentId: Long, now: Long) {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN done = 0 THEN 1 ELSE 0 END) FROM todos WHERE parent_id = ?",
            arrayOf(parentId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            val childCount = cursor.getInt(0)
            val incompleteCount = cursor.getInt(1)
            TodoCompletion.parentDone(childCount, incompleteCount)?.let { parentDone ->
                db.setTodoDone(parentId, parentDone, now)
            }
        }
    }

    /** 完成重复待办后推进到下一次到期 */
    suspend fun rescheduleRepeat(id: Long, nextDue: Long, allDay: Boolean) = withContext(Dispatchers.IO) {
        db.setTodoDue(id, nextDue, allDay, System.currentTimeMillis())
    }

    suspend fun deleteTodoTree(id: Long) = withContext(Dispatchers.IO) { db.deleteTodoTree(id) }

    suspend fun clearDoneTodos() = withContext(Dispatchers.IO) { db.deleteCompletedTodos(System.currentTimeMillis()) }

    suspend fun allFutureTodoReminders(): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Pair<Long, Long>>()
        val now = System.currentTimeMillis()
        db.readableDatabase.rawQuery(
            "SELECT id, remind_at FROM todos WHERE done = 0 AND remind_at IS NOT NULL AND remind_at > ?",
            arrayOf(now.toString()),
        ).use { c ->
            while (c.moveToNext()) list += c.getLong(0) to c.getLong(1)
        }
        list
    }

    // ---- helpers ----

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
            createdAt = getLong(NotesDb.COL_CREATED),
            updatedAt = getLong(NotesDb.COL_UPDATED),
        )
    }

    private fun Cursor.toTodo(): Todo {
        val due = if (isNull(NotesDb.T_DUE_AT)) null else getLong(NotesDb.T_DUE_AT)
        val legacyRemind = if (isNull(NotesDb.COL_REMIND_AT)) null else getLong(NotesDb.COL_REMIND_AT)
        return Todo(
            id = getLong(NotesDb.COL_ID),
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
