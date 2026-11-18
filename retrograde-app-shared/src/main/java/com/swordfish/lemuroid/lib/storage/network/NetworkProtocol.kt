package com.swordfish.lemuroid.lib.storage.network

enum class NetworkProtocol(val scheme: String, val defaultPort: Int) {
    SMB("smb", 445),
    FTP("ftp", 21),
    WEBDAV("webdav", 80);

    companion object {
        fun fromScheme(scheme: String?): NetworkProtocol? =
            values().firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }

        fun fromName(name: String?): NetworkProtocol? =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
