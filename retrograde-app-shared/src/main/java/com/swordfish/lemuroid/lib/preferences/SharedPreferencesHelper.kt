package com.swordfish.lemuroid.lib.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.FileObserver
import androidx.preference.PreferenceManager
import com.frybits.harmony.getHarmonySharedPreferences
import com.swordfish.lemuroid.common.preferences.SharedPreferencesDataStore
import com.swordfish.lemuroid.lib.R

object SharedPreferencesHelper {

    fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getHarmonySharedPreferences(context.getString(R.string.pref_file_harmony_options))
    }

    /**
     * Drops the cached Harmony instances so the next access reads the file from disk again.
     *
     * The game runs in its own long-lived process. Harmony refreshes its in-memory copy through
     * a [FileObserver], which misses the writes coming from the main process on some Android 5
     * TVs. The game then sees outdated options (pad ports, enabled pads, autosave) and its own
     * writes put those stale values back over the ones saved in the settings screen.
     */
    fun reloadSharedPreferencesCache() {
        runCatching {
            // Harmony keeps its instances in a private static map that is not exposed by the
            // library API, so it is reached by name.
            val harmonyClass = Class.forName("com.frybits.harmony.Harmony")
            val mapField = harmonyClass.getDeclaredField("SINGLETON_MAP")
            mapField.isAccessible = true
            val instances = mapField.get(null)
            if (instances !is MutableMap<*, *>) return
            instances.values.forEach { instance ->
                runCatching {
                    if (instance == null) return@runCatching
                    val observerField = instance.javaClass.getDeclaredField("harmonyFileObserver")
                    observerField.isAccessible = true
                    (observerField.get(instance) as? FileObserver)?.stopWatching()
                }
            }
            instances.clear()
        }
    }

    fun getSharedPreferencesDataStore(context: Context): SharedPreferencesDataStore {
        return SharedPreferencesDataStore(getSharedPreferences(context))
    }

    /** Default shared preferences does not work with multi-process. It's currently used only for
     *  stored directory which are only read in the main process.*/
    @Deprecated("Uses standard preference manager. This is not supported in multi-processes.")
    fun getLegacySharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }
}
