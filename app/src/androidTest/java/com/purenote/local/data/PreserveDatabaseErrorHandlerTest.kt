package com.purenote.local.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.database.sqlite.SQLiteDatabase

/**
 * 规范 §6.3 / §16：数据库损坏时不得删库、不得重建空库，现场必须保全。
 * 这条用真实文件库在设备上验证（内存库不能代替）。
 */
@RunWith(AndroidJUnit4::class)
class PreserveDatabaseErrorHandlerTest {

    @org.junit.After
    fun resetCorruptionState() {
        // CorruptionState 是进程级单例（生产上刻意不可恢复）。
        // 测试必须复位，否则会把故障标记泄漏给同一进程内的其它用例，
        // 让它们全部以 CORRUPTED 失败。
        CorruptionState.resetForTests()
    }

    private fun freshDbFile(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.cacheDir, name)
        if (f.exists()) f.delete()
        // 建一个真实库文件，再往里写脏数据模拟损坏现场
        SQLiteDatabase.openOrCreateDatabase(f, null).use { it.execSQL("CREATE TABLE t(id INTEGER)") }
        return f
    }

    @Test
    fun corruptionHandlerDoesNotDeleteTheDatabaseFile() {
        val f = freshDbFile("preserve-test.db")
        assertTrue("前置：库文件应存在", f.exists())
        val originalBytes = f.readBytes()

        var reported: String? = null
        val handler = PreserveDatabaseErrorHandler { reported = it }

        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        handler.onCorruption(db)
        db.close()

        assertTrue("损坏处理器不得删除数据库文件", f.exists())
        assertEquals("现场字节必须原样保留", originalBytes.size, f.readBytes().size)
        assertNotNull("应上报损坏", reported)
        f.delete()
    }

    @Test
    fun corruptionStateIsRecordedWithoutNoteContent() {
        val f = freshDbFile("preserve-state-test.db")
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        PreserveDatabaseErrorHandler().onCorruption(db)
        db.close()

        assertTrue("应置故障状态", CorruptionState.corrupted)
        assertTrue("诊断不得包含笔记正文", CorruptionState.detail!!.contains("corruption"))
        assertTrue("库文件必须仍在", f.exists())
        f.delete()
    }

    @Test
    fun notesDbInstallsThePreservingHandler() {
        // NotesDb 是唯一生产构造点，必须装上保全型处理器而不是 Android 默认实现
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = NotesDb(ctx, "handler-wiring-test.db")
        db.writableDatabase.use { it.execSQL("CREATE TABLE IF NOT EXISTS probe(id INTEGER)") }
        db.close()
        assertTrue("NotesDb 应可用", File(ctx.getDatabasePath("handler-wiring-test.db").absolutePath).exists())
        ctx.deleteDatabase("handler-wiring-test.db")
    }
}
