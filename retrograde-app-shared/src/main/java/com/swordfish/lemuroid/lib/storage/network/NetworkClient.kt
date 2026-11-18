package com.swordfish.lemuroid.lib.storage.network

import java.io.Closeable
import java.io.File
import java.io.InputStream

interface NetworkClient : Closeable {

    /** List files and directories directly under [remotePath]. */
    fun listFiles(remotePath: String): List<NetworkFileEntry>

    /** Open an input stream for the remote file at [remotePath]. */
    fun getInputStream(remotePath: String): InputStream?

    /** Download the remote file at [remotePath] to [localFile]. */
    fun downloadFile(remotePath: String, localFile: File)

    /** Test whether the connection and authentication work. */
    fun testConnection(): Boolean
}
