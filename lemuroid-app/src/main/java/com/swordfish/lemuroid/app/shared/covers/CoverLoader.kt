package com.swordfish.lemuroid.app.shared.covers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.ImageView
import coil.ImageLoader
import coil.load
import coil.util.CoilUtils
import com.swordfish.lemuroid.common.drawable.TextDrawable
import com.swordfish.lemuroid.common.graphics.ColorUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.network.NetworkClientFactory
import com.swordfish.lemuroid.lib.storage.network.NetworkStoragePreferences
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber

class CoverLoader(private val applicationContext: Context) {

    private val imageLoader = ImageLoader.Builder(applicationContext)
        .crossfade(true)
        .okHttpClient {
            OkHttpClient.Builder()
                .cache(CoilUtils.createDefaultCache(applicationContext))
                .addNetworkInterceptor(ThrottleFailedThumbnailsInterceptor)
                .build()
        }
        .build()

    fun loadCover(game: Game, imageView: ImageView?) {
        if (imageView == null) return

        val fallbackDrawable = getFallbackDrawable(game)

        // Prefer a local cover image placed next to the ROM file with the same base name
        // (e.g. kof97.zip -> kof97.jpg / kof97.png). Local covers always win over online art.
        val localCover = findLocalCoverData(applicationContext, game)
        if (localCover != null) {
            Timber.d("Using local cover $localCover for game ${game.fileName}")
            when (localCover) {
                is File -> imageView.load(localCover, imageLoader) {
                    fallback(fallbackDrawable)
                    error(fallbackDrawable)
                }

                is Uri -> imageView.load(localCover, imageLoader) {
                    fallback(fallbackDrawable)
                    error(fallbackDrawable)
                }
            }
            return
        }

        Timber.d("No local cover found for game ${game.fileName} (${game.fileUri})")
        imageView.load(game.coverFrontUrl, imageLoader) {
            fallback(fallbackDrawable)
            error(fallbackDrawable)
        }
    }

    fun cancelRequest(imageView: ImageView) {
        // coil-kt automatically does that for us.
    }

    companion object {
        private val LOCAL_COVER_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"
        )

        private val COVER_EXTENSION_SET = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

        /* Resolves a local cover for the game. Returns a File for "file://" URIs and an android Uri
         * for "content://" (Storage Access Framework) URIs, which is what Lemuroid uses when the ROM
         * folder is selected through the system directory picker. For network URIs (smb/ftp/webdav)
         * the cover is downloaded from the network share to a local cover cache. */
        fun findLocalCoverData(context: Context, game: Game): Any? {
            val uri = runCatching { Uri.parse(game.fileUri) }.getOrNull() ?: return null
            val baseNames = coverBaseNames(game, uri)

            return when (uri.scheme) {
                "content" -> runCatching { findLocalCoverFromSaf(context, uri, baseNames) }.getOrNull()
                "smb", "ftp", "webdav" -> runCatching { findLocalCoverFromNetwork(context, uri, baseNames) }.getOrNull()
                else -> findLocalCoverFromFile(uri, baseNames)
            }
        }

        fun coverBaseNames(game: Game, uri: Uri): List<String> {
            // For SAF "content://" URIs the last segment is a document id such as
            // "primary:roms/kof97.zip", so keep only the actual file name.
            val uriName = runCatching {
                android.net.Uri.decode(uri.lastPathSegment ?: "")
            }.getOrNull().orEmpty().substringAfterLast('/')
            val sanitizedTitle = game.title.replace(Regex("\\(.*\\)"), "").trim()

            return listOf(
                game.fileName.substringBeforeLast('.', game.fileName),
                uriName.substringBeforeLast('.', uriName),
                game.title,
                sanitizedTitle
            ).map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }

