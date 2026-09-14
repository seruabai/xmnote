package com.purenote.local.feature.backup

/** 备份种类。手动固定与迁移前备份**永不**自动轮换（规范 §14）。 */
enum class BackupKind { AUTO, MANUAL, PRE_MIGRATION }

data class BackupFileInfo(
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val kind: BackupKind = BackupKind.AUTO,
    /** 异常大规模删改发生时保留的固定恢复点 */
    val pinned: Boolean = false,
)

/**
 * 本地自动备份的保留策略（规范 §14）。
 *
 * 档位允许重叠：同一份文件可以同时是"最近 48 个自动备份之一"和"今天的每日档位"。
 * 只要它被**任意一个**档位选中就保留。
 *
 * 为什么清理必须晚于发布：规范要求执行顺序严格为
 * 新包成功 -> 校验成功 -> 记录发布成功 -> **才**决定旧包是否可清理。
 * 先删后建会在"新建失败"时把唯一的可用备份也一起清掉。
 */
object BackupRetention {

    /** 最近保留多少个自动备份 */
    const val AUTO_SLOTS = 48
    /** 每日档位数 */
    const val DAILY_SLOTS = 30
    /** 月度档位数 */
    const val MONTHLY_SLOTS = 12
    /** 记录数低于这个基数时不判定异常（小库波动大，误报会冻结轮换） */
    const val ANOMALY_MIN_BASELINE = 20
    /** 记录数相对上一份备份下降超过这个比例视为异常删改 */
    const val ANOMALY_DROP_RATIO = 0.5

    /**
     * 选出可以删除的文件。
     * 手动备份、迁移前备份、已固定（pinned）的一律不返回。
     */
    fun selectForDeletion(files: List<BackupFileInfo>, now: Long): List<BackupFileInfo> {
        val protected = files.filter {
            it.kind == BackupKind.MANUAL || it.kind == BackupKind.PRE_MIGRATION || it.pinned
        }.map { it.name }.toSet()

        val keep = mutableSetOf<String>()

        // 档位 1：最近 AUTO_SLOTS 个自动备份
        files.asSequence()
            .filter { it.kind == BackupKind.AUTO }
            .sortedByDescending { it.createdAt }
            .take(AUTO_SLOTS)
            .forEach { keep += it.name }

        // 档位 2：最近 DAILY_SLOTS 个自然日，每天保留最新的一份
        files.groupBy { dayKey(it.createdAt) }
            .entries.sortedByDescending { it.key }
            .take(DAILY_SLOTS)
            .forEach { (_, day) -> day.maxByOrNull { it.createdAt }?.let { keep += it.name } }

        // 档位 3：最近 MONTHLY_SLOTS 个自然月，每月保留最新的一份
        files.groupBy { monthKey(it.createdAt) }
            .entries.sortedByDescending { it.key }
            .take(MONTHLY_SLOTS)
            .forEach { (_, month) -> month.maxByOrNull { it.createdAt }?.let { keep += it.name } }

        return files.filter { it.name !in keep && it.name !in protected }
    }

    /**
     * 异常大规模删改（规范 §14）：与上一份备份相比记录数骤降时，
     * 应暂停普通轮换，并保留异常发生之前的固定恢复点，
     * 否则"误删 + 自动轮换"会把能救回来的那份也轮掉。
     */
    fun isAnomalousDrop(previousCount: Int, currentCount: Int): Boolean {
        if (previousCount < ANOMALY_MIN_BASELINE) return false
        if (currentCount >= previousCount) return false
        return currentCount < previousCount * (1.0 - ANOMALY_DROP_RATIO)
    }

    /** UTC 自然日序号。用 UTC 而不是本地时区：档位判定必须与设备时区设置无关，否则同一份备份在不同时区下归到不同档位。 */
    internal fun dayKey(ts: Long): Long = Math.floorDiv(ts, 86_400_000L)

    /** UTC 自然月序号（year * 12 + monthIndex）。用 civil-from-days 精确换算，不做 30 天近似。 */
    internal fun monthKey(ts: Long): Long {
        val days = dayKey(ts)
        var z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return year * 12 + (m - 1)
    }
}
