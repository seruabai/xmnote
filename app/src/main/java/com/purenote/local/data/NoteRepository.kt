package com.purenote.local.data

import android.content.Context
import android.database.Cursor
import com.purenote.local.backup.BackupCodec
import com.purenote.local.backup.BackupFile
import com.purenote.local.backup.BackupIo
import com.purenote.local.core.ChecklistCodec
import com.purenote.local.core.NoteMarkup
import com.purenote.local.notify.ReminderJob
import com.purenote.local.notify.ReminderStore
import com.purenote.local.notify.ReminderTarget
import com.purenote.local.notify.Reminders
import com.purenote.local.data.store.DatabaseProvider
import com.purenote.local.data.store.StoreControl
import com.purenote.local.data.store.StorePointer
import com.purenote.local.core.TodoCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * [dbName] 仅测试需要（用独立库文件，避免污染真实数据）；应用内传 null，走活动存储指针。
 */
class NoteRepository(context: Context, dbName: String? = null) : ReminderStore {

    private val appContext = context.applicationContext


    /**
     * 活动存储（规范 §6.1 / §12）：全应用唯一的数据库提供者。
     * 测试传了固定库名时不启用指针机制。
     */
    private val provider: DatabaseProvider? =
        if (dbName == null) DatabaseProvider.forApp(appContext) else null

    private val control: StoreControl? get() = provider?.control

    private val pinned: NotesDb? = dbName?.let { NotesDb(appContext, it) }

    /** 单点访问。provider 模式下每次向它索取当前活动库（切换恢复后自动拿到新库）。 */
    internal val db: NotesDb get() = provider?.require() ?: pinned!!

    /** 当前存储代次；写入命令携带它，用于拒绝切换之后才到达的旧写入。 */
    val storeEpoch: String get() = provider?.storeEpoch ?: "test"

