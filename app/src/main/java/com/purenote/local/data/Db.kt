package com.purenote.local.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** [name] 仅测试需要（用独立库文件，避免污染真实数据）；应用内一律用默认库名。 */
class NotesDb(context: Context, name: String = DB_NAME) : SQLiteOpenHelper(
    context,
    name,
    null,
    DB_VERSION,
    // 规范 §6.3：不安装处理器会落到 Android 默认实现，其 onCorruption 会删除数据库文件。
    PreserveDatabaseErrorHandler(),
) {

    /**
     * 在一个原生事务内执行 [block]（非 suspend：事务与线程绑定）。
     * 供需要直接持有 [SQLiteDatabase] 的场景使用；常规写入走 DatabaseExecutor。
     */
    fun <T> inTransaction(block: (SQLiteDatabase) -> T): T {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val result = block(database)
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_FOLDERS)
        db.execSQL(SQL_CREATE_NOTES)
        db.execSQL(SQL_CREATE_TODOS)
        db.execSQL("CREATE INDEX idx_notes_updated ON notes(updated_at)")
        db.execSQL("CREATE INDEX idx_notes_folder ON notes(folder_id)")
        db.execSQL("CREATE INDEX idx_notes_trashed_updated ON notes(trashed, updated_at)")
        db.execSQL("CREATE INDEX idx_notes_trashed_folder ON notes(trashed, folder_id)")
        db.execSQL("CREATE INDEX idx_todos_parent ON todos(parent_id)")
        db.execSQL("CREATE INDEX idx_todos_due ON todos(due_at)")
        db.execSQL("CREATE INDEX idx_todos_trashed ON todos(trashed)")
        db.execSQL("CREATE INDEX idx_todos_trashed_parent ON todos(trashed, parent_id)")
        // 全新安装也必须与迁移后的库结构完全一致，否则 onUpgrade 之外的分支会漂移
        createV9Tables(db)
        db.execSQL(SQL_CREATE_LIBRARY_META_ROW)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN color INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN images TEXT NOT NULL DEFAULT ''")
            // 必须用 v2 时代的历史 DDL：若用当前定义建表，后面 <3/<4/<5/<9> 的
            // ALTER TABLE ADD COLUMN 会因为列已存在而整条迁移失败（v1 库无法升级）。
            db.execSQL(SQL_CREATE_TODOS_V2)
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
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE todos ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE todos ADD COLUMN trashed_at INTEGER NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_trashed ON todos(trashed)")
        }
        if (oldVersion < 5) {
            // 云同步地基：每条记录一个跨设备稳定 ID（本地自增 id 只在本机有意义）
            addUuidColumn(db, "notes")
            addUuidColumn(db, "todos")
        }
        if (oldVersion < 6) {
            // 笔记提醒支持重复/整天（与 todos 同名列，独立存储）
            db.execSQL("ALTER TABLE notes ADD COLUMN repeat_type INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN all_day INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            // 热点列表查询走 (trashed, 排序/过滤列) 复合索引，笔记量大时避免全表扫描
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_trashed_updated ON notes(trashed, updated_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_trashed_folder ON notes(trashed, folder_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_trashed_parent ON todos(trashed, parent_id)")
        }
        if (oldVersion < 8) {
            // 正文存储格式 Markdown 化：私有标记(PUA 标题/☐ 前缀/[img:]) → 标准 Markdown
            // 迁移逻辑在 NoteMarkup.migrateBodyV1toV2（纯函数，有单测），此处只做逐行搬运
            val cv = android.content.ContentValues()
            db.rawQuery("SELECT id, body FROM notes WHERE kind = 0", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val body = c.getString(1) ?: ""
                    val migrated = com.purenote.local.core.NoteMarkup.migrateBodyV1toV2(body)
                    if (migrated != body) {
                        cv.clear()
                        cv.put("body", migrated)
                        db.update("notes", cv, "id = ?", arrayOf(id.toString()))
                    }
                }
            }
        }
        if (oldVersion < 9) {
            // 规范 §5.2：修订号 + 正文格式版本 + 历史/幂等/附件/提醒所需的表。
            // 加法迁移：只加列建表，不改既有数据、不重建表、不删除任何东西。
            //
            // 门槛必须是 oldVersion < 9。规范原文写的是"从当前 DB_VERSION=3 出发，
            // 先设计 v4 加法迁移"——那是基于过时分叉的假设；真实基线是 8。
            // 若照抄 < 4，所有存量用户（库在 8）永远不会执行该分支，
            // revision 列根本不会被加上，随后 §7 的每一条 WHERE revision = ? 全部报错。
            //
            // 放在 < 8 之后：正文格式版本的含义是"此刻 body 列的编码"，
            // 必须等 v8 的 PUA→Markdown 转换跑完再打标（DEFAULT 2 = Markdown）。
            db.execSQL("ALTER TABLE notes ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE notes ADD COLUMN body_format_version INTEGER NOT NULL DEFAULT 2")
            db.execSQL("ALTER TABLE todos ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE folders ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            createV9Tables(db)
            db.execSQL(SQL_CREATE_LIBRARY_META_ROW)
        }
        if (oldVersion < 10) {
            // 正文块文档化（body_format_version 2 → 3）。
            // 与 v8 同一套路：真正的转换是纯函数（core/NoteBody.upgradeStoredBody，有单测），
            // 这里只做逐行搬运。
            //
            // 保全原则：单行转换失败**不改该行**，它继续保持旧版本号；读取端按行内版本号
            // 解析（core/NoteBody.decode 还会做格式嗅探兜底），所以"没迁移成功"只是没升级，
            // 绝不会让笔记显示为空或内容错乱。
            val values = android.content.ContentValues()
            db.rawQuery("SELECT id, kind, body, body_format_version FROM notes", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    // 脑图正文是树 JSON，不是标记文本：这里绝不能按 Markdown 升级
                    if (c.getInt(1) == NoteKind.MIND.storedCode()) continue
                    val isChecklist = c.getInt(1) == 1
                    val raw = c.getString(2) ?: ""
                    val version = c.getInt(3)
                    val migrated = runCatching {
                        com.purenote.local.core.NoteBody.upgradeStoredBody(
                            isChecklist = isChecklist,
                            rawBody = raw,
                            formatVersion = version,
                        )
                    }.getOrNull() ?: continue // 单行失败就跳过，该行保持旧版本号，读取端仍能解析
                    values.clear()
                    values.put(COL_BODY, migrated.first)
                    values.put(COL_BODY_FORMAT, migrated.second)
                    db.update("notes", values, "id = ?", arrayOf(id.toString()))
                }
            }
        }
    }

    private fun addUuidColumn(db: SQLiteDatabase, table: String) {
        db.execSQL("ALTER TABLE $table ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        // 存量数据一次性补齐；新建行走 insert 时生成
        db.execSQL("UPDATE $table SET uuid = hex(randomblob(16)) WHERE uuid = ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${table}_uuid ON $table(uuid)")
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
        database: SQLiteDatabase = writableDatabase,
    ): Long {
        val cv = ContentValues().apply {
            put("uuid", newUuid())
            put("kind", kind.storedCode())
            put("title", title)
            put("body", encodedBody)
            // 显式写版本号：不能依赖 DDL 默认值，否则"库里是 v3 JSON、标记却是 v2"会被
            // 读取端按 Markdown 解析（历史上 body_format_version 就是靠默认值漏掉的）
            put(COL_BODY_FORMAT, BODY_FORMAT_BLOCKS)
            put("images", images)
            put("color", colorIndex)
            put("folder_id", folderId)
            put("created_at", now)
            put("updated_at", now)
        }
        // insertOrThrow：普通 insert 失败返回 -1，会被当成一个"有效 ID"继续用下去（规范 §7）
        return database.insertOrThrow("notes", null, cv)
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
        repeatType: Int = 0,
        allDay: Boolean = false,
        now: Long,
        database: SQLiteDatabase = writableDatabase,
    ): Int {
        val cv = ContentValues().apply {
            put("kind", kind.storedCode())
            put("title", title)
            put("body", encodedBody)
            put(COL_BODY_FORMAT, BODY_FORMAT_BLOCKS)
            put("images", images)
            put("color", colorIndex)
            put("folder_id", folderId)
            put("pinned", if (pinned) 1 else 0)
            put("remind_at", remindAt)
            put("repeat_type", repeatType)
            put("all_day", if (allDay) 1 else 0)
            put("updated_at", now)
        }
        return database.update("notes", cv, "id = ?", arrayOf(id.toString()))
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

    fun setReminder(id: Long, remindAt: Long?, repeatType: Int = 0, allDay: Boolean = false): Int {
        val cv = ContentValues().apply {
            put("remind_at", remindAt)
            put("repeat_type", repeatType)
            put("all_day", if (allDay) 1 else 0)
        }
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

    /**
     * 删除分类，并把该分类下的笔记移出分类。
     * [database] 允许调用方并入外层事务：两条语句必须同成同败，
     * 否则中途失败会留下"分类已删、笔记仍指向它"的悬空引用。
     */
    fun deleteFolder(id: Long, database: SQLiteDatabase = writableDatabase): Int {
        database.update(
            "notes",
            ContentValues().apply { putNull("folder_id") },
            "folder_id = ?",
            arrayOf(id.toString()),
        )
        return database.delete("folders", "id = ?", arrayOf(id.toString()))
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
        database: SQLiteDatabase = writableDatabase,
    ): Long {
        val cv = ContentValues().apply {
            put("uuid", newUuid())
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
        return database.insert("todos", null, cv)
    }

    // 编辑不写 sort_index：位置保持原样（用户 2026-09-13 要求），排序字段只归拖拽更新
    fun updateTodo(
        id: Long,
        title: String,
        dueAt: Long?,
        allDay: Boolean,
        repeatType: Int,
        now: Long,
    ): Int {
        val cv = ContentValues().apply {
            put("title", title)
            put("due_at", dueAt)
            put("remind_at", dueAt)
            put("all_day", if (allDay) 1 else 0)
            put("repeat_type", repeatType)
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

    fun updateTodoSortIndex(id: Long, sortIndex: Int, now: Long): Int {
        val cv = ContentValues().apply {
            put("sort_index", sortIndex)
            put("updated_at", now)
        }
        return writableDatabase.update("todos", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setTodoDone(id: Long, done: Boolean, now: Long, database: SQLiteDatabase = writableDatabase): Int {
        val cv = ContentValues().apply {
            put("done", if (done) 1 else 0)
            put("done_at", if (done) now else null as Long?)
            put("updated_at", now)
        }
        return database.update("todos", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setDoneForChildren(parentId: Long, done: Boolean, now: Long): Int {
        val cv = ContentValues().apply {
            put("done", if (done) 1 else 0)
            put("done_at", if (done) now else null as Long?)
            put("updated_at", now)
        }
        return writableDatabase.update("todos", cv, "parent_id = ?", arrayOf(parentId.toString()))
    }

    /** 删除待办及其全部子项。两条语句必须同成同败，否则会留下失去父项的孤儿。 */
    fun deleteTodoTree(id: Long, database: SQLiteDatabase = writableDatabase): Int {
        val count = database.delete("todos", "parent_id = ?", arrayOf(id.toString()))
        return count + database.delete("todos", "id = ?", arrayOf(id.toString()))
    }

    fun deleteSubsOf(parentId: Long, database: SQLiteDatabase = writableDatabase): Int =
        database.delete("todos", "parent_id = ?", arrayOf(parentId.toString()))

    /** 待办整树移入废纸篓（父项连带子项），返回受影响的 id（含子项，用于取消提醒） */
    fun trashTodoTree(id: Long, now: Long): List<Long> {
        val ids = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT id FROM todos WHERE id = ? OR parent_id = ?",
            arrayOf(id.toString(), id.toString()),
        ).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        if (ids.isEmpty()) return ids
        val cv = ContentValues().apply {
            put("trashed", 1)
            put("trashed_at", now)
        }
        writableDatabase.update(
            "todos", cv, "id = ? OR parent_id = ?",
            arrayOf(id.toString(), id.toString()),
        )
        return ids
    }

    /** 从废纸篓恢复待办整树 */
    fun restoreTodoTree(id: Long): Int {
        val cv = ContentValues().apply {
            put("trashed", 0)
            putNull("trashed_at")
        }
        return writableDatabase.update(
            "todos", cv, "id = ? OR parent_id = ?",
            arrayOf(id.toString(), id.toString()),
        )
    }

    fun emptyTodoTrash(): Int =
        writableDatabase.delete("todos", "trashed = 1", null)

    /** 清理在废纸篓中超过 maxAgeMs 的待办，返回删除数量 */
    fun purgeExpiredTodoTrash(maxAgeMs: Long, now: Long): Int =
        writableDatabase.delete(
            "todos",
            "trashed = 1 AND trashed_at IS NOT NULL AND trashed_at < ?",
            arrayOf((now - maxAgeMs).toString()),
        )

    /** 已完成待办整批移入废纸篓（替代过去的直接永久清除），返回受影响的 id */
    fun trashCompletedTodos(now: Long): List<Long> {
        val ids = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT id FROM todos WHERE done = 1 AND trashed = 0",
            null,
        ).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        if (ids.isEmpty()) return ids
        val cv = ContentValues().apply {
            put("trashed", 1)
            put("trashed_at", now)
        }
        writableDatabase.update("todos", cv, "done = 1 AND trashed = 0", null)
        return ids
    }

    /** 云同步用的跨设备稳定 ID（32 位十六进制，无连字符） */
    private fun newUuid(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        const val DB_VERSION = 10
        const val DB_NAME = "purenote.db"

        /** 正文格式版本：1 = v1.2.20 及更早的 PUA 私有标记；2 = 标准 Markdown（规范 §5.2） */
        const val BODY_FORMAT_LEGACY = 1
        const val BODY_FORMAT_MARKDOWN = 2

        /** v3：正文改为块文档 JSON（core/RichDoc），解析入口统一走 core/NoteBody */
        const val BODY_FORMAT_BLOCKS = 3

        private val SQL_CREATE_FOLDERS = """
            CREATE TABLE folders(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              revision INTEGER NOT NULL DEFAULT 1,
              created_at INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_NOTES = """
            CREATE TABLE notes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              uuid TEXT NOT NULL DEFAULT '',
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
              repeat_type INTEGER NOT NULL DEFAULT 0,
              all_day INTEGER NOT NULL DEFAULT 0,
              revision INTEGER NOT NULL DEFAULT 1,
              body_format_version INTEGER NOT NULL DEFAULT 3,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        /**
         * v2 分支专用的**历史** DDL：只包含当时存在的列。
         * 用当前定义建表会让后续 ALTER TABLE ADD COLUMN 因列已存在而失败，
         * 结果是 v1 库完全无法升级（执行到 < 3 分支即抛错）。
         */
        private val SQL_CREATE_TODOS_V2 = """
            CREATE TABLE todos(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              parent_id INTEGER NULL,
              title TEXT NOT NULL,
              done INTEGER NOT NULL DEFAULT 0,
              done_at INTEGER NULL,
              due_at INTEGER NULL,
              remind_at INTEGER NULL,
              sort_index INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_TODOS = """
            CREATE TABLE todos(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              uuid TEXT NOT NULL DEFAULT '',
              parent_id INTEGER NULL,
              title TEXT NOT NULL,
              done INTEGER NOT NULL DEFAULT 0,
              done_at INTEGER NULL,
              due_at INTEGER NULL,
              remind_at INTEGER NULL,
              all_day INTEGER NOT NULL DEFAULT 0,
              repeat_type INTEGER NOT NULL DEFAULT 0,
              sort_index INTEGER NOT NULL DEFAULT 0,
              trashed INTEGER NOT NULL DEFAULT 0,
              trashed_at INTEGER NULL,
              revision INTEGER NOT NULL DEFAULT 1,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        // ---- v9 新增表（规范 §5.2）：onCreate 与 onUpgrade 共用同一份定义，避免两条路径漂移 ----

        private val SQL_CREATE_V9_TABLES = listOf(
            """
            CREATE TABLE library_meta(
              id INTEGER PRIMARY KEY CHECK (id = 1),
              library_id TEXT NOT NULL,
              schema_version INTEGER NOT NULL,
              store_epoch TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE note_versions(
              note_id INTEGER NOT NULL,
              revision INTEGER NOT NULL,
              snapshot_format_version INTEGER NOT NULL,
              snapshot_json TEXT NOT NULL,
              reason TEXT NOT NULL DEFAULT '',
              operation_id TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL,
              PRIMARY KEY (note_id, revision)
            )
            """.trimIndent(),
            """
            CREATE TABLE todo_versions(
              todo_root_id INTEGER NOT NULL,
              revision INTEGER NOT NULL,
              snapshot_format_version INTEGER NOT NULL,
              snapshot_json TEXT NOT NULL,
              reason TEXT NOT NULL DEFAULT '',
              operation_id TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL,
              PRIMARY KEY (todo_root_id, revision)
            )
            """.trimIndent(),
            """
            CREATE TABLE operations(
              operation_id TEXT PRIMARY KEY,
              request_hash TEXT NOT NULL,
              entity_type TEXT NOT NULL,
              entity_id INTEGER NOT NULL,
              result_revision INTEGER NOT NULL,
              committed_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE attachments(
              attachment_id TEXT PRIMARY KEY,
              relative_name TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              sha256 TEXT NOT NULL DEFAULT '',
              mime TEXT NOT NULL DEFAULT '',
              state TEXT NOT NULL DEFAULT 'published',
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE note_attachment_refs(
              note_id INTEGER NOT NULL,
              attachment_id TEXT NOT NULL,
              PRIMARY KEY (note_id, attachment_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE version_attachment_refs(
              note_id INTEGER NOT NULL,
              revision INTEGER NOT NULL,
              attachment_id TEXT NOT NULL,
              PRIMARY KEY (note_id, revision, attachment_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE reminder_jobs(
              target_kind TEXT NOT NULL,
              target_id INTEGER NOT NULL,
              expected_revision INTEGER NOT NULL,
              desired_at INTEGER NULL,
              state TEXT NOT NULL DEFAULT 'pending',
              updated_at INTEGER NOT NULL,
              PRIMARY KEY (target_kind, target_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE import_mappings(
              source_library_id TEXT NOT NULL,
              entity_type TEXT NOT NULL,
              source_id TEXT NOT NULL,
              target_id INTEGER NOT NULL,
              PRIMARY KEY (source_library_id, entity_type, source_id)
            )
            """.trimIndent(),
            "CREATE INDEX idx_note_versions_created ON note_versions(note_id, created_at)",
            "CREATE INDEX idx_operations_committed ON operations(committed_at)",
            "CREATE INDEX idx_refs_attachment ON note_attachment_refs(attachment_id)",
            "CREATE INDEX idx_version_refs_attachment ON version_attachment_refs(attachment_id)",
            "CREATE INDEX idx_import_mappings_target ON import_mappings(entity_type, target_id)",
        )

        private fun createV9Tables(db: SQLiteDatabase) {
            SQL_CREATE_V9_TABLES.forEach { db.execSQL(it) }
        }

        /** 库身份 + 存储代次。library_id 跨设备稳定；store_epoch 每次切换活动存储时更换（§5.1/§12）。 */
        private val SQL_CREATE_LIBRARY_META_ROW = """
            INSERT OR IGNORE INTO library_meta(id, library_id, schema_version, store_epoch, created_at)
            VALUES (1, lower(hex(randomblob(16))), 9, lower(hex(randomblob(16))), strftime('%s','now') * 1000)
        """.trimIndent()

        // 列名常量
        const val COL_ID = "id"
        const val COL_UUID = "uuid"
        const val COL_NOTE_REPEAT = "repeat_type"
        const val COL_NOTE_ALL_DAY = "all_day"
        const val COL_KIND = "kind"
        const val COL_TITLE = "title"
        const val COL_BODY = "body"
        const val COL_BODY_FORMAT = "body_format_version"
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
        const val T_UUID = "uuid"
        const val T_DONE = "done"
        const val T_DONE_AT = "done_at"
        const val T_DUE_AT = "due_at"
        const val T_ALL_DAY = "all_day"
        const val T_REPEAT = "repeat_type"
        const val T_SORT = "sort_index"
        const val T_TRASHED = "trashed"
        const val T_TRASHED_AT = "trashed_at"
    }
}
