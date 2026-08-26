package com.purenote.local.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NotesDb(context: Context) : SQLiteOpenHelper(context, "purenote.db", null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_FOLDERS)
        db.execSQL(SQL_CREATE_NOTES)
        db.execSQL(SQL_CREATE_TODOS)
        db.execSQL("CREATE INDEX idx_notes_updated ON notes(updated_at)")
        db.execSQL("CREATE INDEX idx_notes_folder ON notes(folder_id)")
        db.execSQL("CREATE INDEX idx_todos_parent ON todos(parent_id)")
        db.execSQL("CREATE INDEX idx_todos_due ON todos(due_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN color INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN images TEXT NOT NULL DEFAULT ''")
            db.execSQL(SQL_CREATE_TODOS)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_parent ON todos(parent_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_due ON todos(due_at)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE todos ADD COLUMN all_day INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE todos ADD COLUMN repeat_type INTEGER NOT NULL DEFAULT 0")
            // 小米式合一：旧的独立提醒时间并入到期时间
            db.execSQL(
                "UPDATE todos SET due_at = remind_at " +
                    "WHERE due_at IS NULL AND remind_at IS NOT NULL AND parent_id IS NULL",
            )
        }
    }

    // ---- notes ----

    fun insertNote(
        kind: NoteKind,
        title: String,
        encodedBody: String,
        images: String,
        colorIndex: Int,
        folderId: Long?,
        now: Long,
    ): Long {
        val cv = ContentValues().apply {
            put("kind", if (kind == NoteKind.CHECKLIST) 1 else 0)
            put("title", title)
            put("body", encodedBody)
            put("images", images)
            put("color", colorIndex)
            put("folder_id", folderId)
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insert("notes", null, cv)
    }

    fun updateNote(
        id: Long,
        kind: NoteKind,
        title: String,
        encodedBody: String,
        images: String,
        colorIndex: Int,
        folderId: Long?,
        pinned: Boolean,
        remindAt: Long?,
        now: Long,
    ): Int {
        val cv = ContentValues().apply {
            put("kind", if (kind == NoteKind.CHECKLIST) 1 else 0)
            put("title", title)
            put("body", encodedBody)
            put("images", images)
            put("color", colorIndex)
            put("folder_id", folderId)
            put("pinned", if (pinned) 1 else 0)
            put("remind_at", remindAt)
            put("updated_at", now)
        }
        return writableDatabase.update("notes", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setColor(id: Long, colorIndex: Int): Int {
        val cv = ContentValues().apply { put("color", colorIndex) }
        return writableDatabase.update("notes", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setPinned(id: Long, pinned: Boolean, now: Long): Int {
        val cv = ContentValues().apply {
            put("pinned", if (pinned) 1 else 0)
            put("updated_at", now)
        }
        return writableDatabase.update("notes", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setReminder(id: Long, remindAt: Long?): Int {
        val cv = ContentValues().apply { put("remind_at", remindAt) }
        return writableDatabase.update("notes", cv, "id = ?", arrayOf(id.toString()))
    }

    fun moveToFolder(id: Long, folderId: Long?, now: Long): Int {
        val cv = ContentValues().apply {
            put("folder_id", folderId)
            put("updated_at", now)
        }
        return writableDatabase.update("notes", cv, "id = ?", arrayOf(id.toString()))
    }

    fun trashNote(id: Long, now: Long): Int {
        val cv = ContentValues().apply {
            put("trashed", 1)
            put("trashed_at", now)
            put("pinned", 0)
        }
        return writableDatabase.update("notes", cv, "id = ?", arrayOf(id.toString()))
    }

    fun restoreNote(id: Long): Int {
        val cv = ContentValues().apply {
            put("trashed", 0)
            putNull("trashed_at")
        }
        return writableDatabase.update("notes", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteForever(id: Long): Int =
        writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))

    fun emptyTrash(): Int =
        writableDatabase.delete("notes", "trashed = 1", null)

    /** 清理在废纸篓中超过 maxAgeMs 的笔记，返回删除数量 */
    fun purgeExpiredTrash(maxAgeMs: Long, now: Long): Int =
        writableDatabase.delete(
            "notes",
            "trashed = 1 AND trashed_at IS NOT NULL AND trashed_at < ?",
            arrayOf((now - maxAgeMs).toString()),
        )

    // ---- folders ----

    fun insertFolder(name: String, now: Long): Long {
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("created_at", now)
        }
        return writableDatabase.insertWithOnConflict("folders", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun renameFolder(id: Long, newName: String): Int {
        val cv = ContentValues().apply { put("name", newName.trim()) }
        return writableDatabase.update("folders", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteFolder(id: Long): Int {
        writableDatabase.update(
            "notes",
            ContentValues().apply { putNull("folder_id") },
            "folder_id = ?",
            arrayOf(id.toString()),
        )
        return writableDatabase.delete("folders", "id = ?", arrayOf(id.toString()))
    }

    // ---- todos ----

    fun insertTodo(
        parentId: Long?,
        title: String,
        dueAt: Long?,
        allDay: Boolean,
        repeatType: Int,
        sortIndex: Int,
        now: Long,
    ): Long {
        val cv = ContentValues().apply {
            put("parent_id", parentId)
            put("title", title)
            put("due_at", dueAt)
            put("remind_at", dueAt)
            put("all_day", if (allDay) 1 else 0)
            put("repeat_type", repeatType)
            put("sort_index", sortIndex)
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insert("todos", null, cv)
    }

    fun updateTodo(
        id: Long,
        title: String,
        dueAt: Long?,
        allDay: Boolean,
        repeatType: Int,
        sortIndex: Int,
        now: Long,
    ): Int {
        val cv = ContentValues().apply {
            put("title", title)
            put("due_at", dueAt)
            put("remind_at", dueAt)
            put("all_day", if (allDay) 1 else 0)
            put("repeat_type", repeatType)
            put("sort_index", sortIndex)
            put("updated_at", now)
        }
        return writableDatabase.update("todos", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 推进重复待办的下次到期时间 */
    fun setTodoDue(id: Long, dueAt: Long?, allDay: Boolean, now: Long): Int {
        val cv = ContentValues().apply {
            put("due_at", dueAt)
            put("remind_at", dueAt)
            put("all_day", if (allDay) 1 else 0)
            put("updated_at", now)
        }
        return writableDatabase.update("todos", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setTodoDone(id: Long, done: Boolean, now: Long): Int {
        val cv = ContentValues().apply {
            put("done", if (done) 1 else 0)
            put("done_at", if (done) now else null as Long?)
            put("updated_at", now)
        }
        return writableDatabase.update("todos", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setDoneForChildren(parentId: Long, done: Boolean, now: Long): Int {
        val cv = ContentValues().apply {
            put("done", if (done) 1 else 0)
            put("done_at", if (done) now else null as Long?)
            put("updated_at", now)
        }
        return writableDatabase.update("todos", cv, "parent_id = ?", arrayOf(parentId.toString()))
    }

    fun deleteTodoTree(id: Long): Int {
        val count = writableDatabase.delete("todos", "parent_id = ?", arrayOf(id.toString()))
        return count + writableDatabase.delete("todos", "id = ?", arrayOf(id.toString()))
    }

    fun deleteSubsOf(parentId: Long): Int =
        writableDatabase.delete("todos", "parent_id = ?", arrayOf(parentId.toString()))

    fun deleteCompletedTodos(now: Long): Int =
        writableDatabase.delete("todos", "done = 1", null)

    companion object {
        const val DB_VERSION = 3

        private val SQL_CREATE_FOLDERS = """
            CREATE TABLE folders(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              created_at INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_NOTES = """
            CREATE TABLE notes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              kind INTEGER NOT NULL DEFAULT 0,
              title TEXT NOT NULL DEFAULT '',
              body TEXT NOT NULL DEFAULT '',
              images TEXT NOT NULL DEFAULT '',
              color INTEGER NOT NULL DEFAULT 0,
              folder_id INTEGER NULL,
              pinned INTEGER NOT NULL DEFAULT 0,
              trashed INTEGER NOT NULL DEFAULT 0,
              trashed_at INTEGER NULL,
              remind_at INTEGER NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_TODOS = """
            CREATE TABLE todos(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              parent_id INTEGER NULL,
              title TEXT NOT NULL,
              done INTEGER NOT NULL DEFAULT 0,
              done_at INTEGER NULL,
              due_at INTEGER NULL,
              remind_at INTEGER NULL,
              all_day INTEGER NOT NULL DEFAULT 0,
              repeat_type INTEGER NOT NULL DEFAULT 0,
              sort_index INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        // 列名常量
        const val COL_ID = "id"
        const val COL_KIND = "kind"
        const val COL_TITLE = "title"
        const val COL_BODY = "body"
        const val COL_IMAGES = "images"
        const val COL_COLOR = "color"
        const val COL_FOLDER = "folder_id"
        const val COL_PINNED = "pinned"
        const val COL_TRASHED = "trashed"
        const val COL_TRASHED_AT = "trashed_at"
        const val COL_REMIND_AT = "remind_at"
        const val COL_CREATED = "created_at"
        const val COL_UPDATED = "updated_at"

        const val T_PARENT = "parent_id"
        const val T_DONE = "done"
        const val T_DONE_AT = "done_at"
        const val T_DUE_AT = "due_at"
        const val T_ALL_DAY = "all_day"
        const val T_REPEAT = "repeat_type"
        const val T_SORT = "sort_index"
    }
}
