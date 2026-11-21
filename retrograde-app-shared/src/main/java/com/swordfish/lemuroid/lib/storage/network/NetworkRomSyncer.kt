package com.swordfish.lemuroid.lib.storage.network

import android.content.Context
import android.os.StatFs
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

/**
 * Mirrors the whole network share into the local ROM directory.
 *
 * Files downloaded by a previous sync are kept in step with the source and removed when they
 * disappear from it. Files the user placed locally are never overwritten or deleted.
 */
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
        INDEXING
    }

    data class Result(
        val downloadedFiles: Int,
        val skippedFiles: Int,
        val deletedFiles: Int
    )

    private data class RemoteFile(val path: String, val relativePath: String, val size: Long)

    suspend fun sync(
        disabledSystemIds: Set<String>,
        onProgress: (Progress) -> Unit
    ): Result {
        val config = NetworkStoragePreferences.load(context)
        check(config.isConfigured) { "请先启用网络存储并填写服务器地址" }

        val client = NetworkClientFactory.create(config)
        try {
            onProgress(Progress(Stage.SCANNING))
            val connected = runInterruptible { client.testConnection() }
            check(connected) { "无法连接网络目录，已保留原有本地 ROM" }
            val remoteFiles = listRemoteFiles(client, config.remotePath)
            val systems = resolveSystems(remoteFiles)
            val selectedFiles = if (disabledSystemIds.isEmpty()) {
                remoteFiles
            } else {
                remoteFiles.filter { file ->
                    val system = systems[file.relativePath]
                    system == null || system.dbname !in disabledSystemIds
                }
            }
            val romsRoot = getRomsDirectory()
            if (!romsRoot.isDirectory && !romsRoot.mkdirs()) {
                throw IOException("无法创建本地 ROM 目录，请检查存储权限：${romsRoot.absolutePath}")
            }

            val expectedFiles = LinkedHashMap<String, RemoteFile>()
            selectedFiles.forEach { remoteFile ->
                // Validate every local path before any download takes place.
                safeLocalFile(romsRoot, remoteFile.relativePath)
                if (!expectedFiles.containsKey(remoteFile.relativePath)) {
                    expectedFiles[remoteFile.relativePath] = remoteFile
                }
            }

            val manifestKey = sourceKey(config)
            val managedFiles = loadManifest(manifestKey)
            val filesToDownload = expectedFiles.values.filter { remoteFile ->
                val localFile = safeLocalFile(romsRoot, remoteFile.relativePath)
                if (!localFile.isFile) {
                    true
                } else {
                    // Only files downloaded by an earlier sync may be refreshed.
                    remoteFile.relativePath in managedFiles && localFile.length() != remoteFile.size
                }
            }
            val requiredBytes = filesToDownload.fold(0L) { total, file ->
                if (Long.MAX_VALUE - total < file.size.coerceAtLeast(0L)) Long.MAX_VALUE
                else total + file.size.coerceAtLeast(0L)
            }
            val availableBytes = StatFs(romsRoot.absolutePath).availableBytes
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
                val localFile = safeLocalFile(romsRoot, remoteFile.relativePath)
                val upToDate = localFile.isFile &&
                    (remoteFile.relativePath !in managedFiles ||
                        localFile.length() == remoteFile.size)
                if (upToDate) {
                    // Keep whatever already exists locally, including the user's own ROMs.
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
                    managedFiles.add(remoteFile.relativePath)
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
            val deletedFiles = deleteStaleFiles(
                romsRoot,
                expectedFiles.keys,
                managedFiles,
                disabledSystemIds
            )
            // Remove the mirror directory created by older versions to avoid duplicate entries.
            val legacyMirror = File(romsRoot, LEGACY_MIRROR_DIRECTORY)
            if (legacyMirror.isDirectory) {
                runCatching { legacyMirror.deleteRecursively() }
            }
            saveManifest(manifestKey, managedFiles)
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
            // A failed listing must abort the sync instead of silently reporting success.
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

    private fun sourceKey(config: NetworkStorageConfig): String =
        "${config.protocol.name}|${config.authority}|${config.remotePath}"

    private fun manifestFile(): File = File(context.filesDir, MANIFEST_FILE_NAME)

    private fun loadManifest(sourceKey: String): MutableSet<String> {
        val file = manifestFile()
        if (!file.isFile) return mutableSetOf()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.firstOrNull() != MANIFEST_HEADER + sourceKey) return mutableSetOf()
        return lines.drop(1).filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun saveManifest(sourceKey: String, paths: Set<String>) {
        runCatching {
            val file = manifestFile()
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    append(MANIFEST_HEADER).append(sourceKey).append('\n')
                    paths.sorted().forEach { append(it).append('\n') }
                }
            )
        }
    }

    /** Removes synced copies that no longer exist in the source, leaving the user's own files. */
    private fun deleteStaleFiles(
        root: File,
        expectedPaths: Set<String>,
        managedFiles: MutableSet<String>,
        disabledSystemIds: Set<String>
    ): Int {
        var deletedFiles = 0
        val stale = managedFiles.filterNot { it in expectedPaths }
        for (relativePath in stale) {
            // Disabled systems are skipped, not wiped: keep whatever was synced before.
            val system = directSystem(relativePath)?.dbname
            if (system != null && system in disabledSystemIds) continue
            val file = runCatching { safeLocalFile(root, relativePath) }.getOrNull()
            if (file == null) {
                managedFiles.remove(relativePath)
            } else if (!file.isFile) {
                managedFiles.remove(relativePath)
            } else if (file.delete()) {
                deletedFiles++
                managedFiles.remove(relativePath)
            }
        }
        return deletedFiles
    }

    /** Maps every remote file to the system it belongs to, when it can be identified. */
    private fun resolveSystems(files: List<RemoteFile>): Map<String, SystemID> {
        val direct = files.associate { it.relativePath to directSystem(it.relativePath) }
        val directorySystems = files
            .groupBy { parentPath(it.relativePath) }
            .mapNotNull { (directory, directoryFiles) ->
                val systems = directoryFiles.mapNotNull { direct[it.relativePath] }.distinct()
                if (systems.size == 1) directory to systems.single() else null
            }
            .toMap()

        return files.mapNotNull { file ->
            val system = direct[file.relativePath] ?: run {
                var parent = parentPath(file.relativePath)
                var inherited: SystemID? = null
                while (parent.isNotEmpty() && inherited == null) {
                    inherited = directorySystems[parent]
                    parent = parentPath(parent)
                }
                inherited
            }
            system?.let { file.relativePath to it }
        }.toMap()
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

    private fun getRomsDirectory(): File = NetworkStoragePreferences.romsDirectory(context)

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

    companion object {
        private const val MANIFEST_FILE_NAME = "network_sync_manifest.txt"
        private const val MANIFEST_HEADER = "v1|"
        private const val LEGACY_MIRROR_DIRECTORY = "网络镜像"

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
