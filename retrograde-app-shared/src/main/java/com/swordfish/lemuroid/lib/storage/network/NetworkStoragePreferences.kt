package com.swordfish.lemuroid.lib.storage.network

import android.content.Context
import android.content.SharedPreferences
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

    private fun prefs(context: Context): SharedPreferences =
        SharedPreferencesHelper.getSharedPreferences(context)

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
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_PROTOCOL, config.protocol.name)
            .putString(KEY_HOST, config.host)
            .putString(KEY_PORT, config.port.toString())
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_REMOTE_PATH, config.remotePath)
            .apply()
    }

    fun saveSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
    }

    fun clearCache(context: Context) {
        val cacheRoot = context.cacheDir
        listOf(NETWORK_GAMES_CACHE_DIR, "network-games", NETWORK_COVERS_CACHE_DIR).forEach { sub ->
            runCatching { File(cacheRoot, sub).deleteRecursively() }
        }
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
