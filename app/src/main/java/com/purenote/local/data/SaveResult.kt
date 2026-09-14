package com.purenote.local.data

/**
 * 保存回执（规范 §7）。
 *
 * 原实现 `saveExisting` 返回 Boolean 而调用方直接丢弃，界面无从知道保存是否落库：
 * 一次写入失败会被静默报告成成功，用户看到的"已保存"是假的。
 *
 * 阶段 B 先落地 成功 / 不存在 / 失败 三态；
 * Conflict（版本冲突）与 StoreChanged（存储代次变化）在阶段 C/F 补齐。
 */
sealed interface SaveResult {

    /** 提交成功。[revision] 是本次提交产生的修订号。 */
    data class Saved(val noteId: Long, val revision: Long) : SaveResult

    /**
     * 版本冲突：数据库中的实际修订号与预期不一致（规范 §7）。
     * 界面必须保留本地输入，绝不能用"重新读一个 revision 后直接覆盖全文"来解决。
     */
    data class Conflict(val actualRevision: Long) : SaveResult

    /**
     * 存储代次已变化（规范 §12 第 9 条）：这条命令属于**切换之前**的存储，
     * 它的内容是针对旧库的，绝不能写进恢复后的新库。
     */
    data object StoreChanged : SaveResult

    /** 目标记录不存在或已被永久删除 */
    data object NotFound : SaveResult

    /** 写入失败，[reason] 是业务可判别的分类，原始异常留在边界做诊断 */
    data class Failed(val reason: StorageFailure) : SaveResult
}

/** 存储失败分类（规范 §15：错误分类至少有冲突/空间不足/权限失效/锁定/损坏/不支持/结果未知） */
enum class StorageFailure {
    /** 版本冲突：另一处已改过这条记录（规范 §7） */
    CONFLICT,
    /** 数据库处于损坏保全状态，已拒绝写入 */
    CORRUPTED,
    /** 数据库被其他写入占用 */
    LOCKED,
    /** 磁盘空间不足 */
    NO_SPACE,
    /** 权限失效 */
    PERMISSION,
    /** 结果未知：提交可能已发生，必须按幂等键核实而不是直接重试 */
    UNKNOWN_RESULT,
    /** 其他未归类错误 */
    UNKNOWN;

    companion object {
        fun of(t: Throwable): StorageFailure = when (t) {
            is android.database.sqlite.SQLiteFullException -> NO_SPACE
            is android.database.sqlite.SQLiteDiskIOException -> NO_SPACE
            is android.database.sqlite.SQLiteDatabaseLockedException -> LOCKED
            is android.database.sqlite.SQLiteDatabaseCorruptException -> CORRUPTED
            is SecurityException -> PERMISSION
            is IllegalStateException ->
                if (t.message?.contains("损坏保全状态") == true) CORRUPTED else UNKNOWN
            else -> UNKNOWN
        }
    }
}
