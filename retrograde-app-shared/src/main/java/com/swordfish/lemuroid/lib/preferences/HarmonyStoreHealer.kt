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
 * `prefs.transaction.old`). The transaction log is NOT expendable: every recent
 * `commit()` (pad ports, key bindings, ...) stays there until Harmony compacts it
 * into `prefs.data` on a later load. Deleting it therefore rolls back the latest
 * settings, which is why per-device player port assignments appeared to be ignored
 * when a game was launched in a fresh process.
 *
 * Harmony 1.1.9 already copes with a half-written tail on its own: it logs the
 * partial transaction, keeps the valid prefix and compacts what it could read.
 * So healing must only touch stores whose `prefs.data` snapshot itself is broken:
 *  - `prefs.data` parses as valid JSON → leave the whole store untouched and let
 *    Harmony replay the transaction log as usual.
 *  - `prefs.data` is missing or not valid JSON → the snapshot itself is broken;
 *    wipe the whole store directory so Harmony recreates it from defaults.
 */
object HarmonyStoreHealer {

    private const val TAG = "HarmonyStoreHealer"
    private const val HARMONY_ROOT = "harmony_prefs"
    private const val MAIN_FILE = "prefs.data"

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
            // Never delete the transaction log here: it holds the most recent writes,
            // which Harmony will merge into the snapshot by itself on the next load.
            return
        }

        // Snapshot missing or corrupt: start fresh. Back it up first, just in case.
        runCatching {
            val backup = File(store.parentFile, store.name + ".bak")
            backup.deleteRecursively()
            if (mainFile.isFile) mainFile.copyTo(File(backup, MAIN_FILE), overwrite = true)
        }
        store.deleteRecursively()
        log(context, "Wiped corrupt store '${store.name}' (snapshot invalid/missing). Recreated fresh.")
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
