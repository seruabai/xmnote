package com.purenote.local.backup

/**
 * 备份合并的**纯逻辑**（不依赖 Android，可单元测试）。
 *
 * 输入：本地现状（uuid→updatedAt 的快照）+ 备份文件 → 输出一份"该做什么"的计划。
 * 真正的数据库写入由 applier 执行，这样合并规则可以独立测透。
 *
 * 规则：
 * - **分类**：以名字为准去重（DB 的 folders.name 是 UNIQUE，且没有 uuid）。
 * - **笔记/待办**：以 uuid 为准。本地没有→新增；本地已有→比 updatedAt（LWW），
 *   备份更新才覆盖，相同或更旧一律跳过。相同时间戳跳过 → 重复导入同一份备份是幂等的。
 * - **导入不得改写 updatedAt**，否则幂等性与 LWW 都失效（applier 必须遵守）。
 * - **超过两级不处理**：子待办指向的父项若在本机也找不到，提升为顶层项并计入警告，
 *   宁可变形态也不丢用户内容。
 */
object BackupMerger {

    /** 合并计划：applier 按顺序执行；分类先于笔记/待办。 */
    data class Plan(
        val folders: List<FolderAction>,
        val notes: List<NoteAction>,
        val todos: List<TodoAction>,
        val warnings: List<String>,
    ) {
        val isEmpty: Boolean
            get() = folders.none { it is FolderAction.Create } &&
                notes.none { it is NoteAction.Insert || it is NoteAction.Update } &&
                todos.none { it is TodoAction.Insert || it is TodoAction.Update }
    }

    sealed interface FolderAction {
        /** 新建分类（本地没有同名） */
        data class Create(val name: String, val createdAt: Long) : FolderAction
        /** 本地已有同名分类，复用其本地 id */
        data class Reuse(val name: String, val localId: Long) : FolderAction
    }

    sealed interface NoteAction {
        data class Insert(val dto: NoteDto, val folderName: String?) : NoteAction
        data class Update(val localId: Long, val dto: NoteDto, val folderName: String?) : NoteAction
        data class Skip(val uuid: String) : NoteAction
    }

    sealed interface TodoAction {
        data class Insert(val dto: TodoDto, val parentUuid: String?) : TodoAction
        data class Update(val localId: Long, val dto: TodoDto, val parentUuid: String?) : TodoAction
        data class Skip(val uuid: String) : TodoAction
    }

    /** 本地现状快照（uuid → 本地 id、updatedAt；分类名 → 本地 id）。 */
    data class LocalSnapshot(
        val noteIdsByUuid: Map<String, Long>,
        val noteUpdatedAtByUuid: Map<String, Long>,
        val todoIdsByUuid: Map<String, Long>,
        val todoUpdatedAtByUuid: Map<String, Long>,
        val folderIdByName: Map<String, Long>,
    )

    fun plan(backup: BackupFile, local: LocalSnapshot): Plan {
        val warnings = mutableListOf<String>()

        // ---- 分类：按名字去重 ----
        val folderNameByUuid = mutableMapOf<String, String>()
        val folderActions = mutableListOf<FolderAction>()
        backup.folders.forEach { f ->
            val name = f.name.trim()
            if (name.isEmpty()) {
                warnings += "备份中存在名字为空的分类，已跳过"
                return@forEach
            }
            folderNameByUuid[f.uuid] = name
            val existing = local.folderIdByName[name]
            folderActions += if (existing != null) FolderAction.Reuse(name, existing)
            else FolderAction.Create(name, f.createdAt)
        }

        fun resolveFolderName(folderUuid: String?): String? {
            if (folderUuid == null) return null
            val name = folderNameByUuid[folderUuid]
            if (name == null) warnings += "笔记引用的分类 $folderUuid 不在备份中，取消其分类"
            return name
        }

        // ---- 笔记 ----
        val noteActions = backup.notes.map { n ->
            val folderName = resolveFolderName(n.folderUuid)
            if (n.uuid.isBlank()) {
                warnings += "笔记「${n.title.take(20)}」缺少 uuid，按新笔记导入"
                return@map NoteAction.Insert(n, folderName)
            }
            val localId = local.noteIdsByUuid[n.uuid]
            if (localId == null) {
                NoteAction.Insert(n, folderName)
            } else {
                val localUpdated = local.noteUpdatedAtByUuid[n.uuid] ?: 0L
                if (n.updatedAt > localUpdated) NoteAction.Update(localId, n, folderName)
                else NoteAction.Skip(n.uuid)
            }
        }

        // ---- 待办：先收集备份内所有 uuid，用于判断父项是否可解析 ----
        val backupTodoUuids = backup.todos.mapTo(mutableSetOf()) { it.uuid }
        val knownTodoUuids = backupTodoUuids + local.todoIdsByUuid.keys

        val parentUuidByChild = mutableMapOf<String, String?>()
        val todoActions = backup.todos.map { t ->
            var parentUuid = t.parentUuid
            if (parentUuid != null && parentUuid !in knownTodoUuids) {
                warnings += "子待办「${t.title.take(20)}」的父项不存在，已提升为顶层待办"
                parentUuid = null
            }
            parentUuidByChild[t.uuid] = parentUuid

            if (t.uuid.isBlank()) {
                warnings += "待办「${t.title.take(20)}」缺少 uuid，按新待办导入"
                return@map TodoAction.Insert(t, parentUuid)
            }
            val localId = local.todoIdsByUuid[t.uuid]
            if (localId == null) {
                TodoAction.Insert(t, parentUuid)
            } else {
                val localUpdated = local.todoUpdatedAtByUuid[t.uuid] ?: 0L
                if (t.updatedAt > localUpdated) TodoAction.Update(localId, t, parentUuid)
                else TodoAction.Skip(t.uuid)
            }
        }

        return Plan(folderActions, noteActions, todoActions, warnings)
    }
}
