package com.purenote.local.backup

import kotlinx.serialization.Serializable

/**
 * 备份文件（`purenote-backup-*.zip` 里的 `backup.json`）的数据结构。
 *
 * 设计要点：
 * 1. **不存本地自增 id**。所有跨表引用一律用 uuid：
 *    - note.folderUuid → folder.uuid
 *    - todo.parentUuid → todo.uuid
 *    导入时按 uuid 重新分配本地 id，跨设备才不会错位（folder 目前没有 uuid，
 *    备份里单独带 uuid，导入时按名字匹配已有分类）。
 * 2. `schema` 是格式版本，导入时据此决定要不要迁移/拒绝；`appVersion` 仅作排查线索。
 * 3. 附件不进 JSON，放在 zip 的 `attachments/` 目录，这里只存文件名清单。
 */
@Serializable
data class BackupFile(
    val schema: Int = CURRENT_SCHEMA,
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    val folders: List<FolderDto> = emptyList(),
    val notes: List<NoteDto> = emptyList(),
    val todos: List<TodoDto> = emptyList(),
) {
    companion object {
        /**
         * 备份格式版本。结构不兼容地变更时才 +1。
         *
         * v2：NoteDto 增加 [NoteDto.bodyFormatVersion]。
         * 起因是一个真实缺口——正文格式由 PUA 私有标记改为 Markdown 时，
         * 库内迁移只挂在 `onUpgrade(oldVersion < 8)` 上，而**新装设备走 onCreate
         * 根本不经过 onUpgrade**。于是把 v1.2.20 的备份恢复到全新安装上，
         * 旧标记会逐字节落库并永久残留（PUA 不可见、☐ 不再被识别为勾选框、
         * [img:] 只显示字面量）。备份格式号当时没有 +1，导入端也无从判断。
         *
         * v1 备份没有该字段，反序列化会取默认值，即"旧格式"，导入时被正确迁移。
         */
        const val CURRENT_SCHEMA = 2
    }
}

@Serializable
data class FolderDto(
    /** 备份内稳定标识：优先用已有 uuid，没有就用名字派生（见 BackupCodec.folderKey） */
    val uuid: String,
    val name: String,
    val createdAt: Long,
)

@Serializable
data class NoteDto(
    val uuid: String,
    val kind: Int,
    val title: String,
    /**
     * **原样保存的数据库 body 列**（TEXT 是正文；CHECKLIST 是 ChecklistCodec 的紧凑编码）。
     * 导入时优先用这个值逐字节还原，规避编解码往返的任何风险。
     */
    val body: String,
    /** 清单条目（仅 kind=CHECKLIST 有值）。保留它是为了备份可读、以及后续云同步复用。 */
    val items: List<ChecklistItemDto> = emptyList(),
    /**
     * [body] 这一列所用的正文格式版本：
     * 1 = PUA 私有标记（v1.2.20 及更早），2 = 标准 Markdown。
     * 默认取 1：schema=1 的旧备份写于 Markdown 化之前，导入时必须迁移。
     * 仅对 kind=TEXT 有意义（CHECKLIST 的 body 是 ChecklistCodec 编码）。
     */
    val bodyFormatVersion: Int = 1,
    val images: List<String> = emptyList(),
    val colorIndex: Int = 0,
    /** 引用 FolderDto.uuid；无分类为 null */
    val folderUuid: String? = null,
    val pinned: Boolean = false,
    val trashed: Boolean = false,
    val trashedAt: Long? = null,
    val remindAt: Long? = null,
    val repeat: Int = 0,
    val allDay: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class ChecklistItemDto(
    val text: String,
    val done: Boolean = false,
)

@Serializable
data class TodoDto(
    val uuid: String,
    /** 引用父待办的 uuid；顶层为 null */
    val parentUuid: String? = null,
    val title: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val dueAt: Long? = null,
    val allDay: Boolean = false,
    val repeat: Int = 0,
    val sortIndex: Int = 0,
    val trashed: Boolean = false,
    val trashedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
