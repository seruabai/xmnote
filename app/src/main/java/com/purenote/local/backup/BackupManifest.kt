package com.purenote.local.backup

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 备份包清单（规范 §11.1）。
 *
 * 为什么必须有它：`backup.json` 只能证明"包里有这份 JSON"，
 * 证明不了"附件字节完整"。截断的、被同步工具截半的、被手工改过的包
 * 在没有清单时会被当成正常备份导入，然后**替换掉用户现有的有效备份** ——
 * 这正是规范要求"坏备份 / 截断包 / 改字节 / 缺附件 / 重复条目 -> 拒绝完整恢复"的原因。
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int = CURRENT_FORMAT,
    val appVersion: String = "",
    /** 导出时的数据库 schema 版本，便于排查"备份来自哪个结构" */
    val sourceSchema: Int = 0,
    val libraryId: String = "",
    val backupId: String = "",
    val createdAt: Long = 0L,
    val entries: List<ManifestEntry> = emptyList(),
    val counts: ManifestCounts = ManifestCounts(),
    /** 导出时是否所有被引用的附件都成功写入；false 表示这是一份不完整的抢救包 */
    val complete: Boolean = true,
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

@Serializable
data class ManifestEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class ManifestCounts(
    val notes: Int = 0,
    val todos: Int = 0,
    val folders: Int = 0,
    val attachments: Int = 0,
)

/** 读取到的条目摘要，用于与清单比对 */
data class EntryDigest(val path: String, val sizeBytes: Long, val sha256: String)

/**
 * 清单校验（规范 §11.2 第 7 步：解包后必须逐项校验才能标为有效备份）。
 *
 * 纯函数、无 Android 依赖，因此可以直接在 JVM 上把"坏包"构造出来测。
 */
object BackupVerifier {

    sealed interface Outcome {
        data object Ok : Outcome
        data class Failed(val reasons: List<String>) : Outcome
    }

    fun verify(manifest: BackupManifest, actual: List<EntryDigest>): Outcome {
        val reasons = mutableListOf<String>()

        val actualByPath = actual.groupBy { it.path }
        actualByPath.filterValues { it.size > 1 }.keys.forEach {
            reasons += "包里存在重复条目：$it"
        }

        val declared = manifest.entries.associateBy { it.path }

        // 清单里声明了、实际却没有（或对不上）
        manifest.entries.forEach { expected ->
            val found = actualByPath[expected.path]?.firstOrNull()
            when {
                found == null -> reasons += "清单声明的条目缺失：${expected.path}"
                found.sizeBytes != expected.sizeBytes ->
                    reasons += "${expected.path} 大小不符（清单 ${expected.sizeBytes}，实际 ${found.sizeBytes}）"
                !found.sha256.equals(expected.sha256, ignoreCase = true) ->
                    reasons += "${expected.path} 校验值不符，包已损坏或被改动"
            }
        }

        // 实际有、清单没声明（说明清单与内容不是同一次生成的）
        actual.forEach { entry ->
            if (entry.path !in declared) reasons += "包内存在清单未声明的条目：${entry.path}"
        }

        return if (reasons.isEmpty()) Outcome.Ok else Outcome.Failed(reasons)
    }

    fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
