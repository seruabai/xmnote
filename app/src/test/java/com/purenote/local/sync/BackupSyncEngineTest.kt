package com.purenote.local.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * 云同步引擎的顺序与校验测试（JVM，无 Android、无真实网络）。
 *
 * 这些用例针对的是"只有顺序错才会出的事"：
 * - 上传前不校验本地包：把一份残缺包装成"备份完成"；
 * - 上传后不读回：服务器只收半包也算成功；
 * - 读回的是别人的包：只有比对 backupId 才能发现；
 * - 没网还去连：用户等超时，而不是立刻看到原因。
 */
class BackupSyncEngineTest {

    private val staging: File = Files.createTempDirectory("cloud-sync-test").toFile()

    @Test
    fun `第一次同步 导出-上传-读回校验-记录成功`() {
        val owner = FakeOwner(noteCount = 3, backupId = "bk-1")
        val remote = FakeTransport()
        val settings = FakeSettings(serverUrl = "https://dav.example.com/dav", dir = "purenote")
        val engine = engine(owner, settings, remote)
        val phases = mutableListOf<BackupSyncEngine.Phase>()

        val result = kotlinx.coroutines.runBlocking {
            engine.syncNow { state ->
                if (state is BackupSyncEngine.State.Running) phases += state.phase
            }
        }

        assertTrue("同步应成功：" + result.exceptionOrNull()?.message, result.isSuccess)
        val outcome = result.getOrThrow()
        val fixedNow = 1_800_000_000_000L
        assertEquals("purenote/" + SyncContract.fileName(fixedNow), outcome.remotePath)
        assertEquals("bk-1", outcome.backupId)
        assertTrue(outcome.verified)

        // 远端确实拿到了一份完整包：按字节自己校验一遍，不看 mock 的调用次数
        val uploaded = remote.files.getValue(outcome.remotePath)
        assertEquals(owner.lastExportedBytes, uploaded.size)
        TestBackupPackage.inspect(uploaded.inputStream())

        assertTrue("上传后必须读回远端副本", remote.openReadCount >= 1)
        assertTrue("必须对远端文件做 stat", remote.statCount >= 1)
        assertEquals(1, remote.uploadCount)

        // 只有校验通过之后才允许记录成功
        assertEquals(1_800_000_000_000L, settings.meta.lastSuccessAt)
        assertEquals("bk-1", settings.meta.lastBackupId)
        assertEquals(3, settings.meta.lastNoteCount)

        assertEquals(
            listOf(
                BackupSyncEngine.Phase.EXPORTING,
                BackupSyncEngine.Phase.EXPORTING,
                BackupSyncEngine.Phase.UPLOADING,
                BackupSyncEngine.Phase.VERIFYING,
            ),
            phases,
        )
    }

    @Test
    fun `远端大小不一致时按失败处理且不记录成功`() {
        val owner = FakeOwner(noteCount = 1, backupId = "bk-2")
        val remote = FakeTransport(uploadReportedSizeDelta = -5)
        val settings = FakeSettings()
        val engine = engine(owner, settings, remote)

        val result = kotlinx.coroutines.runBlocking { engine.syncNow() }

        assertTrue(result.isFailure)
        assertTrue(
            "错误信息应说明大小不符：" + result.exceptionOrNull()?.message,
            result.exceptionOrNull()?.message!!.contains("不一致"),
        )
        assertEquals(0L, settings.meta.lastSuccessAt)
        // 服务器回执大小不符属于"服务器在改写请求"，重传不可能变好，所以刻意**不重试**：
        // 少花一次上行流量，也少占一次限流配额。
        assertEquals(1, remote.uploadCount)
        assertEquals(0, remote.openReadCount)
    }

    @Test
    fun `读回的是另一份包时按失败处理`() {
        val owner = FakeOwner(noteCount = 1, backupId = "bk-mine")
        val stranger = TestBackupPackage.bytes(
            TestBackupPackage.Spec(backupId = "bk-other", noteCount = 1, noteBodies = listOf("别人的笔记")),
        )
        val remote = FakeTransport(readBackOverride = stranger, statReturnsNull = true)
        val settings = FakeSettings()
        val engine = engine(owner, settings, remote)

        val result = kotlinx.coroutines.runBlocking { engine.syncNow() }

        assertTrue(result.isFailure)
        assertTrue(
            "应识别出读到的不是本次那份：" + result.exceptionOrNull()?.message,
            result.exceptionOrNull()?.message!!.contains("与本次上传的不一致"),
        )
        assertEquals(0L, settings.meta.lastSuccessAt)
    }

