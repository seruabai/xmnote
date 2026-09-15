package com.purenote.local.sync

import android.content.Context

/**
 * SharedPreferences 实现。**只放非密钥信息**，密码一律走 [CredentialStore]。
 */
class PrefsSyncSettingsStore(context: Context) : SyncSettingsStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun loadRemote(): SyncSettingsStore.RemoteConfig? {
        val url = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        return SyncSettingsStore.RemoteConfig(
            serverUrl = url,
            remoteDir = prefs.getString(KEY_DIR, WebDavConfig.DEFAULT_DIR).orEmpty(),
            enabled = prefs.getBoolean(KEY_ENABLED, false),
        )
    }

    override fun saveRemote(config: SyncSettingsStore.RemoteConfig) {
        prefs.edit()
            .putString(KEY_SERVER, config.serverUrl)
            .putString(KEY_DIR, config.remoteDir)
            .putBoolean(KEY_ENABLED, config.enabled)
            .apply()
    }

    override fun clearRemote() {
        prefs.edit().remove(KEY_SERVER).remove(KEY_DIR).remove(KEY_ENABLED).apply()
    }

    override fun loadMeta(): SyncSettingsStore.SyncMeta = SyncSettingsStore.SyncMeta(
        lastSuccessAt = prefs.getLong(KEY_LAST_AT, 0L),
        lastBackupId = prefs.getString(KEY_LAST_ID, "").orEmpty(),
        lastLibraryId = prefs.getString(KEY_LAST_LIBRARY, "").orEmpty(),
        lastBytes = prefs.getLong(KEY_LAST_BYTES, 0L),
        lastNoteCount = prefs.getInt(KEY_LAST_NOTES, 0),
    )

    override fun saveMeta(meta: SyncSettingsStore.SyncMeta) {
        prefs.edit()
            .putLong(KEY_LAST_AT, meta.lastSuccessAt)
            .putString(KEY_LAST_ID, meta.lastBackupId)
            .putString(KEY_LAST_LIBRARY, meta.lastLibraryId)
            .putLong(KEY_LAST_BYTES, meta.lastBytes)
            .putInt(KEY_LAST_NOTES, meta.lastNoteCount)
            .apply()
    }

    private companion object {
        const val PREFS = "purenote_cloud"
        const val KEY_SERVER = "cloud.serverUrl"
        const val KEY_DIR = "cloud.remoteDir"
        const val KEY_ENABLED = "cloud.enabled"
        const val KEY_LAST_AT = "cloud.lastSuccessAt"
        const val KEY_LAST_ID = "cloud.lastBackupId"
        const val KEY_LAST_LIBRARY = "cloud.lastLibraryId"
        const val KEY_LAST_BYTES = "cloud.lastBytes"
        const val KEY_LAST_NOTES = "cloud.lastNoteCount"
    }
}
