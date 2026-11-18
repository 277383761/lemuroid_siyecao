package com.swordfish.lemuroid.app.shared.systems

import android.content.Context
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.SystemID

/**
 * Systems disabled by the user (e.g. 3DS on a TV box that cannot emulate it).
 *
 * Disabled systems are hidden from the system list and their games are excluded from the
 * "All games" section. The list is kept out of the normal settings so it survives a
 * "reset settings".
 */
object DisabledSystemsPreferences {

    private const val PREFS_NAME = "lemuroid_disabled_systems"
    private const val KEY_DISABLED_SYSTEM_IDS = "disabled_system_ids"

    fun disabledSystemIds(context: Context): Set<String> =
        preferences(context).getStringSet(KEY_DISABLED_SYSTEM_IDS, emptySet())?.toSet()
            ?: emptySet()

    fun isMetaSystemDisabled(context: Context, metaSystem: MetaSystemID): Boolean {
        if (metaSystem == MetaSystemID.ALL) return false
        val disabled = disabledSystemIds(context)
        return metaSystem.systemIDs.isNotEmpty() &&
            metaSystem.systemIDs.all { it.dbname in disabled }
    }

    fun setMetaSystemDisabled(context: Context, metaSystem: MetaSystemID, disabled: Boolean) {
        if (metaSystem == MetaSystemID.ALL) return
        val ids = disabledSystemIds(context).toMutableSet()
        metaSystem.systemIDs.forEach { systemId ->
            if (disabled) ids.add(systemId.dbname) else ids.remove(systemId.dbname)
        }
        preferences(context).edit().putStringSet(KEY_DISABLED_SYSTEM_IDS, ids).apply()
    }

    /** System ids that can still be shown: used to filter the "All games" section. */
    fun enabledDbNames(context: Context): Set<String> =
        SystemID.values().map { it.dbname }.toSet() - disabledSystemIds(context)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
