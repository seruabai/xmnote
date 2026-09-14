package com.purenote.local.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 规范 §5.2 / §13.1 / §16：v9 加法迁移必须保真，且必须覆盖真实历史版本。
 *
 * 这些用例手工构造旧版本库，再交由 NotesDb 触发 onUpgrade——
 * 只用当前代码建库再回读是**测不出迁移问题**的。
 */
@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        listOf(DB_V8, DB_V1).forEach { ctx.deleteDatabase(it) }
    }

    private fun buildOldDb(name: String, statements: List<String>) {
        ctx.deleteDatabase(name)
        val f = ctx.getDatabasePath(name)
        f.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        statements.forEach { db.execSQL(it) }
        db.close()
    }

    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
            .use { it.moveToFirst() }

    private fun userVersion(db: SQLiteDatabase): Int =
        db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }

    private val v8Notes = """
        CREATE TABLE notes(
          id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL DEFAULT '', kind INTEGER NOT NULL DEFAULT 0,
          title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', images TEXT NOT NULL DEFAULT '',
          color INTEGER NOT NULL DEFAULT 0, folder_id INTEGER NULL, pinned INTEGER NOT NULL DEFAULT 0,
          trashed INTEGER NOT NULL DEFAULT 0, trashed_at INTEGER NULL, remind_at INTEGER NULL,
          repeat_type INTEGER NOT NULL DEFAULT 0, all_day INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
    """.trimIndent()

    private val v8Todos = """
        CREATE TABLE todos(
          id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL DEFAULT '', parent_id INTEGER NULL,
          title TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, done_at INTEGER NULL, due_at INTEGER NULL,
          remind_at INTEGER NULL, all_day INTEGER NOT NULL DEFAULT 0, repeat_type INTEGER NOT NULL DEFAULT 0,
          sort_index INTEGER NOT NULL DEFAULT 0, trashed INTEGER NOT NULL DEFAULT 0, trashed_at INTEGER NULL,
          created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
    """.trimIndent()

    private val v8Folders = """
        CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)
    """.trimIndent()

    @Test
    fun migratesV8ToV9WithoutTouchingExistingData() {
        val body = "# 标题\n- [ ] 任务\n![](img_1.jpg)"
        buildOldDb(
            DB_V8,
            listOf(
                v8Folders, v8Notes, v8Todos,
                "INSERT INTO notes(uuid,kind,title,body,created_at,updated_at) VALUES ('u1',0,'标题','" + body.replace("'", "''") + "',1,1)",
                "INSERT INTO todos(uuid,title,created_at,updated_at) VALUES ('t1','待办',1,1)",
                "PRAGMA user_version = 8",
            ),
        )

        val db = NotesDb(ctx, DB_V8).writableDatabase

        assertEquals("必须升到 v9", 9, userVersion(db))
        assertTrue("notes 应有 revision", columnsOf(db, "notes").contains("revision"))
        assertTrue("notes 应有 body_format_version", columnsOf(db, "notes").contains("body_format_version"))
        assertTrue("todos 应有 revision", columnsOf(db, "todos").contains("revision"))
        assertTrue("folders 应有 revision", columnsOf(db, "folders").contains("revision"))

        for (t in listOf(
            "library_meta", "note_versions", "todo_versions", "operations",
            "attachments", "note_attachment_refs", "version_attachment_refs",
            "reminder_jobs", "import_mappings",
        )) {
            assertTrue("缺少表 $t", tableExists(db, t))
        }

        // 既有数据保真
        db.rawQuery("SELECT body, revision, body_format_version FROM notes", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("正文必须逐字不变", body, c.getString(0))
            assertEquals("存量修订号从 1 起", 1, c.getInt(1))
            // v8 分支已把正文转成 Markdown，因此存量行就是格式 2
            assertEquals("正文格式版本应为 Markdown", 2, c.getInt(2))
        }

        // 库身份只应有一行
        db.rawQuery("SELECT COUNT(*), MAX(library_id) FROM library_meta", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertNotNull(c.getString(1))
            assertTrue("library_id 不应为空", c.getString(1).isNotBlank())
        }
        db.close()
    }

    @Test
    fun migratesAncientV1DatabaseAllTheWayToV9() {
        // 回归：v2 分支曾用"当前定义"建 todos，导致 <3 分支的 ADD COLUMN 重复加列并抛错，
        // v1 库完全无法升级。这里锁住修复。
        buildOldDb(
            DB_V1,
            listOf(
                // 真实的 v1 notes：v2 只给它加 color/images，v6 加 repeat_type/all_day，
                // v5 加 uuid。其余列（kind/pinned/trashed/trashed_at/remind_at）在 v1 就存在——
                // 否则 v7 的 idx_notes_trashed_updated 无法建立。
                """
                CREATE TABLE notes(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, kind INTEGER NOT NULL DEFAULT 0,
                  title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', folder_id INTEGER NULL,
                  pinned INTEGER NOT NULL DEFAULT 0, trashed INTEGER NOT NULL DEFAULT 0,
                  trashed_at INTEGER NULL, remind_at INTEGER NULL,
                  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                """.trimIndent(),
                "CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)",
                "INSERT INTO notes(title,body,created_at,updated_at) VALUES ('\u65e7\u7b14\u8bb0','\uE201\u2610 \u65e7\u4efb\u52a1',1,1)",
                "PRAGMA user_version = 1",
            ),
        )

        val db = NotesDb(ctx, DB_V1).writableDatabase

        assertEquals("v1 必须能一路升到 v9", 9, userVersion(db))
        val notes = columnsOf(db, "notes")
        for (col in listOf("color", "images", "uuid", "repeat_type", "all_day", "revision", "body_format_version")) {
            assertTrue("v1 升级后 notes 缺少列 $col", notes.contains(col))
        }
        for (t in listOf("todos", "note_versions", "operations", "library_meta")) {
            assertTrue("v1 升级后缺少表 $t", tableExists(db, t))
        }
        // 旧格式正文应已被 v8 转换为 Markdown
        db.rawQuery("SELECT body FROM notes", null).use { c ->
            assertTrue(c.moveToFirst())
            val migrated = c.getString(0)
            assertTrue("PUA 标题应转成 Markdown 标题，实际=$migrated", migrated.contains("# "))
            assertTrue("☐ 应转成任务语法，实际=$migrated", migrated.contains("- [ ] "))
            assertTrue("不得残留 PUA 字符", !migrated.contains('\uE201'))
        }
        db.close()
    }

    @Test
    fun freshInstallHasTheSameShapeAsMigratedDatabase() {
        // onCreate 与 onUpgrade 两条路径必须收敛到同一结构，否则会出现
        // "新装正常、升级报错"（或反之）的漂移
        ctx.deleteDatabase(DB_FRESH)
        val db = NotesDb(ctx, DB_FRESH).writableDatabase
        assertEquals(9, userVersion(db))
        for (t in listOf(
            "library_meta", "note_versions", "todo_versions", "operations",
            "attachments", "note_attachment_refs", "version_attachment_refs",
            "reminder_jobs", "import_mappings", "notes", "todos", "folders",
        )) {
            assertTrue("全新安装缺少表 $t", tableExists(db, t))
        }
        assertTrue(columnsOf(db, "notes").contains("body_format_version"))
        db.rawQuery("SELECT COUNT(*) FROM library_meta", null).use { c ->
            c.moveToFirst(); assertEquals("全新安装应写入库身份", 1, c.getInt(0))
        }
        db.close()
        ctx.deleteDatabase(DB_FRESH)
    }

    private companion object {
        const val DB_V8 = "migrate-v8.db"
        const val DB_V1 = "migrate-v1.db"
        const val DB_FRESH = "migrate-fresh.db"
    }
}
