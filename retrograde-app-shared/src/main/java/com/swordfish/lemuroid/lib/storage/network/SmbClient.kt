package com.swordfish.lemuroid.lib.storage.network

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.io.File
import java.io.InputStream
import java.util.Properties
import timber.log.Timber

class SmbClient(private val config: NetworkStorageConfig) : NetworkClient {

    private val cifsContext: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "30000")
            setProperty("jcifs.resolveOrder", "DNS")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val auth = NtlmPasswordAuthenticator(null, config.username, config.password)
        base.withCredentials(auth)
    }

    private fun buildUrl(remotePath: String): String {
        val path = remotePath.trimStart('/')
        val portPart = if (config.effectivePort != config.protocol.defaultPort) ":${config.effectivePort}" else ""
        // Credentials are provided via the authenticator, not embedded in the URL.
        return "smb://${config.hostname}$portPart/$path"
    }

    override fun listFiles(remotePath: String): List<NetworkFileEntry> {
        val url = buildUrl(remotePath)
        Timber.d("SMB listing: $url")
        val dir = SmbFile(url, cifsContext)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()
            .filterNot { it.name.startsWith(".") }
            .map { child ->
                NetworkFileEntry(
                    name = child.name.trimEnd('/'),
                    size = runCatching { child.length() }.getOrDefault(0L),
                    isDirectory = child.isDirectory,
                    path = (remotePath.trimEnd('/') + "/" + child.name.trimEnd('/')).trimStart('/')
                )
            }
    }

    override fun getInputStream(remotePath: String): InputStream? {
        return runCatching {
            val url = buildUrl(remotePath)
            val file = SmbFile(url, cifsContext)
            SmbFileInputStream(file)
        }.onFailure { Timber.e(it, "SMB getInputStream failed for $remotePath") }.getOrNull()
    }

    override fun downloadFile(remotePath: String, localFile: File) {
        val url = buildUrl(remotePath)
        Timber.d("SMB downloading $url -> ${localFile.absolutePath}")
        localFile.parentFile?.mkdirs()
        SmbFile(url, cifsContext).use { remote ->
            SmbFileInputStream(remote).use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    override fun testConnection(): Boolean {
        val url = buildUrl(config.remotePath)
        val dir = SmbFile(url, cifsContext)
        if (!dir.exists()) {
            throw IllegalStateException("Path not found: $url")
        }
        return true
    }

    override fun close() {
        // jcifs-ng context is reusable; nothing to close explicitly.
    }
}
