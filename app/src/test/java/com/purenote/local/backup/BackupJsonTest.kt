package com.purenote.local.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列化是最容易悄悄损坏笔记内容的一环（中文、emoji、引号、换行、控制字符）。
 * 这里专门盯这些字符的往返保真。
 */
class BackupJsonTest {

    @Test
    fun roundTrip_preservesHardCharacters() {
        val tricky = "引号\"与'反斜杠\\换行\n制表\temoji✅中文【标点】，。！？"
        val backup = BackupFile(
            appVersion = "1.2.20",
            notes = listOf(
                NoteDto(uuid = "n1", kind = 0, title = "标题\"引号", body = tricky, updatedAt = 5L),
            ),
        )
        val decoded = BackupJson.decode(BackupJson.encode(backup))
        assertEquals(tricky, decoded.notes[0].body)
        assertEquals("标题\"引号", decoded.notes[0].title)
    }

    @Test
    fun roundTrip_preservesAllFields() {
        val backup = BackupFile(
            schema = BackupFile.CURRENT_SCHEMA,
            appVersion = "1.2.20",
            exportedAt = 123L,
            folders = listOf(FolderDto("folder-工作", "工作", 1L)),
            notes = listOf(
                NoteDto(
                    uuid = "n1", kind = 1, title = "清单", body = "1\n0买牛奶",
                    items = listOf(ChecklistItemDto("买牛奶", true), ChecklistItemDto("写周报", false)),
                    images = listOf("img_1.jpg", "aud_2.m4a"), colorIndex = 3,
                    folderUuid = "folder-工作", pinned = true, trashed = true, trashedAt = 9L,
                    remindAt = 10L, repeat = 2, allDay = true, createdAt = 1L, updatedAt = 2L,
                ),
            ),
            todos = listOf(
                TodoDto("t1", null, "父", dueAt = 5L, repeat = 1, sortIndex = 2, createdAt = 1L, updatedAt = 2L),
                TodoDto("t2", "t1", "子", done = true, doneAt = 3L, createdAt = 1L, updatedAt = 2L),
            ),
        )
        val decoded = BackupJson.decode(BackupJson.encode(backup))
        assertEquals(backup, decoded)
    }

    @Test
    fun unknownFields_areIgnored_forForwardCompatibility() {
        val text = """{"schema":1,"notes":[],"todos":[],"folders":[],"futureField":123}"""
        val decoded = BackupJson.decode(text)
        assertTrue(decoded.notes.isEmpty())
    }

    @Test
    fun malformedJson_throwsReadableError() {
        val e = assertThrows(BackupFormatException::class.java) { BackupJson.decode("not json at all {{{") }
        // 断言"给用户看的是纯记备份相关说明"，而不是底层解析器术语
        assertTrue(e.message!!.contains("纯记备份"))
    }

    @Test
    fun newerSchema_isRejectedWithClearMessage() {
        val text = """{"schema":999,"notes":[],"todos":[],"folders":[]}"""
        val e = assertThrows(BackupFormatException::class.java) { BackupJson.decode(text) }
        assertTrue(e.message!!.contains("升级应用"))
    }

    @Test
    fun olderSchema_isAccepted() {
        val text = """{"schema":1,"appVersion":"1.0","notes":[],"todos":[],"folders":[]}"""
        assertEquals(1, BackupJson.decode(text).schema)
    }
}
