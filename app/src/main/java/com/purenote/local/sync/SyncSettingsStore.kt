package com.purenote.local.sync

/**
 * 云同步的本地状态（不含密钥）。
 *
 * 为什么 KEY 名带 \`cloud.\` 前缀：与既有 note.* 偏好共存于同一个 SharedPreferences，
 * 前缀避免将来加偏好时撞名。
 */
interface SyncSettingsStore {

    /** 本次安装连接到哪个远端：服务地址与远端目录。 */
    fun loadRemote(): RemoteConfig?

    fun saveRemote(config: RemoteConfig)

    /** 清空远端配置与同步记录（保留凭据由调用方决定）。 */
    fun clearRemote()

    fun loadMeta(): SyncMeta

    fun saveMeta(meta: SyncMeta)

    data class RemoteConfig(
        val serverUrl: String,
        val remoteDir: String = WebDavConfig.DEFAULT_DIR,
        val enabled: Boolean = false,
    )

    data class SyncMeta(
        val lastSuccessAt: Long = 0L,
        /** 上次成功同步的备份包 backupId；用于识别"远端被别的设备换成另一份包" */
        val lastBackupId: String = "",
        val lastLibraryId: String = "",
        val lastBytes: Long = 0L,
        val lastNoteCount: Int = 0,
    )
}
