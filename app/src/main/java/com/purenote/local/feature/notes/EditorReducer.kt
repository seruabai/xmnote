package com.purenote.local.feature.notes

import com.purenote.local.data.ChecklistItem
import com.purenote.local.core.RichDoc
import com.purenote.local.data.NoteKind
import com.purenote.local.data.RepeatRule

/** 保存状态（规范 §8：界面必须能区分"还没存"与"存失败"） */
enum class SaveStatus { IDLE, PENDING, SAVING, FAILED, CONFLICT }

/**
 * 编辑器状态（规范 §8）。纯数据，不含任何 Android 依赖，便于单元测试。
 *
 * - [editGeneration]：用户每改一次 +1，永不回退。
 * - [acknowledgedGeneration]：**已确认落库**的最大代次。
 * - [committedRevision]：已确认提交产生的修订号，用作下一次 CAS 的 expectedRevision。
 */
data class EditorState(
    val sessionId: String,
    val storeEpoch: String,
    val loaded: Boolean = false,
    val readOnly: Boolean = false,
    val content: String = "",
    val editGeneration: Long = 0,
    val acknowledgedGeneration: Long = 0,
    val committedRevision: Long = 0,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
    val failure: String? = null,
) {
    /** 还有未确认的本地改动 */
    val hasUnacknowledgedEdits: Boolean get() = editGeneration > acknowledgedGeneration
}

/** 编辑器事件（规范 §8 的状态迁移输入） */
sealed interface EditorEvent {
    /** 加载完成：确立基线修订号 */
    data class Loaded(val content: String, val revision: Long) : EditorEvent
    /** 加载失败或进入只读：此后禁止写入 */
    data object LoadFailed : EditorEvent
    /** 用户输入 */
    data class Edited(val content: String) : EditorEvent
    /** 写入开始 */
    data class SaveStarted(val generation: Long) : EditorEvent
    /** 写入成功。注意 [generation] 是**本次提交对应的**代次，不是当前代次 */
    data class SaveSucceeded(val generation: Long, val revision: Long) : EditorEvent
    data class SaveFailed(val reason: String) : EditorEvent
    data class SaveConflicted(val actualRevision: Long) : EditorEvent
}

/**
 * 编辑器状态机（规范 §8）。
 *
 * 最容易被写错的一条：**保存回执只能标记它自己那一代次**。
 * 第 10 次输入提交成功时用户可能已经打到第 11 次，
 * 此时界面必须继续显示"待保存"——否则就是"看起来保存成功，实际最新内容没保存"。
 */
object EditorReducer {

    fun initial(sessionId: String, storeEpoch: String): EditorState =
        EditorState(sessionId = sessionId, storeEpoch = storeEpoch)

    fun reduce(state: EditorState, event: EditorEvent): EditorState = when (event) {
        is EditorEvent.Loaded -> state.copy(
            loaded = true,
            readOnly = false,
            content = event.content,
            committedRevision = event.revision,
            editGeneration = 0,
            acknowledgedGeneration = 0,
            saveStatus = SaveStatus.IDLE,
            failure = null,
        )

        EditorEvent.LoadFailed -> state.copy(
            loaded = false,
            readOnly = true,
            saveStatus = SaveStatus.FAILED,
            failure = "加载失败，已切换为只读",
        )

        is EditorEvent.Edited -> state.copy(
            content = event.content,
            editGeneration = state.editGeneration + 1,
            // 正在写入时不要打断"写入中"的显示；写入结束后由回执重新判定
            saveStatus = if (state.saveStatus == SaveStatus.SAVING) state.saveStatus else SaveStatus.PENDING,
            failure = null,
        )

        is EditorEvent.SaveStarted -> state.copy(
            saveStatus = SaveStatus.SAVING,
            failure = null,
        )

        is EditorEvent.SaveSucceeded -> {
            // 只推进"本次提交覆盖到的那一代"
            val acknowledged = maxOf(state.acknowledgedGeneration, event.generation)
            state.copy(
                acknowledgedGeneration = acknowledged,
                committedRevision = event.revision,
                saveStatus = if (state.editGeneration > acknowledged) SaveStatus.PENDING else SaveStatus.IDLE,
                failure = null,
            )
        }

        is EditorEvent.SaveFailed -> state.copy(
            saveStatus = SaveStatus.FAILED,
            failure = event.reason,
        )

        is EditorEvent.SaveConflicted -> state.copy(
            // 冲突：不覆盖对方的修订，也不把本地改动标成已保存
            committedRevision = event.actualRevision,
            saveStatus = SaveStatus.CONFLICT,
            failure = "这条笔记已在别处被修改",
        )
    }

    /** 是否允许发起写入（规范 §8：加载失败/只读/未加载时不写入） */
    fun canWrite(state: EditorState): Boolean = state.loaded && !state.readOnly
}

/** 保存命令（规范 §7）：携带身份、代次、预期修订与完整内容快照 */
data class SaveCommand(
    val noteId: Long,
    val operationId: String,
    val sessionId: String,
    val storeEpoch: String,
    val editGeneration: Long,
    val expectedRevision: Long,
    val kind: NoteKind,
    val title: String,
    /** 正文的块文档快照（编辑器唯一的读写形态） */
    val doc: RichDoc,
    val items: List<ChecklistItem>,
    val images: List<String>,
    val colorIndex: Int,
    val folderId: Long?,
    val pinned: Boolean,
    val remindAt: Long?,
    val repeat: RepeatRule = RepeatRule.NONE,
    val allDay: Boolean = false,
)
