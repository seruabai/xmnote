package com.purenote.local.data

import com.purenote.local.core.RichDoc
import com.purenote.local.feature.mind.MindDoc

enum class NoteKind { TEXT, CHECKLIST, MIND }

/** 落库用的 kind 编码（历史库里有 0/1，脑图排在后面；解码见 [noteKindOf]） */
fun NoteKind.storedCode(): Int = when (this) {
    NoteKind.TEXT -> 0
    NoteKind.CHECKLIST -> 1
    NoteKind.MIND -> 2
}

fun noteKindOf(code: Int): NoteKind = when (code) {
    1 -> NoteKind.CHECKLIST
    2 -> NoteKind.MIND
    else -> NoteKind.TEXT
}

data class ChecklistItem(
    val text: String,
    val done: Boolean = false,
)

data class Note(
    val id: Long,
    /** 跨设备稳定 ID（云同步用），本地自增 id 只在本机有意义 */
    val uuid: String = "",
    val kind: NoteKind,
    val title: String,
    /**
     * 正文的**块文档**，编辑器唯一的读写形态。
     * 存储层是 v3 JSON（见 core/NoteBody），这里已经解码好，界面不必再碰格式。
     */
    val doc: RichDoc = RichDoc(),
    /**
     * 脑图正文（kind = MIND 时使用）。脑图是树而不是块序列，
     * 塞进 [doc] 只会得到一个装着 JSON 的文本块，所以单独一个字段。
     */
    val mind: MindDoc? = null,
    /**
     * 只读投影：块文档转回旧标记文本，供列表卡片/通知等"只看一眼"的地方使用。
     * **不要**拿它回写正文（会丢掉块模型能表达、标记表达不了的内容）。
     */
    val body: String,
    val items: List<ChecklistItem>,
    val images: List<String>,
    val colorIndex: Int,
    val folderId: Long?,
    val pinned: Boolean,
    val trashed: Boolean,
    val trashedAt: Long?,
    val remindAt: Long?,
    val repeat: RepeatRule = RepeatRule.NONE,
    val allDay: Boolean = false,
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
    val unclassifiedOnly: Boolean = false,
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
    /** 跨设备稳定 ID（云同步用） */
    val uuid: String = "",
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
    val trashed: Boolean = false,
    val trashedAt: Long? = null,
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

