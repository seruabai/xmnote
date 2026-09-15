package com.purenote.local.sync

/**
 * 同步引擎需要的"宿主"能力。
 *
 * 为什么不直接依赖 NoteRepository：把引擎与 Android/SQLite 解耦之后，
 * 引擎可以在 JVM 上被真实测试（用临时目录 + 假远端），
 * 而不是只能靠"编一次能过"来交付。
 */
interface CloudBackupOwner {

    /**
     * 生成一份**完整备份包**到 target，返回是否成功。
     * 实现内部必须是规范 §11.2 的一致快照（单事务读取）。
     */
    suspend fun exportBackup(target: java.io.File): Boolean

    /** 当前库身份（libraryId），用于识别"这份包是不是本机这个库的" */
    suspend fun libraryId(): String

    /** 应用版本号，写进包清单 */
    fun appVersion(): String

    /** 当前笔记条数，写进同步记录 */
    suspend fun noteCount(): Int

    /** 把整包读回校验（清单逐项 SHA-256）。失败抛异常。 */
    suspend fun verifyBackupFile(file: java.io.File): BackupInspection

    /** 同上，但从流读（云同步读回远端副本时用这个，避免先落盘再校验）。 */
    suspend fun verifyBackup(source: java.io.InputStream): BackupInspection

    /** 读出来的清单摘要 */
    data class BackupInspection(
        val backupId: String,
        val libraryId: String,
        val noteCount: Int,
        val attachmentCount: Int,
        val complete: Boolean,
    )
}
