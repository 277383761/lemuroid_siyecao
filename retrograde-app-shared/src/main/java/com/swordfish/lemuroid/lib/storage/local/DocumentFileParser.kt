package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import com.swordfish.lemuroid.common.kotlin.ZipArchive
import com.swordfish.lemuroid.common.kotlin.calculateCrc32
import com.swordfish.lemuroid.common.kotlin.toStringCRC32
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.scanner.SerialScanner
import java.io.File
import timber.log.Timber

object DocumentFileParser {

    private const val SINGLE_ARCHIVE_THRESHOLD = 0.9
    private const val MAX_SIZE_CRC32 = 1_000_000_000

    fun parseDocumentFile(context: Context, baseStorageFile: BaseStorageFile): StorageFile {
        return if (baseStorageFile.extension == "zip") {
            Timber.d("Detected zip file. ${baseStorageFile.name}")
            parseZipFile(context, baseStorageFile)
        } else {
            Timber.d("Detected standard file. ${baseStorageFile.name}")
            parseStandardFile(context, baseStorageFile)
        }
    }

    private fun parseZipFile(context: Context, baseStorageFile: BaseStorageFile): StorageFile {
        // Buffer the archive to a seekable temp file so ZipArchive can read its central
        // directory. This also sidesteps Android 5/6's ZipInputStream crash
        // (UTFDataFormatException) on non-ASCII entry names.
        val tmpArchive = runCatching {
            File.createTempFile("doc_zip_", ".zip", context.cacheDir).also { tmp ->
                context.contentResolver.openInputStream(baseStorageFile.uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }.getOrNull()

        val gameEntry = tmpArchive?.let { findGameEntry(it, baseStorageFile.size) }
        if (tmpArchive != null && gameEntry != null) {
            val extracted = File.createTempFile("doc_entry_", ".rom", context.cacheDir)
            try {
                if (ZipArchive.extractEntry(tmpArchive, gameEntry, extracted)) {
                    val diskInfo = extracted.inputStream().use {
                        SerialScanner.extractInfo(gameEntry.name, it)
                    }
                    Timber.d("Handing zip file as compressed game: ${baseStorageFile.name}")
                    return StorageFile(
                        gameEntry.name,
                        gameEntry.size,
                        gameEntry.crc.toStringCRC32(),
                        diskInfo.serial,
                        baseStorageFile.uri,
                        baseStorageFile.uri.path,
                        diskInfo.systemID
                    )
                }
            } finally {
                extracted.delete()
                tmpArchive.delete()
            }
        } else {
            tmpArchive?.delete()
        }

        Timber.d("Handing zip file as standard: ${baseStorageFile.name}")
        return parseStandardFile(context, baseStorageFile)
    }

    private fun parseStandardFile(context: Context, baseStorageFile: BaseStorageFile): StorageFile {
        val diskInfo = context.contentResolver.openInputStream(baseStorageFile.uri)
            ?.let { inputStream -> SerialScanner.extractInfo(baseStorageFile.name, inputStream) }

        val crc32 = if (baseStorageFile.size < MAX_SIZE_CRC32 && diskInfo?.serial == null) {
            context.contentResolver.openInputStream(baseStorageFile.uri)?.calculateCrc32()
        } else {
            null
        }

        Timber.d("Parsed standard file: $baseStorageFile")

        return StorageFile(
            baseStorageFile.name,
            baseStorageFile.size,
            crc32,
            diskInfo?.serial,
            baseStorageFile.uri,
            baseStorageFile.uri.path,
            diskInfo?.systemID
        )
    }

    /* Finds a zip entry which we assume is a game. Lemuroid only supports single archive games,
       so we look for an entry which occupies a large percentage of the archive space. */
    private fun findGameEntry(archive: File, fileSize: Long): ZipArchive.Entry? {
        val entries = ZipArchive.listEntries(archive) ?: return null
        if (fileSize <= 0) return null
        return entries.firstOrNull {
            !it.isDirectory && it.compressedSize > 0 &&
                (it.compressedSize.toFloat() / fileSize.toFloat()) > SINGLE_ARCHIVE_THRESHOLD
        }
    }
}
