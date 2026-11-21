package com.swordfish.lemuroid.lib.storage.network

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.InputStream
import timber.log.Timber

class FtpClient(private val config: NetworkStorageConfig) : NetworkClient {

    private val client: FTPClient by lazy {
        FTPClient().apply {
            connectTimeout = 15000
            defaultTimeout = 30000
            setDataTimeout(60000)
        }
    }

    private fun ensureConnected() {
        if (!client.isConnected) {
            client.connect(config.hostname, config.effectivePort)
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                client.disconnect()
                throw IllegalStateException("FTP connect failed: $reply")
            }
            val login = if (config.username.isBlank()) {
                client.login("anonymous", "anonymous")
            } else {
                client.login(config.username, config.password)
            }
            if (!login) throw IllegalStateException("FTP login failed")
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
        }
    }

    private fun normalizePath(remotePath: String): String {
        val path = remotePath.trim()
        return if (path.startsWith("/")) path else "/$path"
    }

    override fun listFiles(remotePath: String): List<NetworkFileEntry> {
        ensureConnected()
        val dir = normalizePath(remotePath)
        val files: Array<FTPFile> = client.listFiles(dir)
            ?: throw IllegalStateException("FTP listing failed: ${client.replyString}")
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            throw IllegalStateException("FTP listing failed: ${client.replyString}")
        }
        return files
            .filterNot { it.name.startsWith(".") }
            .map { file ->
                NetworkFileEntry(
                    name = file.name,
                    size = file.size,
                    isDirectory = file.isDirectory,
                    path = (dir.trimEnd('/') + "/" + file.name).trimStart('/')
                )
            }
    }

    override fun getInputStream(remotePath: String): InputStream? {
        return runCatching {
            ensureConnected()
            client.retrieveFileStream(normalizePath(remotePath))
        }.onFailure { Timber.e(it, "FTP getInputStream failed for $remotePath") }.getOrNull()
    }

    override fun downloadFile(remotePath: String, localFile: File) {
        ensureConnected()
        Timber.d("FTP downloading $remotePath -> ${localFile.absolutePath}")
        localFile.parentFile?.mkdirs()
        val success = localFile.outputStream().use { output ->
            client.retrieveFile(normalizePath(remotePath), output)
        }
        // FTPClient.retrieveFile handles completePendingCommand() internally.
        if (!success) {
            localFile.delete()
            throw IllegalStateException("FTP download failed: ${client.replyString}")
        }
    }

    override fun testConnection(): Boolean {
        ensureConnected()
        val path = normalizePath(config.remotePath)
        if (!client.changeWorkingDirectory(path)) {
            throw IllegalStateException("Cannot open $path: ${client.replyString}")
        }
        return true
    }

    override fun close() {
        runCatching {
            if (client.isConnected) {
                client.logout()
                client.disconnect()
            }
        }
    }
}
