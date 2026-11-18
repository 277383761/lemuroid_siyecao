package com.swordfish.lemuroid.app.shared.covers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.swordfish.lemuroid.lib.storage.network.NetworkClient
import com.swordfish.lemuroid.lib.storage.network.NetworkFileEntry
import java.io.File
import java.text.Normalizer
import timber.log.Timber

/**
 * Reader for the "天马G" (Pegasus) media layout, so existing scraped libraries can be used
 * without reorganizing covers and videos:
 *
 * ```
 * <platform dir>/
 *   Game Name.zip
 *   media/
 *     Game Name/            <- game title, or ROM file name without extension
 *       boxFront.jpg
 *       logo.png
 *       video.mp4
 * ```
 *
 * The alternative `media/<asset>/<game name>.<ext>` layout (e.g. `media/boxFront/Game.png`)
 * is supported as well.
 */
object PegasusMediaLayout {

    private val COVER_ASSET_NAMES = listOf(
        "boxFront", "box_front", "boxart2D", "boxart2d", "boxFront2D", "boxfront2d",
        "boxFull", "box_full", "box", "poster", "cover", "thumbnail", "thumb",
        "screenshot", "titlescreen", "title"
    )

    private val VIDEO_ASSET_NAMES = listOf("video", "preview", "trailer", "movie")

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "m4v", "avi")

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun baseOf(name: String) = name.substringBeforeLast('.')

    /** macOS/Linux may store accented or Japanese names in decomposed (NFD) form. */
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)

    private fun sameName(first: String, second: String) =
        normalize(first).equals(normalize(second), ignoreCase = true)

    private fun baseNameMatches(name: String, baseNames: List<String>) =
        baseNames.any { sameName(baseOf(name), it) }

    private fun isAssetDirName(name: String) =
        (COVER_ASSET_NAMES + VIDEO_ASSET_NAMES).any { it.equals(name, ignoreCase = true) }

    private fun isNamedAsset(name: String, assets: List<String>, extensions: Set<String>) =
        extensionOf(name) in extensions && assets.any { sameName(baseOf(name), it) }

    // ---------------------------------------------------------------------------------------
    // Local files
    // ---------------------------------------------------------------------------------------

    fun findLocalCover(romFile: File, baseNames: List<String>): File? = findLocal(
        romFile, baseNames,
        assets = COVER_ASSET_NAMES,
        extensions = IMAGE_EXTENSIONS
    )

    fun findLocalVideo(romFile: File, baseNames: List<String>): File? = findLocal(
        romFile, baseNames,
        assets = VIDEO_ASSET_NAMES,
        extensions = VIDEO_EXTENSIONS
    )

    private fun findLocal(
        romFile: File,
        baseNames: List<String>,
        assets: List<String>,
        extensions: Set<String>
    ): File? {
        val levels = linkedSetOf<File>()
        romFile.parentFile?.let { parent ->
            levels.add(parent)
            parent.parentFile?.let { levels.add(it) }
        }

        for (level in levels) {
            val mediaDir = File(level, "media")
            if (!mediaDir.isDirectory) continue

            val mediaChildren = runCatching { mediaDir.listFiles()?.toList() }
                .getOrNull().orEmpty()

            // media/<game name>/<asset>.<ext>
            val gameDirs = mediaChildren.filter { dir ->
                dir.isDirectory && baseNames.any { sameName(dir.name, it) }
            }
            for (gameDir in gameDirs) {
                val files = runCatching { gameDir.listFiles()?.toList() }.getOrNull().orEmpty()
                val match = files.firstOrNull { it.isFile && isNamedAsset(it.name, assets, extensions) }
                if (match != null) return match
            }

            // media/<asset>/<game name>.<ext>
            val assetDirs = mediaChildren.filter { it.isDirectory && isAssetDirName(it.name) }
            for (assetDir in assetDirs) {
                val files = runCatching { assetDir.listFiles()?.toList() }.getOrNull().orEmpty()
                val match = files.firstOrNull { file ->
                    file.isFile && extensionOf(file.name) in extensions &&
                        baseNameMatches(file.name, baseNames)
                }
                if (match != null) return match
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Storage Access Framework (content://)
    // ---------------------------------------------------------------------------------------

    private data class SafEntry(val name: String, val documentId: String, val isDirectory: Boolean)

    fun findSafCover(context: Context, uri: Uri, baseNames: List<String>): Uri? = findSaf(
        context, uri, baseNames,
        assets = COVER_ASSET_NAMES,
        extensions = IMAGE_EXTENSIONS
    )

    fun findSafVideo(context: Context, uri: Uri, baseNames: List<String>): Uri? = findSaf(
        context, uri, baseNames,
        assets = VIDEO_ASSET_NAMES,
        extensions = VIDEO_EXTENSIONS
    )

    private fun findSaf(
        context: Context,
        uri: Uri,
        baseNames: List<String>,
        assets: List<String>,
        extensions: Set<String>
    ): Uri? {
        val authority = uri.authority ?: return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)

        var levelDocumentId = documentId.substringBeforeLast('/')
        if (levelDocumentId.isEmpty()) levelDocumentId = treeDocumentId

        repeat(3) {
            if (levelDocumentId.isEmpty()) return null

            val mediaChildren = querySafChildren(context, treeUri, "$levelDocumentId/media")
            if (mediaChildren.isNotEmpty()) {
                // media/<game name>/<asset>.<ext>
                for (name in baseNames) {
                    val gameDir = mediaChildren.firstOrNull {
                        it.isDirectory && sameName(it.name, name)
                    } ?: continue

                    val match = querySafChildren(context, treeUri, gameDir.documentId)
                        .firstOrNull { !it.isDirectory && isNamedAsset(it.name, assets, extensions) }
                    if (match != null) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, match.documentId)
                    }
                }

                // media/<asset>/<game name>.<ext>
                for (assetDir in mediaChildren.filter { it.isDirectory && isAssetDirName(it.name) }) {
                    val match = querySafChildren(context, treeUri, assetDir.documentId)
                        .firstOrNull { !it.isDirectory && extensionOf(it.name) in extensions &&
                            baseNameMatches(it.name, baseNames) }
                    if (match != null) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, match.documentId)
                    }
                }
            }

            if (levelDocumentId == treeDocumentId) return null
            levelDocumentId = levelDocumentId.substringBeforeLast('/')
        }
        return null
    }

    private fun querySafChildren(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String
    ): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
        )

        val result = mutableListOf<SafEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2)
                    result.add(
                        SafEntry(displayName, documentId, mimeType == Document.MIME_TYPE_DIR)
                    )
                }
            }
        }.onFailure { Timber.e(it, "Failed to query SAF children for $childrenUri") }

        return result
    }

    // ---------------------------------------------------------------------------------------
    // Network shares (SMB / FTP / WebDAV)
    // ---------------------------------------------------------------------------------------

    fun findRemoteCover(
        client: NetworkClient,
        parentPath: String,
        baseNames: List<String>
    ): NetworkFileEntry? = findRemote(
        client, parentPath, baseNames,
        assets = COVER_ASSET_NAMES,
        extensions = IMAGE_EXTENSIONS
    )

    fun findRemoteVideo(
        client: NetworkClient,
        parentPath: String,
        baseNames: List<String>
    ): NetworkFileEntry? = findRemote(
        client, parentPath, baseNames,
        assets = VIDEO_ASSET_NAMES,
        extensions = VIDEO_EXTENSIONS
    )

    private fun findRemote(
        client: NetworkClient,
        parentPath: String,
        baseNames: List<String>,
        assets: List<String>,
        extensions: Set<String>
    ): NetworkFileEntry? {
        val mediaPath = if (parentPath.isEmpty()) "media" else "$parentPath/media"
        val mediaChildren = runCatching { client.listFiles(mediaPath) }.getOrNull()
            ?: return null

        // media/<game name>/<asset>.<ext>
        for (name in baseNames) {
            val gameDir = mediaChildren.firstOrNull {
                it.isDirectory && sameName(it.name, name)
            } ?: continue

            val children = runCatching { client.listFiles(gameDir.path) }.getOrNull() ?: continue
            children.firstOrNull { !it.isDirectory && isNamedAsset(it.name, assets, extensions) }
                ?.let { return it }
        }

        // media/<asset>/<game name>.<ext>
        for (assetDir in mediaChildren.filter { it.isDirectory && isAssetDirName(it.name) }) {
            val children = runCatching { client.listFiles(assetDir.path) }.getOrNull() ?: continue
            children.firstOrNull { entry ->
                !entry.isDirectory && extensionOf(entry.name) in extensions &&
                    baseNameMatches(entry.name, baseNames)
            }?.let { return it }
        }

        return null
    }
}
