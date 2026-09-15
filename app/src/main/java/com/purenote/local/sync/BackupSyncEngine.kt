package com.purenote.local.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * 云备份引擎（H 阶段）。
 *
 * 交付边界（规范 §17 阶段 H）：**传输完整备份包**（阶段 E 的 zip，带 manifest 与逐项 SHA-256），
 * 独立验证，**不顺带实现未经设计的双向同步**。
 *
 * 一次同步的固定顺序（顺序本身是设计的一部分，不能并行化）：
 *   1. 门禁：是否有网、是否配置、凭据是否可用；
 *   2. 导出到 cacheDir 暂存；
 *   3. **上传前**读回校验清单（本地包都不完整，就不要传上去骗自己）；
 *   4. 远端列举（用于识别覆盖写入）；
 *   5. 上传；
 *   6. **上传后读回校验**：比对大小，并把远端那份整包读回来核对清单与 backupId；
 *   7. 只有第 6 步通过才记录"同步成功"。
 *
 * 第 6 步不是多余的：WebDAV 的 PUT 返回 2xx 只证明"服务器接受了请求"，不证明
 * "用户之后能下载到一个完整的包"。阶段 E 对 SAF 外部副本已经立了同样的规矩，
 * 云盘这条路径必须一致 —— 否则"备份成功"就是一句没有证据的话。
 */
