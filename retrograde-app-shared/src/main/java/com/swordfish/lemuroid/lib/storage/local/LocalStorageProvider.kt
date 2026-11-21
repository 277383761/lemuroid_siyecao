/*
 * LocalGameLibraryProvider.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.common.kotlin.extractZipEntryToFile
import com.swordfish.lemuroid.common.kotlin.findZipRomEntryName
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalStorageProvider(
    private val context: Context,
    private val directoriesManager: DirectoriesManager
) : StorageProvider {

    override val id: String = "local"

    override val name: String = context.getString(R.string.local_storage)

    override val uriSchemes = listOf("file")

    override val prefsFragmentClass: Class<LeanbackPreferenceFragment>? = null

    override val enabledByDefault = true

    override fun listBaseStorageFiles(): Flow<List<BaseStorageFile>> =
        walkDirectory(getExternalFolder() ?: directoriesManager.getInternalRomsDirectory())

    override fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile? {
        return DocumentFileParser.parseDocumentFile(context, baseStorageFile)
    }

    private fun getExternalFolder(): File? {
        val prefString = context.getString(R.string.pref_key_legacy_external_folder)
        val preferenceManager = SharedPreferencesHelper.getLegacySharedPreferences(context)
        val folder = preferenceManager.getString(prefString, null)?.let { File(it) }
        // Preset the ROMs directory to /sdcard/roms when the user has not configured one yet.
        return folder ?: File("/sdcard/roms")
    }

    private fun walkDirectory(rootDirectory: File): Flow<List<BaseStorageFile>> = flow {
        val directories = mutableListOf(rootDirectory)

        while (directories.isNotEmpty()) {
            val directory = directories.removeAt(0)
            val groups = directory.listFiles()
                ?.filterNot { it.name.startsWith(".") }
                ?.groupBy { it.isDirectory } ?: mapOf()

            val newDirectories = groups[true] ?: listOf()
            val newFiles = groups[false] ?: listOf()

            directories.addAll(newDirectories)
            emit((newFiles.map { BaseStorageFile(it.name, it.length(), it.toUri(), it.path) }))
        }
    }

    // There is no need to handle anything. Data file have to be in the same directory for detection we expect them
    // to still be there.
    private fun getDataFile(dataFile: DataFile): File {
        val dataFilePath = Uri.parse(dataFile.fileUri).path
        return File(dataFilePath)
    }

    private fun getGameRom(game: Game): File {
        val gamePath = Uri.parse(game.fileUri).path
        val originalFile = File(gamePath)
        if (!originalFile.isZipped()) {
            return originalFile
        }

        val supportedExtensions = getExtractableRomExtensions(game)
        // Arcade systems (FBNeo/MAME) only support the archive itself and read it natively.
        if (supportedExtensions.isEmpty()) {
            return originalFile
        }

        val cacheFile = GameCacheUtils.getCacheFileForGame(LOCAL_STORAGE_CACHE_SUBFOLDER, context, game)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile
        }

        val entryName = originalFile.findZipRomEntryName(supportedExtensions, game.fileName)
            ?: throw java.io.FileNotFoundException("No supported ROM entry in ${originalFile.name}")

        // Use the crash-safe extractor: ZipInputStream.getNextEntry() throws
        // UTFDataFormatException on Android 5/6 for non-ASCII entry names.
        if (!originalFile.extractZipEntryToFile(entryName, cacheFile) || cacheFile.length() == 0L) {
            cacheFile.delete()
            throw java.io.IOException("Failed to extract entry '$entryName' from ${originalFile.name}")
        }

        return cacheFile
    }

    /** Extensions that must be extracted out of the archive before being handed to the core.
     *  Empty for systems whose core reads the zip itself (arcade). */
    private fun getExtractableRomExtensions(game: Game): Set<String> {
        val system = runCatching { GameSystem.findById(game.systemId) }.getOrNull() ?: return emptySet()
        return system.supportedExtensions
            .map { it.lowercase() }
            .filterNot { it == "zip" || it == "7z" }
            .toSet()
    }

    override fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean
    ): RomFiles {
        return RomFiles.Standard(listOf(getGameRom(game)) + dataFiles.map { getDataFile(it) })
    }

    override fun getInputStream(uri: Uri): InputStream {
        return File(uri.path).inputStream()
    }

    companion object {
        const val LOCAL_STORAGE_CACHE_SUBFOLDER = "local-storage-games"
    }
}
