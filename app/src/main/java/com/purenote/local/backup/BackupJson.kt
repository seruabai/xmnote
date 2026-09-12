package com.purenote.local.backup

import kotlinx.serialization.json.Json

/**
 * 备份 JSON 的读写。
 *
 * 手写 JSON 转义是笔记内容（中文/emoji/引号/换行）最容易损坏的地方，
 * 所以这里用 kotlinx-serialization 而不是拼接字符串。
 */
object BackupJson {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true   // 未来新增字段，旧版本仍能读
        encodeDefaults = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** 解析失败抛出 [BackupFormatException]，由调用方转成用户可读提示。 */
    fun decode(text: String): BackupFile {
        val parsed = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw BackupFormatException("备份文件不是有效的纯记备份（JSON 解析失败）", e)
        }
        if (parsed.schema > BackupFile.CURRENT_SCHEMA) {
            throw BackupFormatException(
                "备份格式版本 ${parsed.schema} 高于当前应用支持的 ${BackupFile.CURRENT_SCHEMA}，请升级应用后再导入",
            )
        }
        return parsed
    }
}

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