class BackupSyncEngine(
    private val owner: CloudBackupOwner,
    private val settings: SyncSettingsStore,
    private val credentials: CredentialStore,
    private val network: NetworkStatus,
    private val stagingDir: File,
    private val transportFactory: (WebDavConfig, CredentialStore.Credentials) -> RemoteTransport =
        { config, creds -> WebDavTransport(config, creds) },
    private val now: () -> Long = System::currentTimeMillis,
) {

    enum class Phase { EXPORTING, UPLOADING, VERIFYING }

    sealed interface State {
        data object Idle : State
        data class Running(val phase: Phase, val detail: String = "") : State
        data class Done(val summary: String, val warnings: List<String> = emptyList()) : State
        data class Failed(val message: String) : State
    }

    data class Outcome(
        val remotePath: String,
        val backupId: String,
        val bytes: Long,
        val noteCount: Int,
        val verified: Boolean,
        val warnings: List<String> = emptyList(),
    )

    /** 探测连通性：认证 + 列出远端目录。成功返回远端目录下的文件数。 */
    suspend fun testConnection(): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingIo {
            val config = requireConfig()
            val creds = requireCredentials()
            val transport = transportFactory(config, creds)
            val dir = RemotePaths.normalizeRelative(config.remoteDir)
            // 目录还不存在是**正常状态**，不是失败：认证已经通过，首次同步会自动建出来。
            // 把它报成错误会让用户在"刚填好地址、还没同步过"时以为配置错了。
            val entries = try {
                transport.list(dir)
            } catch (e: RemoteException.NotFound) {
                emptyList()
            } catch (e: RemoteException.Conflict) {
                emptyList()
            }
            entries.count { !it.isDirectory }
        }
    }

    /**
     * 立即同步一次。
     * @param allowIncomplete 本地包本身就不完整（导出时有附件缺失）时是否仍要上传。
     *        默认 false：宁可失败并说清原因，也不要把一份抢救包当成正式备份传上去。
     */
    suspend fun syncNow(
        allowIncomplete: Boolean = false,
        onState: (State) -> Unit = {},
    ): Result<Outcome> = withContext(Dispatchers.IO) {
        if (!network.isOnline()) {
            return@withContext Result.failure(
                RemoteException.Unreachable("当前没有网络连接，请连上 Wi-Fi 或移动网络后重试"),
            )
        }

        val staged = File(stagingDir, "cloud-sync-" + now() + ".tmp")
        try {
            val config = requireConfig()
            val creds = requireCredentials()

            onState(State.Running(Phase.EXPORTING, "正在生成本地备份包"))
            staged.parentFile?.mkdirs()
            if (!owner.exportBackup(staged)) {
                return@withContext Result.failure(IllegalStateException("本地备份导出失败，已取消上传"))
            }

            onState(State.Running(Phase.EXPORTING, "正在校验本地备份包"))
            val local = owner.verifyBackupFile(staged)
            val size = staged.length()
            val warnings = mutableListOf<String>()
            if (!local.complete) {
                if (!allowIncomplete) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "本地备份包本身不完整（导出时有附件缺失），已取消上传；" +
                                "请先用「导出备份」确认附件是否还在设备上",
                        ),
                    )
                }
                warnings += "这份备份导出时就有附件缺失，属抢救包而非完整备份"
            }

            val transport = transportFactory(config, creds)
            val dir = RemotePaths.normalizeRelative(config.remoteDir)
            val fileName = SyncContract.fileName(now())
            val remotePath = SyncContract.remotePath(dir, fileName)
            val existing = listQuietly(transport, dir)

            onState(State.Running(Phase.UPLOADING, "正在上传 " + (size / 1024) + " KB"))
            uploadWithRetry(transport, remotePath, staged, size)

            onState(State.Running(Phase.VERIFYING, "正在读回远端副本核对"))
            verifyRemoteCopy(transport, remotePath, local, size)

            val meta = settings.loadMeta()
            settings.saveMeta(
                meta.copy(
                    lastSuccessAt = now(),
                    lastBackupId = local.backupId,
                    lastLibraryId = local.libraryId,
                    lastBytes = size,
                    lastNoteCount = owner.noteCount(),
                ),
            )
            if (existing.any { !it.isDirectory && it.path == remotePath }) {
                warnings += "远端已存在同名文件，本次为覆盖写入（已核验覆盖后的内容）"
            }

            onState(
                State.Done(
                    "已上传并验证：" + fileName + "（" + (size / 1024) + " KB）",
                    warnings,
                ),
            )
            Result.success(
                Outcome(
                    remotePath = remotePath,
                    backupId = local.backupId,
                    bytes = size,
                    noteCount = local.noteCount,
                    verified = true,
                    warnings = warnings,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onState(State.Failed(describe(e)))
            Result.failure(e)
        } finally {
            staged.delete()
        }
    }

    companion object {
        /**
         * 测试装配点：全部依赖显式传入，不碰 Keystore、不碰 SharedPreferences。
         * 存在这个 builder 的理由与 [forApp] 相同——装配只写一遍，
         * 测试与生产不会各自演化出不同的默认值。
         */
        fun forTest(
            owner: CloudBackupOwner,
            settings: SyncSettingsStore,
            credentials: CredentialStore,
            network: NetworkStatus,
            stagingDir: File,
            transportFactory: (WebDavConfig, CredentialStore.Credentials) -> RemoteTransport,
            now: () -> Long = System::currentTimeMillis,
        ): BackupSyncEngine = BackupSyncEngine(
            owner = owner,
            settings = settings,
            credentials = credentials,
            network = network,
            stagingDir = stagingDir,
            transportFactory = transportFactory,
            now = now,
        )

        /**
         * 生产装配点：把引擎需要的 Android 侧实现一次装好。
         *
         * 抽成工厂而不是让 ViewModel 逐项 new：装配细节只写一遍，
         * 改动（换存储、加代理、改超时）不会在两个调用点漂移。
         */
        fun forApp(
            context: Context,
            owner: CloudBackupOwner,
            transportFactory: (WebDavConfig, CredentialStore.Credentials) -> RemoteTransport =
                { config, creds -> WebDavTransport(config, creds) },
        ): BackupSyncEngine = BackupSyncEngine(
            owner = owner,
            settings = PrefsSyncSettingsStore(context),
            credentials = AndroidCredentialStore(context),
            network = AndroidNetworkStatus(context),
            stagingDir = File(context.cacheDir, "cloud-sync"),
            transportFactory = transportFactory,
        )
    }

    /** 远端最近一份备份（路径 + 时间）；列不出来或被限流时返回失败。 */
    suspend fun remoteLatest(): Result<Pair<String, Long>?> = withContext(Dispatchers.IO) {
        runCatchingIo {
            val config = requireConfig()
            val creds = requireCredentials()
            val dir = RemotePaths.normalizeRelative(config.remoteDir)
            listQuietly(transportFactory(config, creds), dir)
                .filter { !it.isDirectory && SyncContract.isBackupFile(it.path) }
                .maxByOrNull { it.lastModified ?: 0L }
                ?.let { it.path to (it.lastModified ?: 0L) }
        }
    }

    // ---- 内部实现 ----

    private fun requireConfig(): WebDavConfig {
        val remote = settings.loadRemote() ?: throw IllegalStateException("还没有配置云同步")
        if (!remote.enabled) throw IllegalStateException("云同步未开启")
        return WebDavConfig(
            baseUrl = RemotePaths.normalizeBase(remote.serverUrl),
            remoteDir = RemotePaths.normalizeRelative(remote.remoteDir),
        )
    }

    private suspend fun requireCredentials(): CredentialStore.Credentials =
        credentials.load() ?: throw IllegalStateException("凭据已失效，请重新填写账号与应用密码")

    /** 列举：目录还不存在（404/409）时视为空目录，首次同步会自动建目录。 */
    private suspend fun listQuietly(transport: RemoteTransport, dir: String): List<RemoteEntry> = try {
        transport.list(dir)
    } catch (e: RemoteException.NotFound) {
        emptyList()
    } catch (e: RemoteException.Conflict) {
        emptyList()
    }

    private suspend fun uploadWithRetry(
        transport: RemoteTransport,
        remotePath: String,
        file: File,
        size: Long,
    ): RemoteEntry {
        val first = runCatchingIo { transport.upload(remotePath, file.inputStream(), size) }
        // 服务器自己说收到的大小和本地不一致：**不重试**。
        // 这不是"连接抖了一下"，多半是服务器/代理在改写或截断请求，重传一遍同样会不一致，
        // 只会白白多花一次上行流量，还多占一次限流配额。
        first.getOrNull()?.let { entry ->
            if (entry.sizeBytes >= 0 && entry.sizeBytes != size) {
                throw RemoteException.Transport("服务器回执的大小与本地不一致，已按失败处理")
            }
            return entry
        }
        val failure = first.exceptionOrNull() ?: throw RemoteException.Transport("上传失败且没有原因")
        // 只对"可能什么都没写上去"的瞬时错误重试一次；认证/冲突/超限重试没有意义
        val retryable = failure is RemoteException.Transport || failure is RemoteException.Unreachable
        if (!retryable) throw failure
        return transport.upload(remotePath, file.inputStream(), size)
    }

    /**
     * 上传后的读回校验。
     *
     * 三道检查缺一不可：
     *  1. 远端报告的大小与本地一致（拦住"只传了半包"）；
     *  2. 服务器回执的大小与本地一致；
     *  3. **把远端整包读回来跑一遍清单校验**，并确认 backupId 与本次那份相同
     *     （拦住"读到的其实是另一个文件"）。
     */
    private suspend fun verifyRemoteCopy(
        transport: RemoteTransport,
        remotePath: String,
        local: CloudBackupOwner.BackupInspection,
        size: Long,
    ) {
        val stat = transport.stat(remotePath)
        if (stat != null && stat.sizeBytes >= 0 && stat.sizeBytes != size) {
            throw RemoteException.Transport(
                "远端读回的大小与本地不一致（本地 " + size + "，远端 " + stat.sizeBytes + "），远端可能只收到了半包",
            )
        }
        val inspection = try {
            transport.openRead(remotePath).use { input: InputStream ->
                input.use { owner.verifyBackup(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException.Transport(
                "无法读回远端副本完成校验：" + (e.message ?: e::class.java.simpleName),
                e,
            )
        }
        if (inspection.backupId != local.backupId) {
            throw RemoteException.Transport("远端那份包的标识与本次上传的不一致，说明读到的是另一个文件；已按失败处理")
        }
        if (inspection.noteCount != local.noteCount) {
            throw RemoteException.Transport("远端副本的笔记条数与本地不一致，已按失败处理")
        }
    }

    /** 把异常翻成用户可读的一句话。 */
    private fun describe(e: Throwable): String = when (e) {
        is RemoteException -> e.message ?: "云端操作失败"
        is IllegalStateException -> e.message ?: "同步未开始"
        is java.io.IOException -> "本地读写失败：" + (e.message ?: e::class.java.simpleName)
        else -> e.message ?: e::class.java.simpleName
    }

    /** runCatching 会把 CancellationException 一起吞掉，协程里必须显式放过它。 */
    private inline fun <T> runCatchingIo(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
