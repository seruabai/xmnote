package com.purenote.local.feature.backup

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 落盘用的状态 DTO。用 kotlinx-serialization 而不是 org.json：
 *  org.json 属于 Android 框架，在 JVM 单元测试里是抛异常的桩，无法直接测。 */
@Serializable
private data class StoredState(
    val lastSuccessAt: Long = 0L,
    val lastNoteCount: Int = -1,
    val rotationPaused: Boolean = false,
    val pinnedNames: List<String> = emptyList(),
    val lastContentStamp: Long = -1L,
)

/** 自动备份的运行状态（规范 §14：必须显示**实际成功时间**，不承诺准点执行）。 */
data class LocalBackupState(
    val lastSuccessAt: Long = 0L,
    val lastNoteCount: Int = -1,
    /** 检测到异常大规模删改时暂停普通轮换 */
    val rotationPaused: Boolean = false,
    /**
     * 被固定、不参与轮换的备份名。
     * 用名单而不是改名：改名会让文件不再匹配备份后缀，
     * 从此既轮换不到、也在列表里看不见。
     */
    val pinnedNames: List<String> = emptyList(),
    /** 上次备份时的内容变更戳；用于"有变化才备份" */
    val lastContentStamp: Long = -1L,
)

/**
 * 设备内自动备份仓库（规范 §12 布局中的 `noBackupFilesDir/local-backups/`）。
 *
 * 放在 noBackupFilesDir：这些是"设备内轮换副本"，
 * 不应该被 Android 自动云备份再传一份（那会跟用户自己的外部备份重复且难以解释）。
 *
 * 目录由构造参数注入，因此轮换逻辑可以在 JVM 上直接测。
 */
class LocalBackupStore(private val dir: File) {

    private val stateFile get() = File(dir, STATE_FILE)
    private val stagingDir get() = File(dir, "staging")

    fun list(): List<BackupFileInfo> {
        val pinned = readState().pinnedNames.toSet()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: return emptyList()
        return files.map { f ->
            BackupFileInfo(
                name = f.name,
                createdAt = parseCreatedAt(f),
                sizeBytes = f.length(),
                kind = kindOf(f.name),
                pinned = f.name in pinned,
            )
        }
    }

    /** 把某份备份固定住（异常删改时保留异常发生前的恢复点）。 */
    fun pin(name: String) {
        val state = readState()
        if (name in state.pinnedNames) return
        writeState(state.copy(pinnedNames = state.pinnedNames + name))
    }

    /** 新建一个待写入的目标文件（先写到 staging，校验通过后再发布）。 */
    fun newStagingFile(now: Long): File {
        stagingDir.mkdirs()
        return File(stagingDir, "backup-$now$STAGING_SUFFIX")
    }

    /**
     * 把已校验通过的暂存文件发布为正式备份。
     * 规范 §14 的执行顺序：新包成功 -> 校验成功 -> **记录发布成功** -> 才决定旧包是否清理。
     */
    fun publish(staged: File, kind: BackupKind, now: Long): File {
        dir.mkdirs()
        val prefix = when (kind) {
            BackupKind.AUTO -> AUTO_PREFIX
            BackupKind.MANUAL -> MANUAL_PREFIX
            BackupKind.PRE_MIGRATION -> PRE_MIGRATION_PREFIX
        }
        val target = File(dir, "$prefix$now$EXTENSION")
        if (target.exists()) target.delete()
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
        return target
    }

    fun delete(files: List<BackupFileInfo>): Int =
        files.count { File(dir, it.name).delete() }

    /** 按保留策略轮换。返回被删除的文件；轮换被暂停时返回空列表。 */
    fun rotate(now: Long): List<BackupFileInfo> {
        val state = readState()
        if (state.rotationPaused) return emptyList()
        val doomed = BackupRetention.selectForDeletion(list(), now)
        delete(doomed)
        return doomed
    }

    fun readState(): LocalBackupState = runCatching {
        if (!stateFile.exists()) LocalBackupState()
        else Json { ignoreUnknownKeys = true }.decodeFromString<StoredState>(stateFile.readText()).let {
            LocalBackupState(
                lastSuccessAt = it.lastSuccessAt,
                lastNoteCount = it.lastNoteCount,
                rotationPaused = it.rotationPaused,
                pinnedNames = it.pinnedNames,
                lastContentStamp = it.lastContentStamp,
            )
        }
    }.getOrElse { LocalBackupState() }

    fun writeState(state: LocalBackupState) {
        dir.mkdirs()
        val tmp = File(dir, STATE_FILE + ".tmp")
        tmp.writeText(
            Json.encodeToString(
                StoredState(
                    lastSuccessAt = state.lastSuccessAt,
                    lastNoteCount = state.lastNoteCount,
                    rotationPaused = state.rotationPaused,
                    pinnedNames = state.pinnedNames,
                    lastContentStamp = state.lastContentStamp,
                ),
            ),
        )
        if (stateFile.exists()) stateFile.delete()
        tmp.renameTo(stateFile)
    }

    /** 取消所有轮换保护之外的残留暂存文件。 */
    fun cleanupStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    private fun parseCreatedAt(f: File): Long {
        val digits = f.nameWithoutExtension.trimStart('_').filter { it.isDigit() }
        return digits.toLongOrNull() ?: f.lastModified()
    }

    private fun kindOf(name: String): BackupKind = when {
        name.startsWith(MANUAL_PREFIX) -> BackupKind.MANUAL
        name.startsWith(PRE_MIGRATION_PREFIX) -> BackupKind.PRE_MIGRATION
        else -> BackupKind.AUTO
    }

    companion object {
        const val EXTENSION = ".purenote.zip"
        const val STAGING_SUFFIX = ".staging"
        const val AUTO_PREFIX = "auto-"
        const val MANUAL_PREFIX = "manual-"
        const val PRE_MIGRATION_PREFIX = "premigration-"
        private const val STATE_FILE = "state.json"

        fun forApp(context: Context): LocalBackupStore =
            LocalBackupStore(File(context.noBackupFilesDir, "local-backups"))
    }
}
