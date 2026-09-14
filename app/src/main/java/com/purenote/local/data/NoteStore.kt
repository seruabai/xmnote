package com.purenote.local.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/** 条件更新（CAS）结果 */
sealed interface CasOutcome {
    /** 更新成功，产生新修订号 */
    data class Updated(val revision: Long) : CasOutcome
    /** 预期修订号与库中实际值不符 */
    data class Conflict(val actualRevision: Long) : CasOutcome
    /** 记录不存在（或已被永久删除） */
    data object NotFound : CasOutcome
}

/** 已提交操作的记录，用于幂等重放（规范 §7） */
data class OperationRecord(val operationId: String, val resultRevision: Long)

/**
 * 修订号、条件更新与幂等操作（规范 §7）。
 *
 * 原生 Android 事务与线程绑定，因此这里全部是**非 suspend** 的、
 * 要求调用方传入当前事务的 [SQLiteDatabase]。
 */
internal object NoteStore {

    fun readRevision(noteId: Long, database: SQLiteDatabase): Long? =
        database.rawQuery("SELECT revision FROM notes WHERE id = ?", arrayOf(noteId.toString()))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /**
     * 条件更新：仅当库中 revision 等于 [expectedRevision] 时才写入，并让其自增。
     * 影响行数校验为"恰好 1"，否则说明条件写错了（主键 + 版本号不可能命中多行）。
     */
    fun updateNoteCas(
        noteId: Long,
        expectedRevision: Long,
        values: ContentValues,
        database: SQLiteDatabase,
    ): CasOutcome {
        values.put("revision", expectedRevision + 1)
        val affected = database.update(
            "notes",
            values,
            "id = ? AND revision = ? AND trashed = 0",
            arrayOf(noteId.toString(), expectedRevision.toString()),
        )
        return when {
            affected == 1 -> CasOutcome.Updated(expectedRevision + 1)
            affected == 0 -> {
                val actual = readRevision(noteId, database)
                if (actual == null) CasOutcome.NotFound else CasOutcome.Conflict(actual)
            }
            else -> error("条件更新影响到 $affected 行，条件写错了")
        }
    }

    /** 查已提交的同名操作；请求哈希不一致说明同 ID 被用于不同请求，必须拒绝而不是照常执行。 */
    fun findOperation(
        operationId: String,
        requestHash: String,
        database: SQLiteDatabase,
    ): OperationRecord? =
        database.rawQuery(
            "SELECT request_hash, result_revision FROM operations WHERE operation_id = ?",
            arrayOf(operationId),
        ).use { c ->
            if (!c.moveToFirst()) null
            else {
                val stored = c.getString(0)
                require(stored == requestHash) {
                    "operationId $operationId 已用于不同请求（哈希不一致），拒绝执行"
                }
                OperationRecord(operationId, c.getLong(1))
            }
        }

    fun recordOperation(
        operationId: String,
        requestHash: String,
        entityType: String,
        entityId: Long,
        resultRevision: Long,
        now: Long,
        database: SQLiteDatabase,
    ) {
        database.insertWithOnConflict(
            "operations",
            null,
            ContentValues().apply {
                put("operation_id", operationId)
                put("request_hash", requestHash)
                put("entity_type", entityType)
                put("entity_id", entityId)
                put("result_revision", resultRevision)
                put("committed_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun insertNoteVersion(
        noteId: Long,
        revision: Long,
        snapshotJson: String,
        reason: String,
        operationId: String,
        now: Long,
        database: SQLiteDatabase,
    ) {
        database.insertWithOnConflict(
            "note_versions",
            null,
            ContentValues().apply {
                put("note_id", noteId)
                put("revision", revision)
                put("snapshot_format_version", 1)
                put("snapshot_json", snapshotJson)
                put("reason", reason)
                put("operation_id", operationId)
                put("created_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** 规范 §7：request_hash 基于固定字段顺序的规范序列化 */
    fun requestHash(vararg parts: Any?): String {
        val canonical = parts.joinToString("\u0001") { it?.toString() ?: "\u0000" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun cursorToRevision(c: Cursor): Long = c.getLong(c.getColumnIndexOrThrow("revision"))
}
