package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.common.kotlin.extractZipEntryToFile
import com.swordfish.lemuroid.common.kotlin.findZipRomEntryName
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.common.kotlin.writeToFile
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

class StorageAccessFrameworkProvider(private val context: Context) : StorageProvider {

    override val id: String = "access_framework"

    override val name: String = context.getString(R.string.local_storage)

    override val uriSchemes = listOf("content")

    override val prefsFragmentClass: Class<LeanbackPreferenceFragment>? = null

    override val enabledByDefault = true

    override fun listBaseStorageFiles(): Flow<List<BaseStorageFile>> {
        return getExternalFolder()?.let { folder ->
            traverseDirectoryEntries(Uri.parse(folder))
        } ?: emptyFlow()
    }

    override fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile? {
        return DocumentFileParser.parseDocumentFile(context, baseStorageFile)
    }

    private fun getExternalFolder(): String? {
        val prefString = context.getString(R.string.pref_key_extenral_folder)
        val preferenceManager = SharedPreferencesHelper.getLegacySharedPreferences(context)
        return preferenceManager.getString(prefString, null)
    }

    private fun traverseDirectoryEntries(rootUri: Uri): Flow<List<BaseStorageFile>> = flow {
        val directoryDocumentIds = mutableListOf<String>()
        DocumentsContract.getTreeDocumentId(rootUri)?.let { directoryDocumentIds.add(it) }

        while (directoryDocumentIds.isNotEmpty()) {
            val currentDirectoryDocumentId = directoryDocumentIds.removeAt(0)

            val result = runCatching {
                listBaseStorageFiles(rootUri, currentDirectoryDocumentId)
            }
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error while listing files")
            }

            val (files, directories) = result.getOrDefault(
                listOf<BaseStorageFile>() to listOf<String>()
            )

            emit(files)
            directoryDocumentIds.addAll(directories)
        }
    }

    private fun listBaseStorageFiles(
        treeUri: Uri,
        rootDocumentId: String
    ): Pair<List<BaseStorageFile>, List<String>> {
        val resultFiles = mutableListOf<BaseStorageFile>()
        val resultDirectories = mutableListOf<String>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)

        Timber.d("Querying files in directory: $childrenUri")

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use {
            while (it.moveToNext()) {
                val documentId = it.getString(0)
                val documentName = it.getString(1)
                val documentSize = it.getLong(2)
                val mimeType = it.getString(3)

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    resultDirectories.add(documentId)
                } else {
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId
                    )
                    resultFiles.add(
                        BaseStorageFile(
                            name = documentName,
                            size = documentSize,
                            uri = documentUri,
                            path = documentUri.path
                        )
                    )
                }
            }
        }

        return resultFiles to resultDirectories
    }

    override fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean
    ): RomFiles {
        val originalDocumentUri = Uri.parse(game.fileUri)
        val originalDocument = DocumentFile.fromSingleUri(context, originalDocumentUri)!!

        val extractableExtensions = getExtractableRomExtensions(game)
        val needsExtraction = originalDocument.isZipped() && extractableExtensions.isNotEmpty()

        return when {
            needsExtraction && dataFiles.isEmpty() ->
                getGameRomFilesZipped(game, originalDocument, extractableExtensions)
            allowVirtualFiles -> getGameRomFilesVirtual(game, dataFiles)
            else -> getGameRomFilesStandard(game, dataFiles, originalDocument)
        }
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

    private fun getGameRomFilesStandard(
        game: Game,
        dataFiles: List<DataFile>,
        originalDocument: DocumentFile
    ): RomFiles {
        val gameEntry = getGameRomStandard(game, originalDocument)
        val dataEntries = dataFiles.map { getDataFileStandard(game, it) }
        return RomFiles.Standard(listOf(gameEntry) + dataEntries)
    }

    private fun getGameRomFilesZipped(
        game: Game,
        originalDocument: DocumentFile,
        supportedExtensions: Set<String>
    ): RomFiles {
        val cacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return RomFiles.Standard(listOf(cacheFile))
        }

        // Content URIs are not seekable, so buffer the whole archive into a temp file first.
        // This also lets us use the crash-safe ZipArchive reader (Android 5/6 ZipInputStream
        // throws UTFDataFormatException on non-ASCII entry names).
        val tmpArchive = File.createTempFile("saf_zip_", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(originalDocument.uri)?.use { input ->
                tmpArchive.outputStream().use { output -> input.copyTo(output) }
            } ?: throw java.io.FileNotFoundException("Cannot open ${originalDocument.name}")

            val entryName = tmpArchive.findZipRomEntryName(supportedExtensions)
                ?: throw java.io.FileNotFoundException("No supported ROM entry in ${originalDocument.name}")

            if (!tmpArchive.extractZipEntryToFile(entryName, cacheFile) || cacheFile.length() == 0L) {
                cacheFile.delete()
                throw java.io.IOException("Failed to extract entry from ${originalDocument.name}")
            }
        } finally {
            tmpArchive.delete()
        }
        return RomFiles.Standard(listOf(cacheFile))
    }

    private fun getGameRomFilesVirtual(game: Game, dataFiles: List<DataFile>): RomFiles {
        val gameEntry = getGameRomVirtual(game)
        val dataEntries = dataFiles.map { getDataFileVirtual(it) }
        return RomFiles.Virtual(listOf(gameEntry) + dataEntries)
    }

    private fun getDataFileVirtual(dataFile: DataFile): RomFiles.Virtual.Entry {
        return RomFiles.Virtual.Entry(
            "$VIRTUAL_FILE_PATH/${dataFile.fileName}",
            context.contentResolver.openFileDescriptor(Uri.parse(dataFile.fileUri), "r")!!
        )
    }

    private fun getDataFileStandard(game: Game, dataFile: DataFile): File {
        val cacheFile = GameCacheUtils.getDataFileForGame(
            SAF_CACHE_SUBFOLDER,
            context,
            game,
            dataFile
        )

        if (cacheFile.exists()) {
            return cacheFile
        }

        val stream = context.contentResolver.openInputStream(Uri.parse(dataFile.fileUri))!!
        stream.writeToFile(cacheFile)
        return cacheFile
    }

    private fun getGameRomVirtual(game: Game): RomFiles.Virtual.Entry {
        return RomFiles.Virtual.Entry(
            "$VIRTUAL_FILE_PATH/${game.fileName}",
            context.contentResolver.openFileDescriptor(Uri.parse(game.fileUri), "r")!!
        )
    }

    private fun getGameRomStandard(game: Game, originalDocument: DocumentFile): File {
        val cacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)

        if (cacheFile.exists()) {
            return cacheFile
        }

        val stream = context.contentResolver.openInputStream(originalDocument.uri)!!
        stream.writeToFile(cacheFile)
        return cacheFile
    }

    override fun getInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    companion object {
        const val SAF_CACHE_SUBFOLDER = "storage-framework-games"
        const val VIRTUAL_FILE_PATH = "/virtual/file/path"
    }
}
