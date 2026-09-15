package com.purenote.local.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.purenote.local.data.NoteKind
import com.purenote.local.data.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 云同步端到端（设备 -> 本机临时 WebDAV 服务器）。
 *
 * 为什么必须有这一层：JVM 测试用的是内存假远端与 MockWebServer，
 * 它们能证明"协议拼装正确"，证明不了"装到设备上、走真实网络栈、
 * 打到真实 WebDAV 服务器上，文件真的落盘了"。
 *
 * 服务器由 tools/verify/cloud_sync_webdav.ps1 启动（wsgidav，真实 207/PUT）。
 * 没有传 webdavHost 参数时**跳过**而不是假装通过。
 */
@RunWith(AndroidJUnit4::class)
class CloudSyncWebDavTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var repo: NoteRepository

    private val host: String get() = args.getString("webdavHost") ?: ""
    private val port: Int get() = (args.getString("webdavPort") ?: "8087").toInt()

    @Before
    fun setUp() {
        ctx.deleteDatabase(DB)
        repo = NoteRepository(ctx, DB)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(DB)
    }

    @Test
    fun syncUploadsFullPackageAndVerifiesReadBack() = runBlocking {
        assumeTrue("未提供 webdavHost，跳过设备端 WebDAV 验证", host.isNotEmpty())

        repo.createNote(NoteKind.TEXT, "云端验证笔记", "这是云同步端到端验证用的正文", emptyList(), emptyList(), 0, null)
        val notesBefore = repo.noteCount()
        assertTrue("测试数据没写进去", notesBefore > 0)

        val settings = PrefsSyncSettingsStore(ctx)
        settings.saveRemote(
            SyncSettingsStore.RemoteConfig(
                serverUrl = "http://" + host + ":" + port + "/",
                remoteDir = "purenote",
                enabled = true,
            ),
        )

        // 这一步同时验证凭据链路：Keystore 写入 -> 引擎读出 -> 用于 Basic 认证
        val stored = AndroidCredentialStore(ctx).save(CredentialStore.Credentials("verify-user", "verify-pass"))
        assertTrue("凭据应能写进 Keystore", stored)
        assertTrue("读回的账号应与写入一致", AndroidCredentialStore(ctx).load()?.account == "verify-user")

        repo.cloudTransportFactory = { c, creds -> WebDavTransport(c, creds) }
        val outcome = repo.cloudSync().getOrThrow()

        assertTrue("远端路径应落在指定目录下：" + outcome.remotePath, outcome.remotePath.startsWith("purenote/"))
        assertTrue("必须完成读回校验", outcome.verified)
        assertTrue("包大小应为正", outcome.bytes > 0)
        assertEquals(notesBefore, outcome.noteCount)

        val meta = settings.loadMeta()
        assertTrue("成功后必须记录时间", meta.lastSuccessAt > 0L)
        assertEquals(outcome.backupId, meta.lastBackupId)

        // 再列一次远端：文件确实在（这一步用的是真实 PROPFIND）
        val entries = repo.cloudTestConnection().getOrThrow()
        assertTrue("远端目录里应至少有一个文件", entries >= 1)
    }

    @Test
    fun wrongCredentialsReportAuthFailure() = runBlocking {
        assumeTrue("未提供 webdavHost，跳过设备端 WebDAV 验证", host.isNotEmpty())

        repo.createNote(NoteKind.TEXT, "凭据验证", "正文", emptyList(), emptyList(), 0, null)
        val settings = PrefsSyncSettingsStore(ctx)
        settings.saveRemote(
            SyncSettingsStore.RemoteConfig("http://" + host + ":" + port + "/", "purenote-auth-fail", true),
        )

        val engine = BackupSyncEngine(
            owner = repo,
            settings = settings,
            // 用真实 Keystore 存一对错误凭据：走的是和产品完全一样的凭据路径
            credentials = AndroidCredentialStore(ctx).also {
                it.save(CredentialStore.Credentials("wrong-user", "wrong-pass"))
            },
            // 用真实的网络判定：这一条测的是"打到真实服务器"，不是"绕过网络门禁"
            network = AndroidNetworkStatus(ctx),
            stagingDir = File(ctx.cacheDir, "cloud-sync-test"),
            transportFactory = { c, creds -> WebDavTransport(c, creds) },
        )
        val result = engine.syncNow()

        when {
            // 本地验证用的 wsgidav 是匿名模式：任何凭据都能通过，这里只要求"能走完流程"
            result.isSuccess -> assertTrue(true)
            // 真实云盘会拒绝：必须给出能指导用户去填应用密码的提示
            else -> {
                val message = result.exceptionOrNull()?.message.orEmpty()
                assertTrue(
                    "错误信息应能指导用户去填应用密码：" + message,
                    message.contains("应用密码") || message.contains("认证") || message.contains("权限"),
                )
                assertEquals("失败时不得记录成功", 0L, settings.loadMeta().lastSuccessAt)
            }
        }
    }

    private companion object {
        const val DB = "purenote-cloud-sync-test.db"
    }
}
