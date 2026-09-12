package com.purenote.local.backup

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.data.NotesDb
import com.purenote.local.data.RepeatRule

/**
 * 备份文件与数据库之间的搬运。
 *
 * 两条铁律：
 * 1. **写入不得改动 `updated_at`**（用备份里的值原样写回）。否则 LWW 与"重复导入幂等"都失效。
 * 2. **跨表引用一律用 uuid 解析**，绝不把备份里的 id 直接写进库里。
 */
object BackupCodec {

    private const val TABLE_FOLDERS = "folders"

    /** 把整个库读成备份模型。 */
    fun export(db: NotesDb, appVersion: String, now: Long): BackupFile {
        val rdb = db.readableDatabase

        // 分类：folders 没有 uuid，用「名字」作备份内标识（DB 里 name 是 UNIQUE），
        // 这样导入端按名字匹配已有分类即可，不会重复建同名分类。
        val folders = mutableListOf<FolderDto>()
        val folderUuidByName = mutableMapOf<String, String>()
        rdb.rawQuery("SELECT name, created_at FROM $TABLE_FOLDERS ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val uuid = "folder-" + name
                folderUuidByName[name] = uuid
                folders += FolderDto(uuid = uuid, name = name, createdAt = c.getLong(1))
            }
        }

        val folderNameById = mutableMapOf<Long, String>()
        rdb.rawQuery("SELECT id, name FROM $TABLE_FOLDERS", null).use { c ->
            while (c.moveToNext()) folderNameById[c.getLong(0)] = c.getString(1) ?: ""
        }

