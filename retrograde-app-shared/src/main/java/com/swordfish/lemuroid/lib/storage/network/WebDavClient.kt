package com.swordfish.lemuroid.lib.storage.network

import android.util.Xml
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import timber.log.Timber

class WebDavClient(private val config: NetworkStorageConfig) : NetworkClient {

    private val httpScheme: String
        get() = if (config.isHttps) "https" else "http"

    private val baseUrl: String
        get() = "$httpScheme://${config.hostname}:${config.effectivePort}"

    private val client: OkHttpClient
        get() = SHARED_CLIENT

    private fun authHeader(): String? =
        if (config.username.isNotBlank()) {
            Credentials.basic(config.username, config.password)
        } else null

    private fun fullUrl(remotePath: String): String {
        val path = remotePath.trim().trimStart('/')
        return if (path.isEmpty()) "$baseUrl/" else "$baseUrl/$path"
    }

    private fun propfindRequest(url: String, depth: String): Request.Builder {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Depth", depth)
            .header("Content-Type", "application/xml; charset=utf-8")
        authHeader()?.let { requestBuilder.header("Authorization", it) }
        return requestBuilder
    }

    override fun listFiles(remotePath: String): List<NetworkFileEntry> {
        return runCatching {
            val url = fullUrl(remotePath).let { if (it.endsWith("/")) it else "$it/" }
            val request = propfindRequest(url, "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("WebDAV PROPFIND failed for $url: HTTP ${response.code}")
                    return emptyList()
                }
                val body = response.body?.byteStream() ?: return emptyList()
                parsePropfind(body, remotePath)
            }
        }.onFailure { Timber.e(it, "WebDAV listFiles failed for $remotePath") }
            .getOrDefault(emptyList())
    }

    /** Convert an href (absolute URL or absolute path) into a decoded path. */
    private fun hrefToPath(href: String): String {
        val rawPath = if (href.contains("://")) {
            runCatching { URI(href).rawPath }.getOrNull() ?: href
        } else {
            href
        }
        return runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
    }

    private fun parsePropfind(input: InputStream, basePath: String): List<NetworkFileEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val entries = mutableListOf<NetworkFileEntry>()
        val selfPath = "/" + basePath.trim().trim('/')

        var eventType = parser.eventType
        var href: String? = null
        var isCollection = false
        var contentLength = 0L

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when {
                eventType == XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "href" -> href = parser.nextText()
                        "collection" -> isCollection = true
                        "getcontentlength" -> contentLength = runCatching {
                            parser.nextText().toLong()
                        }.getOrDefault(0L)
                    }
                }
                eventType == XmlPullParser.END_TAG && parser.name == "response" -> {
                    val entryPath = href?.let { hrefToPath(it).trimEnd('/') }
                    if (entryPath != null && entryPath != selfPath && entryPath.isNotEmpty()) {
                        val name = entryPath.substringAfterLast('/')
                        val path = (basePath.trimEnd('/') + "/" + name).trimStart('/')
                        if (name.isNotEmpty()) {
                            entries.add(
                                NetworkFileEntry(
                                    name = name,
                                    size = contentLength,
                                    isDirectory = isCollection,
                                    path = path
                                )
                            )
                        }
                    }
                    href = null
                    isCollection = false
                    contentLength = 0L
                }
            }
            eventType = parser.next()
        }
        return entries
    }

    override fun getInputStream(remotePath: String): InputStream? {
        return runCatching {
            val requestBuilder = Request.Builder().url(fullUrl(remotePath)).get()
            authHeader()?.let { requestBuilder.header("Authorization", it) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes()?.inputStream() else null
            }
        }.onFailure { Timber.e(it, "WebDAV getInputStream failed for $remotePath") }.getOrNull()
    }

    override fun downloadFile(remotePath: String, localFile: File) {
        Timber.d("WebDAV downloading $remotePath -> ${localFile.absolutePath}")
        localFile.parentFile?.mkdirs()
        val requestBuilder = Request.Builder().url(fullUrl(remotePath)).get()
        authHeader()?.let { requestBuilder.header("Authorization", it) }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("empty body")
            localFile.outputStream().use { output ->
                body.byteStream().copyTo(output)
            }
        }
    }

    override fun testConnection(): Boolean {
        val url = fullUrl(config.remotePath).let { if (it.endsWith("/")) it else "$it/" }
        val request = propfindRequest(url, "0")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${response.message}")
            }
            return true
        }
    }

    override fun close() {
        // OkHttp connection pool is managed internally.
    }

    private companion object {
        val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:displayname/><d:getcontentlength/><d:resourcetype/>" +
                "</d:prop></d:propfind>"
    }
}