        private fun isCoverFile(name: String, baseNames: List<String>): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in COVER_EXTENSION_SET) return false
            val nameWithoutExtension = name.substringBeforeLast('.')
            return baseNames.any { it.equals(nameWithoutExtension, ignoreCase = true) }
        }

        private fun findLocalCoverFromFile(uri: Uri, baseNames: List<String>): File? {
            val path = runCatching { android.net.Uri.decode(uri.path ?: "") }.getOrNull()
                ?: return null
            val romFile = File(path)

            // Look in the ROM folder first, then fall back to the common ROM roots (covers only
            // need to sit in a folder that is easy for the user to fill).
            val directories = linkedSetOf<File>()
            romFile.parentFile?.let { directories.add(it) }
            directories.add(File("/sdcard/roms"))
            directories.add(File("/storage/emulated/0/roms"))

            for (directory in directories) {
                val match = runCatching {
                    directory.listFiles()
                        ?.firstOrNull { it.isFile && isCoverFile(it.name, baseNames) }
                }.getOrNull()
                if (match != null) return match
            }

            // Fall back to the "天马G" media layout: <dir>/media/<game name>/boxFront.jpg
            return PegasusMediaLayout.findLocalCover(romFile, baseNames)
        }

        private fun findLocalCoverFromSaf(
            context: Context,
            uri: Uri,
            baseNames: List<String>
        ): Uri? {
            val authority = uri.authority ?: return null
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: return null
            val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
                .getOrNull() ?: return null
            val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)

            // Walk up to three levels so covers work both when placed next to the ROM and when
            // placed in the ROM root while games live in per-system subfolders.
            var parentDocumentId = runCatching {
                documentId.substringBeforeLast('/', "")
            }.getOrNull().orEmpty()
            if (parentDocumentId.isEmpty()) parentDocumentId = treeDocumentId

            // Walking must not return early: the 天马G media lookup at the end still has to run
            // when the tree root is reached.
            var depth = 0
            while (depth < 3 && parentDocumentId.isNotEmpty()) {
                val match = queryChildren(context, treeUri, parentDocumentId)
                    .firstOrNull { (name, _) -> isCoverFile(name, baseNames) }

                if (match != null) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, match.second)
                }

                if (parentDocumentId == treeDocumentId) break
                parentDocumentId = parentDocumentId.substringBeforeLast('/', "")
                depth++
            }

            // Fall back to the "天马G" media layout: <dir>/media/<game name>/boxFront.jpg
            return PegasusMediaLayout.findSafCover(context, uri, baseNames)
        }

        private fun queryChildren(
            context: Context,
            treeUri: Uri,
            parentDocumentId: String
        ): List<Pair<String, String>> {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentDocumentId
            )
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
            }.onFailure { Timber.e(it, "Failed to query SAF children for $childrenUri") }

            return result
        }

        private val networkCoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val networkCoverInProgress = Collections.synchronizedSet(mutableSetOf<String>())

        private fun findLocalCoverFromNetwork(
            context: Context,
            uri: Uri,
            baseNames: List<String>
        ): File? {
            val config = NetworkStoragePreferences.load(context)
            if (!config.isConfigured) return null

            val remotePath = uri.path ?: return null
            val parentPath = remotePath.substringBeforeLast('/', "")
            if (parentPath.isEmpty()) return null

            val coverCacheDir = File(context.cacheDir, NetworkStoragePreferences.NETWORK_COVERS_CACHE_DIR)
                .apply { mkdirs() }

            // Fast local cache check (safe to run on the main thread).
            val cachedCover = coverCacheDir.listFiles()
                ?.firstOrNull { it.isFile && isCoverFile(it.name, baseNames) }
            if (cachedCover != null) return cachedCover

            // Covers taken from the 天马G media layout are cached under a per-game name, so
            // the generic "same name as the ROM" check above cannot find them.
            val cachePrefix = networkCoverCachePrefix(remotePath)
            val cachedMediaCover = coverCacheDir.listFiles()
                ?.firstOrNull { it.isFile && it.name.startsWith("$cachePrefix.") }
            if (cachedMediaCover != null) return cachedMediaCover

            // Not cached: trigger a background download and return null so the caller
            // falls back to the online cover / placeholder. The next time this view is
            // bound the cached cover will be found synchronously.
            if (networkCoverInProgress.add(remotePath)) {
                networkCoverScope.launch {
                    try {
                        downloadNetworkCover(context, config, parentPath, baseNames, coverCacheDir, cachePrefix)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to download network cover for $remotePath")
                    } finally {
                        networkCoverInProgress.remove(remotePath)
                    }
                }
            }
            return null
        }

        private fun networkCoverCachePrefix(remotePath: String) =
            "net-" + Integer.toHexString(remotePath.hashCode())

        private fun downloadNetworkCover(
            context: Context,
            config: com.swordfish.lemuroid.lib.storage.network.NetworkStorageConfig,
            parentPath: String,
            baseNames: List<String>,
            coverCacheDir: File,
            cachePrefix: String
        ) {
            val client = NetworkClientFactory.create(config)
            try {
                // Same folder as the ROM first, then the 天马G media layout.
                val entries = client.listFiles(parentPath)
                val coverEntry = entries.firstOrNull { !it.isDirectory && isCoverFile(it.name, baseNames) }
                    ?: PegasusMediaLayout.findRemoteCover(client, parentPath, baseNames)
                    ?: return

                val extension = coverEntry.name.substringAfterLast('.', "jpg").lowercase()
                val localCover = File(coverCacheDir, "$cachePrefix.$extension")
                if (!localCover.exists()) {
                    client.downloadFile(coverEntry.path, localCover)
                }
            } finally {
                runCatching { client.close() }
            }
        }

        fun getFallbackDrawable(game: Game) =
            TextDrawable(computeTitle(game), computeColor(game))

        fun getFallbackRemoteUrl(game: Game): String {
            val color = Integer.toHexString(computeColor(game)).substring(2)
            val title = computeTitle(game)
            return "https://fakeimg.pl/512x512/$color/fff/?font=bebas&text=$title"
        }

        private fun computeTitle(game: Game): String {
            val sanitizedName = game.title
                .replace(Regex("\\(.*\\)"), "")

            return sanitizedName.asSequence()
                .filter { it.isDigit() or it.isUpperCase() or (it == '&') }
                .take(3)
                .joinToString("")
                .ifBlank { game.title.first().toString() }
                .capitalize()
        }

        private fun computeColor(game: Game): Int {
            return ColorUtils.randomColor(game.title)
        }
    }
}
