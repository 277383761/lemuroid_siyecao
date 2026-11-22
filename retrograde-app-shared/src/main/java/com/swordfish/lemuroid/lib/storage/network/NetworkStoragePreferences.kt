package com.swordfish.lemuroid.lib.storage.network

import android.content.Context
import android.content.SharedPreferences
import com.frybits.harmony.getHarmonySharedPreferences
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import java.io.File

object NetworkStoragePreferences {

    private const val KEY_ENABLED = "network_storage_enabled"
    private const val KEY_PROTOCOL = "network_storage_protocol"
    private const val KEY_HOST = "network_storage_host"
    private const val KEY_PORT = "network_storage_port"
    private const val KEY_USERNAME = "network_storage_username"
    private const val KEY_PASSWORD = "network_storage_password"
    private const val KEY_REMOTE_PATH = "network_storage_remote_path"
    private const val KEY_SYNC_ENABLED = "network_storage_sync_enabled"
    private const val KEY_SYNC_COMPLETED_AT = "network_storage_sync_completed_at"
    private const val PREFS_FILE_NAME = "network_storage_options"
    private const val KEY_MIGRATED = "network_storage_migrated_v1"

    /** Network settings live in their own file. The game process writes general options such as
     *  pad ports and autosave; sharing one Harmony file let its stale copy erase the server. */
    private fun prefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val networkPrefs = appContext.getHarmonySharedPreferences(PREFS_FILE_NAME)
        migrateLegacyPreferences(appContext, networkPrefs)
        return networkPrefs
    }

    private fun migrateLegacyPreferences(context: Context, target: SharedPreferences) {
        if (target.getBoolean(KEY_MIGRATED, false)) return
        synchronized(this) {
            if (target.getBoolean(KEY_MIGRATED, false)) return
            val legacy = SharedPreferencesHelper.getSharedPreferences(context)
            target.edit()
                .putBoolean(KEY_ENABLED, legacy.getBoolean(KEY_ENABLED, false))
                .putString(KEY_PROTOCOL, legacy.getString(KEY_PROTOCOL, null))
                .putString(KEY_HOST, legacy.getString(KEY_HOST, ""))
                .putString(KEY_PORT, legacy.getString(KEY_PORT, "0"))
                .putString(KEY_USERNAME, legacy.getString(KEY_USERNAME, ""))
                .putString(KEY_PASSWORD, legacy.getString(KEY_PASSWORD, ""))
                .putString(KEY_REMOTE_PATH, legacy.getString(KEY_REMOTE_PATH, ""))
                .putBoolean(KEY_SYNC_ENABLED, legacy.getBoolean(KEY_SYNC_ENABLED, false))
                .putLong(KEY_SYNC_COMPLETED_AT, legacy.getLong(KEY_SYNC_COMPLETED_AT, 0L))
                .putBoolean(KEY_MIGRATED, true)
                .commit()
        }
    }

    fun load(context: Context): NetworkStorageConfig {
        val p = prefs(context)
        return NetworkStorageConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            protocol = NetworkProtocol.fromName(p.getString(KEY_PROTOCOL, null))
                ?: NetworkProtocol.SMB,
            host = p.getString(KEY_HOST, "") ?: "",
            port = runCatching { p.getString(KEY_PORT, "0")?.toInt() }.getOrNull() ?: 0,
            username = p.getString(KEY_USERNAME, "") ?: "",
            password = p.getString(KEY_PASSWORD, "") ?: "",
            remotePath = p.getString(KEY_REMOTE_PATH, "") ?: ""
        )
    }

    fun save(context: Context, config: NetworkStorageConfig) {
        // Committed synchronously: the game process reads this right after the settings screen.
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_PROTOCOL, config.protocol.name)
            .putString(KEY_HOST, config.host)
            .putString(KEY_PORT, config.port.toString())
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_REMOTE_PATH, config.remotePath)
            .commit()
    }

    fun saveSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SYNC_ENABLED, enabled).commit()
    }

    fun isSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SYNC_ENABLED, false)

    /** Set once a sync has finished successfully, so the library can trust the local copies. */
    fun markSyncCompleted(context: Context) {
        prefs(context).edit().putLong(KEY_SYNC_COMPLETED_AT, System.currentTimeMillis()).commit()
    }

    fun hasCompletedSync(context: Context): Boolean =
        prefs(context).getLong(KEY_SYNC_COMPLETED_AT, 0L) > 0L

    fun clearCache(context: Context) {
        val cacheRoot = context.cacheDir
        listOf(NETWORK_GAMES_CACHE_DIR, "network-games", NETWORK_COVERS_CACHE_DIR).forEach { sub ->
            runCatching { File(cacheRoot, sub).deleteRecursively() }
        }
    }

    /** Root of the local ROM library, which is also the destination of the offline sync. */
    fun romsDirectory(context: Context): File {
        val preferenceKey = context.getString(R.string.pref_key_legacy_external_folder)
        val configured = SharedPreferencesHelper.getLegacySharedPreferences(context)
            .getString(preferenceKey, null)
        return configured?.takeIf { it.isNotBlank() }?.let(::File) ?: File("/sdcard/roms")
    }

    /** File already synced to the local ROM directory, or null when it is not there yet. */
    fun localSyncedFile(context: Context, remotePath: String): File? {
        val root = load(context).remotePath.replace('\\', '/').trim('/')
        val fullPath = remotePath.replace('\\', '/').trim('/')
        val relative = when {
            root.isEmpty() -> fullPath
            fullPath == root -> return null
            fullPath.startsWith("$root/") -> fullPath.substring(root.length + 1)
            else -> return null
        }
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        val file = File(romsDirectory(context), segments.joinToString("/"))
        return file.takeIf { it.isFile }
    }

    const val NETWORK_GAMES_CACHE_DIR = "network-games-v2"
    const val NETWORK_COVERS_CACHE_DIR = "network-covers"

    // Preference keys exposed for the preference XML.
    const val PREF_KEY_ENABLED = KEY_ENABLED
    const val PREF_KEY_PROTOCOL = KEY_PROTOCOL
    const val PREF_KEY_HOST = KEY_HOST
    const val PREF_KEY_PORT = KEY_PORT
    const val PREF_KEY_USERNAME = KEY_USERNAME
    const val PREF_KEY_PASSWORD = KEY_PASSWORD
    const val PREF_KEY_REMOTE_PATH = KEY_REMOTE_PATH
    const val PREF_KEY_SYNC_ENABLED = KEY_SYNC_ENABLED
}