        val notes = mutableListOf<NoteDto>()
        rdb.rawQuery("SELECT * FROM notes ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val kind = c.getIntCol(NotesDb.COL_KIND)
                val rawBody = c.getStringCol(NotesDb.COL_BODY) ?: ""
                val folderId = if (c.isNullCol(NotesDb.COL_FOLDER)) null else c.getLongCol(NotesDb.COL_FOLDER)
                notes += NoteDto(
                    uuid = c.getStringCol(NotesDb.COL_UUID) ?: "",
                    kind = kind,
                    title = c.getStringCol(NotesDb.COL_TITLE) ?: "",
                    body = rawBody,
                    items = if (kind == 1) {
                        ChecklistCodec.decode(rawBody).map { ChecklistItemDto(it.text, it.done) }
                    } else {
                        emptyList()
                    },
                    images = (c.getStringCol(NotesDb.COL_IMAGES) ?: "")
                        .split('\n').filter { it.isNotBlank() },
                    colorIndex = c.getIntCol(NotesDb.COL_COLOR),
                    folderUuid = folderId?.let { folderNameById[it] }?.let { folderUuidByName[it] },
                    pinned = c.getIntCol(NotesDb.COL_PINNED) == 1,
                    trashed = c.getIntCol(NotesDb.COL_TRASHED) == 1,
                    trashedAt = if (c.isNullCol(NotesDb.COL_TRASHED_AT)) null else c.getLongCol(NotesDb.COL_TRASHED_AT),
                    remindAt = if (c.isNullCol(NotesDb.COL_REMIND_AT)) null else c.getLongCol(NotesDb.COL_REMIND_AT),
                    repeat = c.getIntCol(NotesDb.COL_NOTE_REPEAT),
                    allDay = c.getIntCol(NotesDb.COL_NOTE_ALL_DAY) == 1,
                    createdAt = c.getLongCol(NotesDb.COL_CREATED),
                    updatedAt = c.getLongCol(NotesDb.COL_UPDATED),
                )
            }
        }

        // 待办：parent_id 也要转成 uuid，否则跨设备会指向错误的父项
        val todoUuidById = mutableMapOf<Long, String>()
        rdb.rawQuery("SELECT id, uuid FROM todos", null).use { c ->
            while (c.moveToNext()) todoUuidById[c.getLong(0)] = c.getString(1) ?: ""
        }
        val todos = mutableListOf<TodoDto>()
        rdb.rawQuery("SELECT * FROM todos ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val parentId = if (c.isNullCol(NotesDb.T_PARENT)) null else c.getLongCol(NotesDb.T_PARENT)
                todos += TodoDto(
                    uuid = c.getStringCol(NotesDb.T_UUID) ?: "",
                    parentUuid = parentId?.let { todoUuidById[it] },
                    title = c.getStringCol(NotesDb.COL_TITLE) ?: "",
                    done = c.getIntCol(NotesDb.T_DONE) == 1,
                    doneAt = if (c.isNullCol(NotesDb.T_DONE_AT)) null else c.getLongCol(NotesDb.T_DONE_AT),
                    dueAt = if (c.isNullCol(NotesDb.T_DUE_AT)) null else c.getLongCol(NotesDb.T_DUE_AT),
                    allDay = c.getIntCol(NotesDb.T_ALL_DAY) == 1,
                    repeat = c.getIntCol(NotesDb.T_REPEAT),
                    sortIndex = c.getIntCol(NotesDb.T_SORT),
                    trashed = c.getIntCol(NotesDb.T_TRASHED) == 1,
                    trashedAt = if (c.isNullCol(NotesDb.T_TRASHED_AT)) null else c.getLongCol(NotesDb.T_TRASHED_AT),
                    createdAt = c.getLongCol(NotesDb.COL_CREATED),
                    updatedAt = c.getLongCol(NotesDb.COL_UPDATED),
                )
            }
        }

        return BackupFile(
            schema = BackupFile.CURRENT_SCHEMA,
            appVersion = appVersion,
            exportedAt = now,
            folders = folders,
            notes = notes,
            todos = todos,
        )
    }

    /** 采集本地现状，供 [BackupMerger] 判断新增/覆盖/跳过。 */
    fun snapshot(db: NotesDb): BackupMerger.LocalSnapshot {
        val rdb = db.readableDatabase
        val noteIds = mutableMapOf<String, Long>()
        val noteUpdated = mutableMapOf<String, Long>()
        rdb.rawQuery("SELECT id, uuid, updated_at FROM notes", null).use { c ->
            while (c.moveToNext()) {
                val uuid = c.getString(1) ?: ""
                noteIds[uuid] = c.getLong(0)
                noteUpdated[uuid] = c.getLong(2)
            }
        }
        val todoIds = mutableMapOf<String, Long>()
        val todoUpdated = mutableMapOf<String, Long>()
        rdb.rawQuery("SELECT id, uuid, updated_at FROM todos", null).use { c ->
            while (c.moveToNext()) {
                val uuid = c.getString(1) ?: ""
                todoIds[uuid] = c.getLong(0)
                todoUpdated[uuid] = c.getLong(2)
            }
        }
        val folderIds = mutableMapOf<String, Long>()
        rdb.rawQuery("SELECT id, name FROM $TABLE_FOLDERS", null).use { c ->
            while (c.moveToNext()) folderIds[c.getString(1) ?: ""] = c.getLong(0)
        }
        return BackupMerger.LocalSnapshot(noteIds, noteUpdated, todoIds, todoUpdated, folderIds)
    }

    /** 合并结果统计，用于导入后给用户看"新增 N、更新 M、跳过 K"。 */
    data class Applied(val inserted: Int, val updated: Int, val skipped: Int, val warnings: List<String>)

    /**
     * 执行合并计划。整个导入跑在一个事务里：要么全部成功，要么全部回滚，
     * 不会留下"导入了一半"的库。
     *
     * [local] 必须传入 [snapshot] 的结果：子待办的父项可能只存在于本机、
     * 不在备份里，需要靠它把 uuid 解析回本地 id，否则会被误判为孤儿而提升为顶层。
     */
    fun apply(db: NotesDb, plan: BackupMerger.Plan, local: BackupMerger.LocalSnapshot): Applied {
        var inserted = 0
        var updated = 0
        var skipped = 0

        // uuid → 本地 id：先用本地已有映射打底，新建后再回填，供子待办解析父项
        val todoIdByUuid = local.todoIdsByUuid.toMutableMap()

        db.writableDatabase.beginTransaction()
        try {
            // 分类：先 Reuse 记下名字→id，再 Create 并回填
            val folderIdByName = mutableMapOf<String, Long>()
            plan.folders.forEach { action ->
                when (action) {
                    is BackupMerger.FolderAction.Reuse -> folderIdByName[action.name] = action.localId
                    is BackupMerger.FolderAction.Create -> {
                        val id = db.writableDatabase.insertWithOnConflict(
                            TABLE_FOLDERS, null,
                            ContentValues().apply {
                                put("name", action.name)
                                put("created_at", action.createdAt)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        // -1 表示同名已存在（并发/边界），回查其 id
                        folderIdByName[action.name] = if (id != -1L) id else queryFolderId(db, action.name)
                    }
                }
            }

            plan.notes.forEach { action ->
                when (action) {
                    is BackupMerger.NoteAction.Skip -> skipped++
                    is BackupMerger.NoteAction.Insert -> {
                        val folderId = action.folderName?.let { folderIdByName[it] }
                        val id = db.writableDatabase.insert("notes", null, noteValues(action.dto, folderId))
                        if (id != -1L) inserted++
                    }
                    is BackupMerger.NoteAction.Update -> {
                        val folderId = action.folderName?.let { folderIdByName[it] }
                        db.writableDatabase.update(
                            "notes", noteValues(action.dto, folderId), "id = ?",
                            arrayOf(action.localId.toString()),
                        )
                        updated++
                    }
                }
            }

            // 待办分两遍：先插/更新顶层与已存在的父项，再处理子项，保证 parentId 可解析
            val pendingChildren = mutableListOf<Pair<TodoDto, String?>>()
            plan.todos.forEach { action ->
                val dto = when (action) {
                    is BackupMerger.TodoAction.Insert -> action.dto
                    is BackupMerger.TodoAction.Update -> action.dto
                    is BackupMerger.TodoAction.Skip -> { skipped++; return@forEach }
                }
                val parentUuid = when (action) {
                    is BackupMerger.TodoAction.Insert -> action.parentUuid
                    is BackupMerger.TodoAction.Update -> action.parentUuid
                    else -> null
                }
                if (parentUuid == null) {
                    when (action) {
                        is BackupMerger.TodoAction.Insert -> {
                            val id = db.writableDatabase.insert("todos", null, todoValues(dto, null))
                            if (id != -1L) { inserted++; todoIdByUuid[dto.uuid] = id }
                        }
                        is BackupMerger.TodoAction.Update -> {
                            db.writableDatabase.update("todos", todoValues(dto, null), "id = ?", arrayOf(action.localId.toString()))
                            updated++
                            todoIdByUuid[dto.uuid] = action.localId
                        }
                        else -> Unit
                    }
                } else {
                    pendingChildren += dto to parentUuid
                }
            }
            pendingChildren.forEach { (dto, parentUuid) ->
                val parentId = todoIdByUuid[parentUuid]
                val existingLocalId = plan.todos
                    .filterIsInstance<BackupMerger.TodoAction.Update>()
                    .firstOrNull { it.dto.uuid == dto.uuid }?.localId
                if (parentId == null) {
                    // 父项不可解析：提升为顶层，不丢内容
                    if (existingLocalId != null) {
                        db.writableDatabase.update("todos", todoValues(dto, null), "id = ?", arrayOf(existingLocalId.toString()))
                        updated++
                    } else {
                        db.writableDatabase.insert("todos", null, todoValues(dto, null))
                        inserted++
                    }
                } else if (existingLocalId != null) {
                    db.writableDatabase.update("todos", todoValues(dto, parentId), "id = ?", arrayOf(existingLocalId.toString()))
                    updated++
                    todoIdByUuid[dto.uuid] = existingLocalId
                } else {
                    val id = db.writableDatabase.insert("todos", null, todoValues(dto, parentId))
                    if (id != -1L) { inserted++; todoIdByUuid[dto.uuid] = id }
                }
            }

            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }

        return Applied(inserted, updated, skipped, plan.warnings)
    }

    private fun queryFolderId(db: NotesDb, name: String): Long =
        db.readableDatabase.rawQuery("SELECT id FROM $TABLE_FOLDERS WHERE name = ?", arrayOf(name)).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }

    private fun noteValues(dto: NoteDto, folderId: Long?): ContentValues = ContentValues().apply {
        put("uuid", dto.uuid)
        put("kind", dto.kind)
        put("title", dto.title)
        put("body", dto.body)
        put("images", dto.images.joinToString("\n"))
        put("color", dto.colorIndex)
        put("folder_id", folderId)
        put("pinned", if (dto.pinned) 1 else 0)
        put("trashed", if (dto.trashed) 1 else 0)
        put("trashed_at", dto.trashedAt)
        put("remind_at", dto.remindAt)
        put("repeat_type", dto.repeat)
        put("all_day", if (dto.allDay) 1 else 0)
        put("created_at", dto.createdAt)
        // 铁律：原样写回，不得用 now
        put("updated_at", dto.updatedAt)
    }

    private fun todoValues(dto: TodoDto, parentId: Long?): ContentValues = ContentValues().apply {
        put("uuid", dto.uuid)
        put("parent_id", parentId)
        put("title", dto.title)
        put("done", if (dto.done) 1 else 0)
        put("done_at", dto.doneAt)
        put("due_at", dto.dueAt)
        put("remind_at", dto.dueAt)
        put("all_day", if (dto.allDay) 1 else 0)
        put("repeat_type", dto.repeat)
        put("sort_index", dto.sortIndex)
        put("trashed", if (dto.trashed) 1 else 0)
        put("trashed_at", dto.trashedAt)
        put("created_at", dto.createdAt)
        put("updated_at", dto.updatedAt)
    }

    /** 备份里出现的、本机没有的附件名，供 IO 层决定要打包/还原哪些文件。 */
    fun referencedAttachments(backup: BackupFile): Set<String> =
        backup.notes.flatMapTo(mutableSetOf()) { it.images }

    @Suppress("unused")
    private fun repeatNameOf(ordinal: Int): String =
        RepeatRule.entries.getOrNull(ordinal)?.name ?: RepeatRule.NONE.name
}

private fun android.database.Cursor.getIntCol(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.getLongCol(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun android.database.Cursor.getStringCol(name: String): String? = getString(getColumnIndexOrThrow(name))
private fun android.database.Cursor.isNullCol(name: String): Boolean = isNull(getColumnIndexOrThrow(name))
