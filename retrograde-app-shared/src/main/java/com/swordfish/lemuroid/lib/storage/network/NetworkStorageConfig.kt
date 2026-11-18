package com.swordfish.lemuroid.lib.storage.network

data class NetworkStorageConfig(
    val enabled: Boolean = false,
    val protocol: NetworkProtocol = NetworkProtocol.SMB,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val remotePath: String = ""
) {
    /** Host with any pasted scheme, path or trailing slash stripped.
     *  e.g. "http://nas.local:5244/dav/" -> "nas.local:5244" */
    private val normalizedHostField: String
        get() {
            var value = host.trim()
            val schemeIndex = value.indexOf("://")
            if (schemeIndex >= 0) value = value.substring(schemeIndex + 3)
            value = value.trimEnd('/').substringBefore('/')
            return value
        }

    /** Port embedded in the host field, or 0 when there is none. */
    private val embeddedPort: Int
        get() {
            val value = normalizedHostField
            if (value.startsWith("[")) return 0 // IPv6 literal, not supported here
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || value.indexOf(':') != colon) return 0
            return value.substring(colon + 1).toIntOrNull() ?: 0
        }

    /** Bare host name without scheme, port or path. */
    val hostname: String
        get() {
            val value = normalizedHostField
            return if (embeddedPort > 0) value.substringBeforeLast(':') else value
        }

    val effectivePort: Int
        get() = when {
            port > 0 -> port
            embeddedPort > 0 -> embeddedPort
            else -> protocol.defaultPort
        }

    val isHttps: Boolean
        get() {
            val lower = host.trim().lowercase()
            return lower.startsWith("https://") ||
                lower.startsWith("webdavs://") ||
                effectivePort == 443
        }

    /** "host" or "host:port" depending on whether the port is the protocol default. */
    val authority: String
        get() = if (effectivePort == protocol.defaultPort) hostname else "$hostname:$effectivePort"

    val isConfigured: Boolean
        get() = enabled && hostname.isNotBlank()
}
