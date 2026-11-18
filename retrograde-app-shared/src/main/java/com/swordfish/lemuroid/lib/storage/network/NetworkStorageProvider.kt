package com.swordfish.lemuroid.lib.storage.network

import android.content.Context
import android.net.Uri
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.common.kotlin.extractEntryToFile
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

class NetworkStorageProvider(private val context: Context) : StorageProvider {

    override val id: String = "network"

    override val name: String = context.getString(R.string.network_storage)

    override val uriSchemes = listOf(
        NetworkProtocol.SMB.scheme,
        NetworkProtocol.FTP.scheme,
        NetworkProtocol.WEBDAV.scheme
    )

    override val prefsFragmentClass: Class<out LeanbackPreferenceFragment>? = null

    /** Follow the "Enable network storage" switch instead of the provider-level preference,
     *  otherwise the library would never index this provider. */
    override val enabledByDefault: Boolean
        get() = NetworkStoragePreferences.load(context).enabled

    private fun currentConfig(): NetworkStorageConfig = NetworkStoragePreferences.load(context)

    override fun listBaseStorageFiles(): Flow<List<BaseStorageFile>> {
        val config = currentConfig()
        if (!config.isConfigured) return emptyFlow()

        return flow {
            val client = runCatching { NetworkClientFactory.create(config) }
                .onFailure { Timber.e(it, "Failed to create network client") }
                .getOrNull() ?: return@flow

            var total = 0
            try {
                walkRemoteDirectory(client, config.remotePath).collect {
                    total += it.size
                    emit(it)
                }
                Timber.i("Network storage listed $total file(s) from ${config.authority}${config.remotePath}")
            } catch (e: Throwable) {
                Timber.e(e, "Error while listing network files")
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun walkRemoteDirectory(
        client: NetworkClient,
        rootPath: String
    ): Flow<List<BaseStorageFile>> = flow {
        val directories = mutableListOf(rootPath)

        while (directories.isNotEmpty()) {
            val directory = directories.removeAt(0)
            val entries = runCatching { client.listFiles(directory) }
                .onFailure { Timber.e(it, "Failed to list $directory") }
                .getOrDefault(emptyList())

            val (files, dirs) = entries.partition { !it.isDirectory }

            directories.addAll(dirs.map { it.path })

            if (files.isNotEmpty()) {
                val config = currentConfig()
                emit(files.map { entry ->
                    BaseStorageFile(
                        name = entry.name,
                        size = entry.size,
                        uri = buildUri(config, entry.path),
                        path = entry.path
                    )
                })
            }
        }
    }

    private fun buildUri(config: NetworkStorageConfig, remotePath: String): Uri {
        val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        return Uri.parse("${config.protocol.scheme}://${config.authority}$path")
    }

    override fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile {
        // For network files we do not download to compute CRC/serial during indexing.
        // Metadata matching falls back to filename + extension.
        return StorageFile(
            name = baseStorageFile.name,
            size = baseStorageFile.size,
            crc = null,
            serial = null,
            uri = baseStorageFile.uri,
            path = baseStorageFile.path
        )
    }

    override fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean
    ): RomFiles {
        val config = currentConfig()
        val client = NetworkClientFactory.create(config)
        try {
            val gameFile = getGameRom(client, game)
            val dataEntries = dataFiles.map { getDataFile(client, game, it) }
            return RomFiles.Standard(listOf(gameFile) + dataEntries)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun getRemotePath(uri: Uri): String {
        return uri.path ?: uri.toString().substringAfter(uri.authority ?: "")
    }

    private val downloadLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(cacheFile: File): Any =
        downloadLocks.computeIfAbsent(cacheFile.absolutePath) { Any() }

    private fun getGameRom(client: NetworkClient, game: Game): File {
        val cacheFile = getCacheFileForGame(game)
        synchronized(lockFor(cacheFile)) {
            val uri = Uri.parse(game.fileUri)
            val remotePath = getRemotePath(uri)
            val originalFile = File(uri.lastPathSegment ?: game.fileName)

            if (!originalFile.isZipped()) {
                if (cacheFile.length() > 0) return cacheFile
                downloadAtomic(client, remotePath, cacheFile)
                return cacheFile
            }

            val zipCache = File(cacheFile.parentFile, originalFile.name)
            if (zipCache.length() == 0L) {
                downloadAtomic(client, remotePath, zipCache)
            }

            // Arcade cores (FBNeo/MAME) read the zip themselves; every other core needs the
            // bare ROM, so extract the matching entry (same behaviour as local storage).
            val entryName = findRomEntryName(zipCache, game)
            if (entryName == null) return zipCache

            val extracted = File(zipCache.parentFile, File(entryName).name)
            if (extracted.length() == 0L) {
                try {
                    ZipInputStream(zipCache.inputStream()).use {
                        it.extractEntryToFile(entryName, extracted)
                    }
                } catch (e: Throwable) {
                    // Corrupt archive or missing entry: remove so the next attempt re-downloads.
                    zipCache.delete()
                    extracted.delete()
                    throw e
                }
            }
            return extracted
        }
    }

    /** Name of the single ROM entry inside the archive, based on the extensions the game system
     *  supports. Returns null when the archive should be handed to the core untouched (arcade). */
    private fun findRomEntryName(zipFile: File, game: Game): String? {
        val system = runCatching { GameSystem.findById(game.systemId) }.getOrNull() ?: return null
        val supported = system.supportedExtensions
            .map { it.lowercase(Locale.getDefault()) }
            .toSet()
            .minus(ARCHIVE_EXTENSIONS)

        if (supported.isEmpty()) return null

        var bestName: String? = null
        var bestSize = -1L
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.getDefault())
                if (extension !in supported) continue
                if (entry.size > bestSize) {
                    bestSize = entry.size
                    bestName = entry.name
                }
            }
        }
        return bestName
    }

    private fun getDataFile(client: NetworkClient, game: Game, dataFile: DataFile): File {
        val cacheFile = getDataFileForGame(game, dataFile)
        synchronized(lockFor(cacheFile)) {
            if (cacheFile.exists()) return cacheFile
            val uri = Uri.parse(dataFile.fileUri)
            val remotePath = getRemotePath(uri)
            downloadAtomic(client, remotePath, cacheFile)
            return cacheFile
        }
    }

    /** Download to a .tmp file first, then atomically rename on success.
     *  A failed or interrupted download never leaves a half-written cache file. */
    private fun downloadAtomic(client: NetworkClient, remotePath: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            client.downloadFile(remotePath, tmp)
            if (!tmp.renameTo(target)) {
                // Fallback for filesystems where rename fails.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun getCacheFileForGame(game: Game): File {
        val dir = File(context.cacheDir, NetworkStoragePreferences.NETWORK_GAMES_CACHE_DIR)
            .apply { mkdirs() }
        return File(dir, game.fileName)
    }

    private fun getDataFileForGame(game: Game, dataFile: DataFile): File {
        val dir = File(context.cacheDir, NetworkStoragePreferences.NETWORK_GAMES_CACHE_DIR)
            .apply { mkdirs() }
        return File(dir, dataFile.fileName)
    }

    override fun getInputStream(uri: Uri): InputStream? {
        val config = currentConfig()
        if (!config.isConfigured) return null
        val client = NetworkClientFactory.create(config)
        return try {
            val remoteStream = client.getInputStream(getRemotePath(uri)) ?: return null
            // Read fully into memory so the network client can be closed immediately.
            // getInputStream is only used during indexing to read small metadata files
            // (m3u/cue), so buffering the entire content is acceptable.
            remoteStream.use { it.readBytes().inputStream() }
        } finally {
            runCatching { client.close() }
        }
    }

    private companion object {
        val ARCHIVE_EXTENSIONS = setOf("zip", "7z")
    }
}
