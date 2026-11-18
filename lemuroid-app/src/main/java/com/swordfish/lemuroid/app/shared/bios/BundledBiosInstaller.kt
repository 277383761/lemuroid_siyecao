package com.swordfish.lemuroid.app.shared.bios

import android.content.Context
import timber.log.Timber
import java.io.File

// Extracts BIOS archives bundled in the APK assets (assets/bios folder) into the libretro
// system directory, so that cores can find them without any manual setup.
// neogeo.zip is required by FBNeo/MAME2003+ to run Neo Geo games (e.g. kof98.zip).
object BundledBiosInstaller {

    // The zip is copied to the system directory root (FBNeo) plus core-specific subfolders
    // (MAME2003+ and FBNeo alternate layouts) to maximize core compatibility.
    private val TARGET_SUBDIRECTORIES = listOf("", "fbneo", "mame2003_plus")

    fun installBundledBios(context: Context) {
        runCatching {
            val systemDirectory = File(context.filesDir, "system").apply { mkdirs() }
            val bundledZips = context.assets.list(BIOS_ASSETS_FOLDER) ?: emptyArray()

            for (zipName in bundledZips.filter { it.endsWith(".zip") }) {
                for (subdirectory in TARGET_SUBDIRECTORIES) {
                    val targetDirectory = if (subdirectory.isEmpty()) {
                        systemDirectory
                    } else {
                        File(systemDirectory, subdirectory).apply { mkdirs() }
                    }
                    val targetFile = File(targetDirectory, zipName)

                    if (targetFile.isFile && targetFile.length() > 0) {
                        continue
                    }

                    context.assets.open("$BIOS_ASSETS_FOLDER/$zipName").use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    Timber.i("Installed bundled bios: ${targetFile.path}")
                }
            }
        }.onFailure {
            Timber.e(it, "Failed to install bundled bios files")
        }
    }

    private const val BIOS_ASSETS_FOLDER = "bios"
}