    /** 统一事务入口（规范 §6.1）。多步写操作必须整体提交或整体回滚。 */
    private val tx = DatabaseExecutor(helper = { db })

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
        /** 发起写入时的存储代次；与当前代次不符则拒绝（规范 §12 第 9 条）。 */
        storeEpoch: String = "",
    ): SaveResult = try {
        // 恢复完成后，旧页面的保存任务可能才到达。它的内容针对的是旧库，
        // 写进新库会造成"恢复后又被旧内容覆盖"。必须在任何写入之前拦下。
        if (storeEpoch.isNotBlank() && storeEpoch != this.storeEpoch) {
            return SaveResult.StoreChanged
        }
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
                    // 规范 §9：期望的提醒状态与数据在**同一个事务**里落库。
                    // 保存失败就没有这条期望，系统闹钟也就不会被改成与数据不一致的样子。
                    ReminderJobsTable.upsert(
                        database, Reminders.KIND_NOTE, id, outcome.revision, remindAt, now,
                    )
                    // 规范 §10：正文引用的提交必须在文件完整发布之后（发布已在阶段 D 保证），
                    // 这里在同一个事务里把"当前版本引用了哪些附件"记下来。
                    val referenced = (NoteMarkup.imageNames(encodedBody) + encodedImages.split('\n'))
                        .filter { it.isNotBlank() }
                        .toSet()
                    referenced.forEach {
                        AttachmentsTable.addNoteRef(database, id, it)
                        // 同时记进历史版本引用：这个版本以后可能被回滚，附件不能算垃圾
                        AttachmentsTable.addVersionRef(database, id, outcome.revision, it)
                    }
                    AttachmentsTable.pruneNoteRefs(database, id, referenced)
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
            bumpRootRevisionAndSnapshot(database, parentId, now)
        }

    /**
     * 根待办发生聚合变更时：修订号 +1，并留一份**含子项**的完整快照。
     *
     * 之前 todo 的 revision 从来没被改过（恒为 1），后果不只是历史缺失：
     * reminder_jobs 记的是 expected_revision，待办改过之后这个值不变，
     * 于是"旧任务不能覆盖新提醒"对**待办**根本失效——一条过期的提醒期望
     * 会被当成仍然有效而照常推送。规范 §5.2 把根待办的修订号定义为
     * 父子整体的冲突边界，正是为此。
     */
    private fun bumpRootRevisionAndSnapshot(
        database: android.database.sqlite.SQLiteDatabase,
        rootId: Long,
        now: Long,
    ) {
        val revision = database.rawQuery("SELECT revision FROM todos WHERE id = ?", arrayOf(rootId.toString()))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L } + 1
        database.execSQL("UPDATE todos SET revision = ? WHERE id = ?", arrayOf(revision, rootId))
        TodoVersionsTable.insert(
            db = database,
            rootId = rootId,
            revision = revision,
            snapshotJson = todoSnapshotJson(database, rootId, revision, now),
            reason = "save",
            operationId = "",
            now = now,
        )
    }

    /** 快照存原始存储值（含全部子项），不做有损的 UI 模型往返。 */
    private fun todoSnapshotJson(
        database: android.database.sqlite.SQLiteDatabase,
        rootId: Long,
        revision: Long,
        now: Long,
    ): String = org.json.JSONObject().apply {
        put("rootId", rootId)
        put("revision", revision)
        val children = org.json.JSONArray()
        database.rawQuery(
            "SELECT id, title, done, due_at, sort_index FROM todos WHERE parent_id = ? ORDER BY sort_index, id",
            arrayOf(rootId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                children.put(
                    org.json.JSONObject().apply {
                        put("id", c.getLong(0))
                        put("title", c.getString(1))
                        put("done", c.getInt(2) == 1)
                        put("dueAt", if (c.isNull(3)) org.json.JSONObject.NULL else c.getLong(3))
                        put("sortIndex", c.getInt(4))
                    },
                )
            }
        }
        put("children", children)
        put("at", now)
    }.toString()

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

    // ---- 规范 §12：恢复到新一代存储，验证通过后才切换活动指针 ----

    sealed interface RestoreOutcome {
        data class Ok(val inserted: Int, val updated: Int, val newEpoch: String, val warnings: List<String>) : RestoreOutcome
        data class Failed(val message: String) : RestoreOutcome
    }

    /**
     * 整体恢复（规范 §12）：**新一代存储 + 小型活动指针**，不用"就地覆盖"冒充原子操作。
     *
     * 关键性质：
     *  - 活动库在整个过程中**始终可读**，指针切换前它一直是权威；
     *  - 新库建在另一个文件里，导入后逐项验证，验证不通过就丢弃新库、保留旧库；
     *  - 切换点是一次 AtomicFile 写（active.json）；
     *  - 旧 epoch 的库文件与附件一律保留，不在同一次启动里清理。
     */
    suspend fun restoreIntoNewStore(source: InputStream): RestoreOutcome = withContext(Dispatchers.IO) {
        val p = provider ?: return@withContext RestoreOutcome.Failed("测试模式下不支持切换存储")
        val c = control ?: return@withContext RestoreOutcome.Failed("测试模式下不支持切换存储")

        // 先把备份落到临时文件：既要解析校验、又要导两次（预检 + 实际导入）
        val staged = File(appContext.cacheDir, "restore-${System.currentTimeMillis()}.zip")
        val fromEpoch = p.storeEpoch
        val newEpoch = c.newEpoch()
        val pointer = StorePointer(epoch = newEpoch, dbName = c.dbNameFor(newEpoch))
        val target = appContext.getDatabasePath(pointer.dbName)

        try {
            source.use { input -> staged.outputStream().use { input.copyTo(it) } }

            // 第 1 步：校验包（清单逐项核对），不通过直接拒绝，旧库分毫不动
            val declared = staged.inputStream().use { BackupIo(appContext).readBackup(it) }

            // 第 2 步：在**新文件**里建库并导入
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            val newDb = p.openForVerification(pointer)
            val applied = try {
                val imported = staged.inputStream().use { BackupIo(appContext).import(it, newDb) }

                // 第 3 步：验证结构与业务关系
                val version = newDb.readableDatabase.version
                if (version != NotesDb.DB_VERSION) {
                    error("新库 schema 版本为 $version，期望 ${NotesDb.DB_VERSION}")
                }
                val counts = countEntities(newDb)
                val expectedNotes = declared.notes.size
                if (counts.first < expectedNotes) {
                    error("新库只有 ${counts.first} 条笔记，备份声明 $expectedNotes 条")
                }
                imported
            } finally {
                // 必须先关掉：同一个文件不能同时被两个 helper 打开着去切换
                newDb.close()
            }

            // 第 6 步：先记恢复意图，再发布指针
            c.markRecoveryPrepared(fromEpoch, newEpoch)

            // 第 7/8 步：切换活动指针并打开新库核对
            p.switchTo(pointer)
            val reopened = p.require()
            val reopenedEpoch = p.storeEpoch
            if (reopenedEpoch != newEpoch) {
                error("切换后活动 epoch 为 $reopenedEpoch，期望 $newEpoch")
            }
            val finalCount = countEntities(reopened).first
            if (finalCount < declared.notes.size) {
                error("切换后只读到 $finalCount 条笔记")
            }

            // 第 9 步：恢复完成；旧 epoch 与恢复前备份一律保留
            c.clearRecovery()
            RestoreOutcome.Ok(applied.inserted, applied.updated, newEpoch, applied.warnings)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cleanupFailedRestore(c, target)
            throw e
        } catch (e: Throwable) {
            // 任何中断都只丢弃"未完成的新库"，活动库与旧 epoch 保持原样
            cleanupFailedRestore(c, target)
            RestoreOutcome.Failed(e.message ?: e::class.java.simpleName)
        } finally {
            staged.delete()
        }
    }

    private fun cleanupFailedRestore(control: StoreControl, target: File) {
        // 只有"指针还没切过去"时才丢弃新库；已切换则不回滚（旧代仍保留，可人工选择）
        val active = runCatching { control.activePointer().dbName }.getOrNull()
        if (active != target.name) {
            runCatching { target.delete() }
            runCatching { File(target.absolutePath + "-journal").delete() }
            runCatching { control.clearRecovery() }
        }
    }

    private fun countEntities(db: NotesDb): Pair<Int, Int> {
        val notes = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null)
            .use { it.moveToFirst(); it.getInt(0) }
        val todos = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM todos", null)
            .use { it.moveToFirst(); it.getInt(0) }
        return notes to todos
    }

    // ---- 测试用只读探针（不影响生产路径）----

    // ---- 规范 §10：附件元数据 ----

    /**
     * 登记一个已发布的附件。由 [com.purenote.local.core.ImageStore] 在发布成功后回调。
     * 记录的是**字节级事实**（大小 + SHA-256），用于日后判断文件是否被外部改动或截断。
     */
    suspend fun recordAttachment(
        attachmentId: String,
        relativeName: String,
        sizeBytes: Long,
        sha256: String,
        mime: String = "image/jpeg",
    ) = tx.write { database ->
        AttachmentsTable.upsert(
            database, attachmentId, relativeName, sizeBytes, sha256, mime, System.currentTimeMillis(),
        )
    }

    internal fun debugAttachmentCount(relativeName: String): Int =
        AttachmentsTable.countFor(db.readableDatabase, relativeName)

    // ---- 规范 §9：提醒协调所需的数据访问 ----

    override suspend fun pendingJobs(): List<ReminderJob> = tx.read { ReminderJobsTable.pending(it) }

    override suspend fun knownTargets(): List<Pair<String, Long>> = tx.read { ReminderJobsTable.knownTargets(it) }

    override suspend fun desiredTargets(): List<ReminderTarget> = tx.read { database ->
        val out = mutableListOf<ReminderTarget>()
        database.rawQuery(
            "SELECT id, revision, remind_at FROM notes WHERE trashed = 0",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += ReminderTarget(Reminders.KIND_NOTE, c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
            }
        }
        // 只有顶层待办触发提醒；子项跟随父项
        database.rawQuery(
            "SELECT id, revision, remind_at FROM todos WHERE trashed = 0 AND parent_id IS NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += ReminderTarget(Reminders.KIND_TODO, c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
            }
        }
        out
    }

    override suspend fun findTarget(kind: String, targetId: Long): ReminderTarget? = tx.read { database ->
        val table = if (kind == Reminders.KIND_TODO) "todos" else "notes"
        val extra = if (kind == Reminders.KIND_TODO) " AND parent_id IS NULL" else ""
        database.rawQuery(
            "SELECT id, revision, remind_at FROM $table WHERE id = ? AND trashed = 0$extra",
            arrayOf(targetId.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                ReminderTarget(kind, c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
            } else {
                null
            }
        }
    }

    override suspend fun upsertJob(kind: String, targetId: Long, revision: Long, remindAt: Long?) =
        tx.write { database ->
            ReminderJobsTable.upsert(database, kind, targetId, revision, remindAt, System.currentTimeMillis())
        }

    override suspend fun markApplied(kind: String, targetId: Long) = tx.write { database ->
        ReminderJobsTable.markApplied(database, kind, targetId, System.currentTimeMillis())
    }

    /** 当前修订号；记录不存在时返回 null（规范 §7 的 expectedRevision 基线） */
    suspend fun noteRevision(noteId: Long): Long? = tx.read { database ->
        NoteStore.readRevision(noteId, database)
    }

    internal fun debugRevision(noteId: Long): Long =
        db.readableDatabase.rawQuery("SELECT revision FROM notes WHERE id = ?", arrayOf(noteId.toString()))
            .use { c -> c.moveToFirst(); c.getLong(0) }

    /** 记录数（规范 §14 异常大规模删改检测的输入） */
    suspend fun noteCount(): Int = tx.read { database ->
        database.rawQuery("SELECT COUNT(*) FROM notes", null).use { it.moveToFirst(); it.getInt(0) }
    }

    /**
     * 内容变更戳：notes/todos 里最大的 updated_at。
     * 规范 §14 要求"有变化才备份"，用它判断自上次备份以来是否真的改过东西。
     */
    suspend fun contentStamp(): Long = tx.read { database ->
        val notes = database.rawQuery("SELECT IFNULL(MAX(updated_at), 0) FROM notes", null)
            .use { it.moveToFirst(); it.getLong(0) }
        val todos = database.rawQuery("SELECT IFNULL(MAX(updated_at), 0) FROM todos", null)
            .use { it.moveToFirst(); it.getLong(0) }
        maxOf(notes, todos)
    }

    /** 库身份（规范 §14：每个 libraryId 一个唯一的周期任务） */
    suspend fun libraryId(): String = tx.read { database -> BackupCodec.libraryId(db) }

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
