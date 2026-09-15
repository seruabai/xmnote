package com.purenote.local.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** 附件元数据（规范 §10）：发布成功后登记，供引用保护与备份校验使用。 */
internal object AttachmentsTable {

    fun upsert(
        db: SQLiteDatabase,
        attachmentId: String,
        relativeName: String,
        sizeBytes: Long,
        sha256: String,
        mime: String,
        now: Long,
    ) {
        db.insertWithOnConflict(
            "attachments",
            null,
            ContentValues().apply {
                put("attachment_id", attachmentId)
                put("relative_name", relativeName)
                put("size_bytes", sizeBytes)
                put("sha256", sha256)
                put("mime", mime)
                put("state", "published")
                put("created_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 记录"当前版本引用了哪些附件"。只增不删交给清理逻辑，这里先保证引用被记下来。 */
    fun addNoteRef(db: SQLiteDatabase, noteId: Long, attachmentId: String) {
        db.insertWithOnConflict(
            "note_attachment_refs",
            null,
            ContentValues().apply {
                put("note_id", noteId)
                put("attachment_id", attachmentId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /**
     * 清理"当前版本已不再引用"的引用记录，但**保留历史版本仍在引用的那些**。
     * 这正是当初必须把 note_attachment_refs 与 version_attachment_refs 分开的原因：
     * 直接按当前正文删引用，一旦用户回滚历史版本，附件就变成孤儿了。
     */
    fun pruneNoteRefs(db: SQLiteDatabase, noteId: Long, keep: Set<String>) {
        if (keep.isEmpty()) {
            db.delete("note_attachment_refs", "note_id = ?", arrayOf(noteId.toString()))
            return
        }
        val placeholders = keep.joinToString(",") { "?" }
        db.delete(
            "note_attachment_refs",
            "note_id = ? AND attachment_id NOT IN ($placeholders)",
            arrayOf(noteId.toString()) + keep.toTypedArray(),
        )
    }

    /**
     * 记录"某个历史版本引用了哪些附件"（规范 §5.2/§10）。
     *
     * 与 note_attachment_refs 分开存是必须的：当前版本的引用可以被清理，
     * 但历史版本一旦还引用着某个附件，那个文件就不能被当成垃圾删掉——
     * 否则用户回滚到旧版本时会看到一堆破图。
     */
    fun addVersionRef(db: SQLiteDatabase, noteId: Long, revision: Long, attachmentId: String) {
        db.insertWithOnConflict(
            "version_attachment_refs",
            null,
            ContentValues().apply {
                put("note_id", noteId)
                put("revision", revision)
                put("attachment_id", attachmentId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun countFor(db: SQLiteDatabase, relativeName: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM attachments WHERE relative_name = ?", arrayOf(relativeName))
            .use { it.moveToFirst(); it.getInt(0) }
}
