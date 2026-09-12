package com.purenote.local.backup

/**
 * 备份包内条目名的安全清洗（纯逻辑，可单测）。
 *
 * 导入 zip 时必须防 **Zip Slip**：恶意/损坏的备份包可能带
 * `../../databases/purenote.db` 这类条目，若直接拼接路径就会写到目录外、
 * 覆盖其他文件。这里的策略是"只接受纯文件名，其余一律拒绝"。
 */
object BackupPaths {

    private const val MAX_NAME_LENGTH = 128

    /** 合法则返回原名（去首尾空白），否则返回 null。 */
    fun sanitizeEntryName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name.contains("..")) return null
        if (name.length > MAX_NAME_LENGTH) return null
        // 以点开头（含 . / ..）或含 NUL 一律拒绝
        if (name.startsWith('.')) return null
        if (name.any { it == '\u0000' }) return null
        return name
    }
}
