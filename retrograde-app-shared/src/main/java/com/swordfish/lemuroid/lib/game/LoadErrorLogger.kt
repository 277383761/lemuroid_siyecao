package com.swordfish.lemuroid.lib.game

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Persists unexpected game-load errors to a file we can actually read afterwards.
 *
 * Some devices (e.g. old Android TVs) have an empty logcat buffer, and
 * [Context.getExternalFilesDir] may point at a directory that does not exist yet
 * (or is not writable) — which made earlier diagnostics silently fail. The app's
 * internal [Context.getFilesDir] is always writable, so we write there.
 */
object LoadErrorLogger {

    private const val FILE_NAME = "load_error.log"

    /** Writes the full stack trace and returns a short, on-screen-safe summary. */
    fun log(context: Context, tag: String, throwable: Throwable, maxFrames: Int = 20): String {
        val head = "${throwable.javaClass.name}: ${throwable.message}"
        Log.e(tag, "Unexpected error during game load. $head", throwable)
        runCatching {
            val dir = context.filesDir.apply { mkdirs() }
            File(dir, FILE_NAME).appendText(
                "${java.util.Date()} $head\n${Log.getStackTraceString(throwable)}\n\n"
            )
        }
        // Also try a world-readable location; harmless if it fails (no storage permission).
        runCatching {
            File("/sdcard", FILE_NAME).appendText(
                "${java.util.Date()} $head\n${Log.getStackTraceString(throwable)}\n\n"
            )
        }
        return buildString {
            append(head)
            throwable.stackTrace.take(maxFrames).forEach { frame ->
                append("\n  at ").append(frame.toString())
            }
        }
    }
}