    @Test
    fun `没有网络时不上传且给出可读原因`() {
        val remote = FakeTransport()
        val settings = FakeSettings()
        val engine = BackupSyncEngine.forTest(
            owner = FakeOwner(),
            settings = settings,
            credentials = InMemoryCredentialStore(CredentialStore.Credentials("u", "p")),
            network = FixedNetworkStatus(online = false),
            stagingDir = staging,
            transportFactory = { _, _ -> remote },
        )

        val result = kotlinx.coroutines.runBlocking { engine.syncNow() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.contains("没有网络"))
        assertTrue(remote.files.isEmpty())
        assertEquals(0L, settings.meta.lastSuccessAt)
    }

    @Test
    fun `没配置或没开启时给出明确提示`() {
        val settings = FakeSettings(configured = false)
        val engine = engine(FakeOwner(), settings, FakeTransport())

        val result = kotlinx.coroutines.runBlocking { engine.syncNow() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.contains("还没有配置"))

        settings.remote = SyncSettingsStore.RemoteConfig("https://dav.example.com/dav", "purenote", enabled = false)
        val disabled = kotlinx.coroutines.runBlocking { engine.syncNow() }
        assertTrue(disabled.exceptionOrNull()?.message!!.contains("未开启"))
    }

    @Test
    fun `本地包不完整时默认拒绝上传 除非显式允许`() {
        val owner = FakeOwner(complete = false)
        val remote = FakeTransport()
        val settings = FakeSettings()
        val engine = engine(owner, settings, remote)

        val refused = kotlinx.coroutines.runBlocking { engine.syncNow() }
        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull()?.message!!.contains("不完整"))
        assertTrue("拒绝时不应上传任何东西", remote.files.isEmpty())

