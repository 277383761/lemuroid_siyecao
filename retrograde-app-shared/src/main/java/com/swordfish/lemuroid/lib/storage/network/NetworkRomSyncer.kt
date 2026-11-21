package com.swordfish.lemuroid.lib.storage.network

import android.content.Context
import android.os.StatFs
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

/** Mirrors the selected parts of a network share into one dedicated local directory. */
class NetworkRomSyncer(private val context: Context) {

    data class Progress(
        val stage: Stage,
        val currentFile: String = "",
        val processedFiles: Int = 0,
        val totalFiles: Int = 0,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L
    )

    enum class Stage {
        SCANNING,
        DOWNLOADING,
        CLEANING,
        INDEXING
    }

    data class Result(
        val downloadedFiles: Int,
        val skippedFiles: Int,
        val deletedFiles: Int
    )

    private data class RemoteFile(val path: String, val relativePath: String, val size: Long)

    suspend fun sync(
        selectedSystemIds: Set<String>,
        onProgress: (Progress) -> Unit
    ): Result {
        val config = NetworkStoragePreferences.load(context)
        check(config.isConfigured) { "请先启用网络存储并填写服务器地址" }
        check(selectedSystemIds.isNotEmpty()) { "请至少选择一个要同步的主机系统" }

        val client = NetworkClientFactory.create(config)
        try {
            onProgress(Progress(Stage.SCANNING))
            val connected = runInterruptible { client.testConnection() }
            check(connected) { "无法连接网络目录，已保留原有本地镜像" }
            val remoteFiles = listRemoteFiles(client, config.remotePath)
            val selectedFiles = filterSelectedSystems(remoteFiles, selectedSystemIds)
            val mirrorRoot = getMirrorDirectory()
            if (!mirrorRoot.isDirectory && !mirrorRoot.mkdirs()) {
                throw IOException("无法创建本地镜像目录，请检查存储权限：${mirrorRoot.absolutePath}")
            }

            val expectedFiles = LinkedHashMap<String, RemoteFile>()
            selectedFiles.forEach { remoteFile ->
                // Validate every local path before any download or deletion takes place.
                safeLocalFile(mirrorRoot, remoteFile.relativePath)
                if (!expectedFiles.containsKey(remoteFile.relativePath)) {
                    expectedFiles[remoteFile.relativePath] = remoteFile
                }
            }

            val filesToDownload = expectedFiles.values.filter { remoteFile ->
                val localFile = safeLocalFile(mirrorRoot, remoteFile.relativePath)
                !localFile.isFile || localFile.length() != remoteFile.size
            }
            val requiredBytes = filesToDownload.fold(0L) { total, file ->
                if (Long.MAX_VALUE - total < file.size.coerceAtLeast(0L)) Long.MAX_VALUE
                else total + file.size.coerceAtLeast(0L)
            }
            val availableBytes = StatFs(mirrorRoot.absolutePath).availableBytes
            if (requiredBytes > availableBytes) {
                throw IOException(
                    "本地空间不足：本次需要下载 ${formatBytes(requiredBytes)}，" +
                        "可用 ${formatBytes(availableBytes)}"
                )
            }

            var downloadedFiles = 0
            var skippedFiles = 0
            var processedFiles = 0
            var downloadedBytes = 0L
            for (remoteFile in expectedFiles.values) {
                coroutineContext.ensureActive()
                val localFile = safeLocalFile(mirrorRoot, remoteFile.relativePath)
                if (localFile.isFile && localFile.length() == remoteFile.size) {
                    skippedFiles++
                } else {
                    onProgress(
                        Progress(
                            Stage.DOWNLOADING,
                            currentFile = remoteFile.relativePath,
                            processedFiles = processedFiles,
                            totalFiles = expectedFiles.size,
                            downloadedBytes = downloadedBytes,
                            totalBytes = requiredBytes
                        )
                    )
                    downloadAtomic(client, remoteFile, localFile)
                    downloadedFiles++
                    downloadedBytes += remoteFile.size.coerceAtLeast(0L)
                }
                processedFiles++
                onProgress(
                    Progress(
                        Stage.DOWNLOADING,
                        currentFile = remoteFile.relativePath,
                        processedFiles = processedFiles,
                        totalFiles = expectedFiles.size,
                        downloadedBytes = downloadedBytes,
                        totalBytes = requiredBytes
                    )
                )
            }

            coroutineContext.ensureActive()
            onProgress(Progress(Stage.CLEANING, totalFiles = expectedFiles.size))
            val deletedFiles = cleanMirror(mirrorRoot, expectedFiles.keys)
            return Result(downloadedFiles, skippedFiles, deletedFiles)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun listRemoteFiles(client: NetworkClient, rootPath: String): List<RemoteFile> {
        val result = mutableListOf<RemoteFile>()
        val directories = mutableListOf(rootPath)
        val root = rootPath.replace('\\', '/').trim('/')

        while (directories.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directory = directories.removeAt(0)
            // A failed listing must abort the sync. Treating it as an empty directory could
            // otherwise delete valid local files during the mirror cleanup phase.
            val entries = runInterruptible { client.listFiles(directory) }
            entries.forEach { entry ->
                if (entry.isDirectory) {
                    directories.add(entry.path)
                } else {
                    val relativePath = relativePath(root, entry.path)
                    if (relativePath.isNotEmpty()) {
                        result.add(RemoteFile(entry.path, relativePath, entry.size.coerceAtLeast(0L)))
                    }
                }
            }
        }
        return result
    }

    private fun relativePath(root: String, remotePath: String): String {
        val fullPath = remotePath.replace('\\', '/').trim('/')
        val relative = when {
            root.isEmpty() -> fullPath
            fullPath == root -> ""
            fullPath.startsWith("$root/") -> fullPath.substring(root.length + 1)
            else -> fullPath
        }
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "网络文件路径不安全：$remotePath" }
        return segments.joinToString("/")
    }

    private fun filterSelectedSystems(
        remoteFiles: List<RemoteFile>,
        selectedSystemIds: Set<String>
    ): List<RemoteFile> {
        if (selectedSystemIds.containsAll(GameSystem.all().map { it.id.dbname })) return remoteFiles

        val directSystems = remoteFiles.associate { it.relativePath to directSystem(it.relativePath) }
        val directorySystems = remoteFiles
            .groupBy { parentPath(it.relativePath) }
            .mapNotNull { (directory, files) ->
                val systems = files.mapNotNull { directSystems[it.relativePath] }.distinct()
                if (systems.size == 1) directory to systems.single() else null
            }
            .toMap()

        return remoteFiles.filter { file ->
            val direct = directSystems[file.relativePath]
            if (direct != null) return@filter direct.dbname in selectedSystemIds

            var parent = parentPath(file.relativePath)
            while (parent.isNotEmpty()) {
                val inherited = directorySystems[parent]
                if (inherited != null) return@filter inherited.dbname in selectedSystemIds
                parent = parentPath(parent)
            }

            val extension = file.relativePath.substringAfterLast('.', "").lowercase(Locale.US)
            val candidates = GameSystem.all().filter { system ->
                system.supportedExtensions.any { it.equals(extension, ignoreCase = true) }
            }
            // Unknown files are treated as shared resources (BIOS, metadata or media). Files
            // below a recognized system directory have already been classified above.
            candidates.isEmpty() || candidates.any { it.id.dbname in selectedSystemIds }
        }
    }

    private fun directSystem(relativePath: String): SystemID? {
        val directories = parentPath(relativePath).split('/').filter { it.isNotEmpty() }
        for (index in directories.indices.reversed()) {
            SystemID.values().firstOrNull { it.matchesFolderName(directories[index]) }?.let {
                return it
            }
        }
        val extension = relativePath.substringAfterLast('.', "")
        if (extension.isEmpty()) return null
        return GameSystem.findByUniqueFileExtension(extension)?.id
    }

    private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

    private fun getMirrorDirectory(): File {
        val preferenceKey = context.getString(R.string.pref_key_legacy_external_folder)
        val configured = SharedPreferencesHelper.getLegacySharedPreferences(context)
            .getString(preferenceKey, null)
        val romsRoot = configured?.takeIf { it.isNotBlank() }?.let(::File) ?: File("/sdcard/roms")
        return File(romsRoot, MIRROR_DIRECTORY_NAME)
    }

    private fun safeLocalFile(root: File, relativePath: String): File {
        val file = File(root, relativePath)
        val rootPath = root.canonicalPath + File.separator
        check(file.canonicalPath.startsWith(rootPath)) { "本地文件路径不安全：$relativePath" }
        return file
    }

    private suspend fun downloadAtomic(client: NetworkClient, remoteFile: RemoteFile, target: File) {
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) {
            throw IOException("无法创建目录：${it.absolutePath}")
        } }
        if (target.isDirectory && !target.deleteRecursively()) {
            throw IOException("无法替换本地目录：${target.absolutePath}")
        }
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.delete()
        try {
            runInterruptible { client.downloadFile(remoteFile.path, temporary) }
            coroutineContext.ensureActive()
            if (temporary.length() != remoteFile.size) {
                throw IOException(
                    "文件下载不完整：${remoteFile.relativePath} " +
                        "(${temporary.length()}/${remoteFile.size} 字节)"
                )
            }
            if (target.exists() && !target.delete()) {
                throw IOException("无法覆盖本地文件：${target.absolutePath}")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun cleanMirror(root: File, expectedPaths: Set<String>): Int {
        var deletedFiles = 0
        root.walkBottomUp().forEach { file ->
            if (file == root) return@forEach
            if (file.isDirectory) {
                file.delete()
            } else {
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                if (relativePath !in expectedPaths) {
                    if (!file.delete()) {
                        throw IOException("无法删除镜像中的多余文件：${file.absolutePath}")
                    }
                    deletedFiles++
                }
            }
        }
        return deletedFiles
    }

    companion object {
        const val MIRROR_DIRECTORY_NAME = "网络镜像"

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = -1
            while (value >= 1024.0 && unit < units.lastIndex) {
                value /= 1024.0
                unit++
            }
            return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
        }
    }
}
