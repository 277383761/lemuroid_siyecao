package com.swordfish.lemuroid.lib.storage.network

object NetworkClientFactory {

    fun create(config: NetworkStorageConfig): NetworkClient = when (config.protocol) {
        NetworkProtocol.SMB -> SmbClient(config)
        NetworkProtocol.FTP -> FtpClient(config)
        NetworkProtocol.WEBDAV -> WebDavClient(config)
    }
}
