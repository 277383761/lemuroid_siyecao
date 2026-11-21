package com.swordfish.lemuroid.lib.preferences

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * Heals a corrupted Harmony SharedPreferences store before it is ever accessed.
 *
 * Harmony 1.x keeps the committed snapshot in `prefs.data` (JSON) and pending,
 * not-yet-merged writes in a binary transaction log (`prefs.transaction.data` /
 * `prefs.transaction.old`). If the process is killed while a transaction is being
 * written, the transaction log can be left in a half-written state. On the next
 * launch, Harmony's reader parses that log with `DataInputStream.readUTF`, which
 * throws `java.io.UTFDataFormatException` (e.g. "bad second byte at 1") and breaks
 * EVERY subsequent read of that store for the whole process — crashing game loads
 * (and anything else that reads settings).
 *
 * Harmony caches the loaded `SharedPreferences` instance in a static map, so once a
 * process has read the broken store it cannot recover within that process. This is
 * why the healing MUST run before the first access (every game launch is a fresh
 * process, so healing in [android.app.Application.onCreate] covers it).
 *
 * Strategy that never loses committed settings:
 *  - `prefs.data` parses as valid JSON → it is a good snapshot. Delete only the
 *    transaction log + lock so Harmony rebuilds in-memory state from the snapshot.
 *  - `prefs.data` is missing or not valid JSON → the snapshot itself is broken;
 *    wipe the whole store directory so Harmony recreates it from defaults.
 */
object HarmonyStoreHealer {

    private const val TAG = "HarmonyStoreHealer"
    private const val HARMONY_ROOT = "harmony_prefs"
    private const val MAIN_FILE = "prefs.data"
    private val TRANSACTION_FILES = arrayOf("prefs.transaction.data", "prefs.transaction.old")
    private const val LOCK_FILE = "prefs.data.lock"

    fun heal(context: Context) {
        val root = File(context.filesDir, HARMONY_ROOT)
        if (!root.isDirectory) return

        val stores = root.listFiles { f -> f.isDirectory } ?: return
        for (store in stores) {
            healStore(store, context)
        }
    }

    private fun healStore(store: File, context: Context) {
        val mainFile = File(store, MAIN_FILE)
        val snapshotValid = mainFile.isFile && isValidJson(mainFile)

        if (snapshotValid) {
            // Good snapshot: drop only the (possibly corrupt) pending transaction log.
            var changed = false
            for (name in TRANSACTION_FILES) {
                val f = File(store, name)
                if (f.exists()) {
                    changed = f.delete() || changed
                }
            }
            val lock = File(store, LOCK_FILE)
            if (lock.exists() && lock.length() == 0L) {
                changed = lock.delete() || changed
            }
            if (changed) {
                log(context, "Healed store '${store.name}': cleared transaction log (kept valid snapshot).")
            }
        } else {
            // Snapshot missing or corrupt: start fresh. Back it up first, just in case.
            runCatching {
                val backup = File(store.parentFile, store.name + ".bak")
                backup.deleteRecursively()
                if (mainFile.isFile) mainFile.copyTo(File(backup, MAIN_FILE), overwrite = true)
            }
            store.deleteRecursively()
            log(context, "Wiped corrupt store '${store.name}' (snapshot invalid/missing). Recreated fresh.")
        }
    }

    private fun isValidJson(file: File): Boolean {
        return runCatching {
            // Harmony writes a single top-level JSON object. Reading it fully validates structure.
            file.bufferedReader().use { JSONObject(it.readText()) }
            true
        }.getOrDefault(false)
    }

    private fun log(context: Context, message: String) {
        Log.i(TAG, message)
        runCatching {
            val dir = context.filesDir
            File(dir, "harmony_heal.log").appendText("${java.util.Date()} $message\n")
        }
    }
}
