package com.purenote.local.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规范 §14 / §16：保留策略必须"保留到该保留的、清理到该清理的"，
 * 且手动/迁移前/已固定的备份永远不动。
 */
class BackupRetentionTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun auto(name: String, at: Long) = BackupFileInfo(name, at, 100, BackupKind.AUTO)

    @Test
    fun keepsTheNewestAutoSlots() {
        // 同一天内 60 份：最近 48 份必须保留
        val files = (1..60).map { auto("a$it", t0 + it * 1000L) }
        val doomed = BackupRetention.selectForDeletion(files, t0 + 100_000L).map { it.name }.toSet()

        val survivors = files.map { it.name }.filterNot { it in doomed }
        assertTrue("最近 48 份必须在", (52..60).all { "a$it" in survivors })
        assertEquals("同一天里旧的应被清掉，只留 48 份 + 当日档位", 48, survivors.size)
    }

    @Test
    fun olderBackupsAreStillKeptAsDailySlots() {
        // 每天 3 份，连续 10 天：每日档位保留每天最新的一份
        val files = (0 until 10).flatMap { d ->
            (0 until 3).map { auto("d${d}_$it", t0 + d * day + it * 3600_000L) }
        }
        val doomed = BackupRetention.selectForDeletion(files, t0 + 10 * day).map { it.name }.toSet()

        // 每天的最新一份必须留下
        (0 until 10).forEach { d ->
            assertFalse("第 $d 天的每日档位必须保留", "d${d}_2" in doomed)
        }
    }

    @Test
    fun manualAndPreMigrationAndPinnedAreNeverRotated() {
        // 档位是按"存在的桶"取的：备份太少时每个桶都成为代表、什么都不会被删。
        // 所以要构造足够多的近期备份 + 足够多的月份，让最老的那份确实不被任何档位选中。
        val recent = (0 until 60).map { d -> auto("recent$d", t0 - d * day) }
        val months = (1..20).map { m -> auto("mon$m", t0 - m * 30L * day) }
        val files = recent + months + listOf(
            BackupFileInfo("manual-1", t0 - 900 * day, 1, BackupKind.MANUAL),
            BackupFileInfo("premigration-1", t0 - 920 * day, 1, BackupKind.PRE_MIGRATION),
            BackupFileInfo("auto-pinned", t0 - 940 * day, 1, BackupKind.AUTO, pinned = true),
            // 极老的自动备份：不在最近 48 个里、不是近 30 天的日代表、也不是近 12 个月的月代表
            auto("auto-old", t0 - 1000 * day),
        )
        val doomed = BackupRetention.selectForDeletion(files, t0).map { it.name }.toSet()

        assertFalse("手动备份不得被轮换", "manual-1" in doomed)
        assertFalse("迁移前备份不得被轮换", "premigration-1" in doomed)
        assertFalse("已固定的备份不得被轮换", "auto-pinned" in doomed)
        assertTrue("无档位保护的老自动备份应被清理", "auto-old" in doomed)
    }

    @Test
    fun oneFileCanOccupySeveralSlots() {
        // 唯一的一份备份：同时是"最近 48 个"、"今天的每日档"、"本月的月度档"
        val files = listOf(auto("only", t0))
        assertTrue(BackupRetention.selectForDeletion(files, t0).isEmpty())
    }

    @Test
    fun monthlySlotsKeepOneRepresentativePerMonth() {
        // 连续 60 个月、每月一份：最近 48 份由 AUTO_SLOTS 保底，
        // 更老的月份若不在"最近 12 个月"里就应被轮换掉。
        val files = (0 until 60).map { m -> auto("m$m", t0 - (59 - m) * 30L * day) }
        val doomed = BackupRetention.selectForDeletion(files, t0).map { it.name }.toSet()
        val survivors = files.map { it.name }.filterNot { it in doomed }

        assertTrue("最近一个月必须在", "m59" in survivors)
        assertTrue("最老的一个月应被轮换掉", "m0" in doomed)
        assertEquals("保留数量应为最近 48 份", 48, survivors.size)
    }

    @Test
    fun monthKeyIsExactNotApproximated() {
        // 2026-01-01 与 2026-02-01 必须落在不同的月份桶
        val jan = 1767225600000L   // 2026-01-01T00:00:00Z
        val feb = 1769904000000L   // 2026-02-01T00:00:00Z
        assertTrue("跨月必须换桶", BackupRetention.monthKey(jan) != BackupRetention.monthKey(feb))
        // 同月内不同日应同桶
        assertTrue(
            "同月同日不同时同桶",
            BackupRetention.monthKey(jan) == BackupRetention.monthKey(jan + 5 * 3600_000L),
        )
    }

    @Test
    fun dayKeyIsTimezoneIndependent() {
        // 取一个日边界，验证同一天内任意时刻落在同一个日桶（不依赖设备时区）
        val dayStart = BackupRetention.dayKey(t0) * day
        assertEquals(BackupRetention.dayKey(dayStart), BackupRetention.dayKey(dayStart + 86_399_999L))
        assertTrue(
            "跨过日边界必须换桶",
            BackupRetention.dayKey(dayStart) != BackupRetention.dayKey(dayStart + day),
        )
    }

    @Test
    fun abnormalMassDeletionIsDetected() {
        // 从 100 条掉到 20 条（-80%）必须判定异常，暂停轮换
        assertTrue(BackupRetention.isAnomalousDrop(previousCount = 100, currentCount = 20))
        // 轻微减少不算
        assertFalse(BackupRetention.isAnomalousDrop(previousCount = 100, currentCount = 95))
        // 增加不算
        assertFalse(BackupRetention.isAnomalousDrop(previousCount = 100, currentCount = 120))
    }

    @Test
    fun smallLibrariesDoNotTriggerTheAnomalyFreeze() {
        // 小库波动大：5 条变 1 条不该冻结轮换，否则永远轮换不了
        assertFalse(BackupRetention.isAnomalousDrop(previousCount = 5, currentCount = 1))
        assertFalse(BackupRetention.isAnomalousDrop(previousCount = 0, currentCount = 0))
    }
}
