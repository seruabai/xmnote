package com.purenote.local.sync

/**
 * 云端产物的命名与目录约定（纯函数，可在 JVM 上直接测）。
 *
 * 布局（相对仓库根）：
 *   <remoteDir>/purenote-<yyyyMMdd-HHmm>.purenote.zip
 *
 * 刻意**不做** SYNC_DESIGN §4.3 的 notes/<uuid>.json 逐条布局：
 * 阶段 H 的交付边界是"完整包传输"，逐条布局是双向同步的前提，
 * 规范 §17 明确要求"不顺带实现未经设计的双向同步"。
 * 将来做双向同步时新增布局即可，不推翻这里的约定。
 */
object SyncContract {

    const val FILE_EXTENSION = ".purenote.zip"

    /** 文件名前缀：纯 ASCII，避免不同云盘对中文文件名的转义差异 */
    const val FILE_PREFIX = "purenote-"

    /** 时间戳格式 yyyyMMdd-HHmm（本地时区，用户看得懂） */
    fun stamp(now: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(now))

    fun fileName(now: Long): String = FILE_PREFIX + stamp(now) + FILE_EXTENSION

    /** 把文件名拼进远端目录；目录为空串时直接放在仓库根。 */
    fun remotePath(remoteDir: String, fileName: String): String {
        val dir = RemotePaths.normalizeRelative(remoteDir)
        return if (dir.isEmpty()) fileName else dir + "/" + fileName
    }

    /** 远端列出的文件里，哪一个是备份包。 */
    fun isBackupFile(path: String): Boolean = path.endsWith(FILE_EXTENSION)
}
