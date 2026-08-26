package com.purenote.local.data

enum class NoteKind { TEXT, CHECKLIST }

data class ChecklistItem(
    val text: String,
    val done: Boolean = false,
)

data class Note(
    val id: Long,
    val kind: NoteKind,
    val title: String,
    val body: String,
    val items: List<ChecklistItem>,
    val images: List<String>,
    val colorIndex: Int,
    val folderId: Long?,
    val pinned: Boolean,
    val trashed: Boolean,
    val trashedAt: Long?,
    val remindAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 笔记卡片底色调色板索引，0 表示默认 */
data class Folder(
    val id: Long,
    val name: String,
    val createdAt: Long,
)

data class NoteFilter(
    val folderId: Long? = null,
    val query: String = "",
    val trashed: Boolean = false,
)

/** 列表排序方式 */
enum class SortOrder { BY_UPDATED, BY_CREATED }

/** 重复规则（对应小米待办 remindRepeatType） */
enum class RepeatRule { NONE, DAILY, WEEKLY, WEEKDAYS, WORKDAYS, MONTHLY, YEARLY;

    companion object {
        fun fromOrdinal(v: Int): RepeatRule = entries.firstOrNull { it.ordinal == v } ?: NONE
    }
}

data class Todo(
    val id: Long,
    val parentId: Long?,
    val title: String,
    val done: Boolean,
    val doneAt: Long?,
    /** 到期/提醒时间（小米待办的 remindTime 与 expireTime 合一） */
    val dueAt: Long?,
    /** 整天事件：只显示日期，到期取当天末尾 */
    val allDay: Boolean = false,
    val repeat: RepeatRule = RepeatRule.NONE,
    /** 兼容旧列：始终与 dueAt 同步写入 */
    val remindAt: Long? = null,
    val sortIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isSubtask: Boolean get() = parentId != null

    /** 是否已过期（未完成且到期时刻已过） */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        !done && dueAt != null && dueAt <= now
}

data class NotePrefill(
    val title: String = "",
    val body: String = "",
    val imageUris: List<String> = emptyList(),
)

/** 排序：置顶优先，其余按所选时间倒序 */
fun List<Note>.sortedForHome(order: SortOrder): List<Note> {
    val byTime = when (order) {
        SortOrder.BY_UPDATED -> compareByDescending<Note> { it.updatedAt }
        SortOrder.BY_CREATED -> compareByDescending<Note> { it.createdAt }
    }
    return sortedWith(compareByDescending<Note> { it.pinned }.then(byTime))
}

