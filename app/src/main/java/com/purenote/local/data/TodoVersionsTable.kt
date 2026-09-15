package com.purenote.local.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** 待办聚合的历史快照（规范 §5.2）。根待办的修订号是父子整体的冲突边界。 */
internal object TodoVersionsTable {

    fun insert(
        db: SQLiteDatabase,
        rootId: Long,
        revision: Long,
        snapshotJson: String,
        reason: String,
        operationId: String,
        now: Long,
    ) {
        db.insertWithOnConflict(
            "todo_versions",
            null,
            ContentValues().apply {
                put("todo_root_id", rootId)
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

    fun countFor(db: SQLiteDatabase, rootId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM todo_versions WHERE todo_root_id = ?", arrayOf(rootId.toString()))
            .use { it.moveToFirst(); it.getInt(0) }
}