        val allowed = kotlinx.coroutines.runBlocking { engine.syncNow(allowIncomplete = true) }
        assertTrue("显式允许后应能上传：" + allowed.exceptionOrNull()?.message, allowed.isSuccess)
        assertEquals(1, allowed.getOrThrow().warnings.size)
    }

    @Test
    fun `导出的临时文件用完即删`() {
        val owner = FakeOwner()
        val engine = engine(owner, FakeSettings(), FakeTransport())
        kotlinx.coroutines.runBlocking { engine.syncNow() }
        assertEquals(0, staging.listFiles()?.size)
    }

    @Test
    fun `测试连接列出远端目录的文件数`() {
        val remote = FakeTransport()
        remote.files["purenote/a.purenote.zip"] = TestBackupPackage.bytes(TestBackupPackage.Spec("bk-a"))
        remote.files["purenote/b.purenote.zip"] = TestBackupPackage.bytes(TestBackupPackage.Spec("bk-b"))
        val engine = engine(FakeOwner(), FakeSettings(), remote)

        val result = kotlinx.coroutines.runBlocking { engine.testConnection() }
        assertEquals(2, result.getOrThrow())
    }

    @Test
    fun `远端目录不存在时连接测试仍成功 首次同步会自动建`() {
        val remote = FakeTransport(missingDirectory = true)
        val engine = engine(FakeOwner(), FakeSettings(), remote)

        val result = kotlinx.coroutines.runBlocking { engine.testConnection() }
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
    }

    @Test
    fun `凭据丢失时不尝试连接 而是提示重新填写`() {
        val engine = BackupSyncEngine.forTest(
            owner = FakeOwner(),
            settings = FakeSettings(),
            credentials = InMemoryCredentialStore(null),
            network = FixedNetworkStatus(online = true),
            stagingDir = staging,
            transportFactory = { _, _ -> FakeTransport() },
        )
        val result = kotlinx.coroutines.runBlocking { engine.syncNow() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.contains("凭据已失效"))
    }

    // ---- 夹具 ----

    private fun engine(
        owner: FakeOwner,
        settings: FakeSettings,
        remote: FakeTransport,
    ) = BackupSyncEngine.forTest(
        owner = owner,
        settings = settings,
        credentials = InMemoryCredentialStore(CredentialStore.Credentials("me@example.com", "app-pass")),
        network = FixedNetworkStatus(online = true),
        stagingDir = staging,
        transportFactory = { _, _ -> remote },
        now = { 1_800_000_000_000L },
    )

    private class FakeOwner(
        private val noteCount: Int = 2,
        private val backupId: String = "bk-test",
        private val complete: Boolean = true,
    ) : CloudBackupOwner {

        var lastExportedBytes: Int = 0
            private set

        override suspend fun exportBackup(target: File): Boolean {
            val bytes = TestBackupPackage.bytes(
                TestBackupPackage.Spec(
                    backupId = backupId,
                    noteCount = noteCount,
                    noteBodies = List(noteCount) { "note-" + it },
                    complete = complete,
                ),
            )
            target.writeBytes(bytes)
            lastExportedBytes = bytes.size
            return true
        }

        override suspend fun libraryId(): String = "lib-test"

        override fun appVersion(): String = "1.2.20-test"

        override suspend fun noteCount(): Int = noteCount

        override suspend fun verifyBackupFile(file: File): CloudBackupOwner.BackupInspection =
            file.inputStream().use { verifyBackup(it) }

        override suspend fun verifyBackup(source: InputStream): CloudBackupOwner.BackupInspection =
            TestBackupPackage.inspect(source)
    }

    private class FakeSettings(
        configured: Boolean = true,
        serverUrl: String = "https://dav.example.com/dav",
        dir: String = "purenote",
    ) : SyncSettingsStore {

        var remote: SyncSettingsStore.RemoteConfig? =
            if (configured) SyncSettingsStore.RemoteConfig(serverUrl, dir, enabled = true) else null
        var meta: SyncSettingsStore.SyncMeta = SyncSettingsStore.SyncMeta()

        override fun loadRemote(): SyncSettingsStore.RemoteConfig? = remote

        override fun saveRemote(config: SyncSettingsStore.RemoteConfig) {
            remote = config
        }

        override fun clearRemote() {
            remote = null
        }

        override fun loadMeta(): SyncSettingsStore.SyncMeta = meta

        override fun saveMeta(meta: SyncSettingsStore.SyncMeta) {
            this.meta = meta
        }
    }

    /** 内存假远端：字节真存、读回来真读，不做"假装成功"。 */
    private class FakeTransport(
        private val readBackOverride: ByteArray? = null,
        /** 只让"上传回执"报错大小：用来验证重试与最终判定，stat 仍然诚实 */
        private val uploadReportedSizeDelta: Int = 0,
        private val missingDirectory: Boolean = false,
        private val statReturnsNull: Boolean = false,
    ) : RemoteTransport {

        val files = linkedMapOf<String, ByteArray>()
        var openReadCount = 0
            private set
        var statCount = 0
            private set
        var uploadCount = 0
            private set

        override val capabilities: StoreCapabilities = StoreCapabilities()

        override suspend fun list(prefix: String): List<RemoteEntry> {
            if (missingDirectory) throw RemoteException.NotFound(prefix)
            val dir = RemotePaths.normalizeRelative(prefix)
            return files.map { (path, bytes) ->
                RemoteEntry(path = path, sizeBytes = bytes.size.toLong(), etag = "etag-" + bytes.size)
            }.filter { dir.isEmpty() || it.path.startsWith(dir + "/") }
        }

        override suspend fun openRead(path: String): InputStream {
            openReadCount++
            val bytes = readBackOverride ?: files[path] ?: throw RemoteException.NotFound(path)
            return bytes.inputStream()
        }

        override suspend fun stat(path: String): RemoteEntry? {
            statCount++
            if (statReturnsNull) return null
            val bytes = files[path] ?: return null
            return RemoteEntry(path, bytes.size.toLong(), etag = "etag")
        }

        override suspend fun upload(
            path: String,
            source: InputStream,
            length: Long,
            ifMatch: String?,
        ): RemoteEntry {
            uploadCount++
            val bytes = source.use { it.readBytes() }
            assertEquals("上传长度必须与内容一致", length, bytes.size.toLong())
            files[path] = bytes
            return RemoteEntry(path, bytes.size.toLong() + uploadReportedSizeDelta, etag = "etag-new")
        }

        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }
}
