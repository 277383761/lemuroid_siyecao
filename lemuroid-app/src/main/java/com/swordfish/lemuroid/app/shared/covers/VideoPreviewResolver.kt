package com.swordfish.lemuroid.app.shared.covers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.network.NetworkClientFactory
import com.swordfish.lemuroid.lib.storage.network.NetworkProtocol
import com.swordfish.lemuroid.lib.storage.network.NetworkStoragePreferences
import java.io.File
import java.util.Collections
import okhttp3.Credentials
import timber.log.Timber

/** A playable video source for the game preview, plus the headers needed to stream it. */
data class VideoSource(val uri: Uri, val headers: Map<String, String> = emptyMap())

/**
 * Resolves the preview video of a game. Naming mirrors the local cover convention:
 * the video sits next to the ROM with the same base name (`kof97.zip` -> `kof97.mp4`)
 * and the `-video` suffix is also accepted (`kof97-video.mp4`).
 *
 * Must not be called on the main thread: network shares are listed synchronously.
 */
object VideoPreviewResolver {

    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "m4v")

    /** Resolved sources are cached per game URI, negative results are not cached. */
    private val cache = Collections.synchronizedMap(mutableMapOf<String, VideoSource>())

    fun resolve(context: Context, game: Game): VideoSource? {
        cache[game.fileUri]?.let { return it }

        val source = runCatching { findVideo(context, game) }
            .onFailure { Timber.e(it, "Failed to resolve preview video for ${game.fileName}") }
            .getOrNull()

        if (source != null) cache[game.fileUri] = source
        return source
    }

    fun clearCache() = cache.clear()

    private fun findVideo(context: Context, game: Game): VideoSource? {
        val uri = Uri.parse(game.fileUri)
        val baseNames = CoverLoader.coverBaseNames(game, uri)

        return when (uri.scheme) {
            "content" -> findSafVideo(context, uri, baseNames)
            "webdav" -> findWebDavVideo(context, uri, baseNames)
            "smb", "ftp" -> null
            else -> findLocalVideo(uri, baseNames)
        }
    }

    private fun isVideoFile(name: String, baseNames: List<String>): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in VIDEO_EXTENSIONS) return false

        val nameWithoutExtension = name.substringBeforeLast('.')
            .removeSuffix("-video")
            .removeSuffix("_video")
        return baseNames.any { it.equals(nameWithoutExtension, ignoreCase = true) }
    }

    private fun findLocalVideo(uri: Uri, baseNames: List<String>): VideoSource? {
        val path = Uri.decode(uri.path ?: "") 
        val romFile = File(path)

        val directories = linkedSetOf<File>()
        romFile.parentFile?.let { directories.add(it) }
        directories.add(File("/sdcard/roms"))
        directories.add(File("/storage/emulated/0/roms"))

        for (directory in directories) {
            val match = runCatching {
                directory.listFiles()?.firstOrNull { it.isFile && isVideoFile(it.name, baseNames) }
            }.getOrNull()
            if (match != null) return VideoSource(Uri.fromFile(match))
        }

        // Fall back to the "天马G" media layout: <dir>/media/<game name>/video.mp4
        return PegasusMediaLayout.findLocalVideo(romFile, baseNames)
            ?.let { VideoSource(Uri.fromFile(it)) }
    }

    private fun findSafVideo(context: Context, uri: Uri, baseNames: List<String>): VideoSource? {
        val authority = uri.authority ?: return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return null
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull() ?: return null
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)

        var parentDocumentId = documentId.substringBeforeLast('/', "")
        if (parentDocumentId.isEmpty()) parentDocumentId = treeDocumentId

        // Walking must not return early: the 天马G media lookup at the end still has to run
        // when the tree root is reached.
        var depth = 0
        while (depth < 3 && parentDocumentId.isNotEmpty()) {
            val match = querySafChildren(context, treeUri, parentDocumentId)
                .firstOrNull { (name, _) -> isVideoFile(name, baseNames) }
            if (match != null) {
                return VideoSource(DocumentsContract.buildDocumentUriUsingTree(treeUri, match.second))
            }

            if (parentDocumentId == treeDocumentId) break
            parentDocumentId = parentDocumentId.substringBeforeLast('/', "")
            depth++
        }

        // Fall back to the "天马G" media layout: <dir>/media/<game name>/video.mp4
        return PegasusMediaLayout.findSafVideo(context, uri, baseNames)
            ?.let { VideoSource(it) }
    }

    private fun querySafChildren(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String
    ): List<Pair<String, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val result = mutableListOf<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use {
                while (it.moveToNext()) {
                    val documentId = it.getString(0)
                    val displayName = it.getString(1) ?: continue
                    result.add(displayName to documentId)
                }
            }
        }.onFailure { Timber.e(it, "Failed to query SAF children for video $childrenUri") }

        return result
    }

    /** Only WebDAV is streamed directly; SMB/FTP would need a custom data source. */
    private fun findWebDavVideo(context: Context, uri: Uri, baseNames: List<String>): VideoSource? {
        val config = NetworkStoragePreferences.load(context)
        if (!config.isConfigured) return null
        if (config.protocol != NetworkProtocol.WEBDAV) return null

        val remotePath = uri.path ?: return null
        val parentPath = remotePath.substringBeforeLast('/', "")
        if (parentPath.isEmpty()) return null

        val client = NetworkClientFactory.create(config)
        val entry = try {
            client.listFiles(parentPath).firstOrNull { !it.isDirectory && isVideoFile(it.name, baseNames) }
                // Fall back to the "天马G" media layout: <dir>/media/<game name>/video.mp4
                ?: PegasusMediaLayout.findRemoteVideo(client, parentPath, baseNames)
        } finally {
            runCatching { client.close() }
        } ?: return null

        val scheme = if (config.isHttps) "https" else "http"
        val path = entry.path.trimStart('/')
        val url = Uri.parse("$scheme://${config.hostname}:${config.effectivePort}/$path")

        val headers = if (config.username.isNotBlank()) {
            mapOf("Authorization" to Credentials.basic(config.username, config.password))
        } else {
            emptyMap()
        }
        return VideoSource(url, headers)
    }
}
