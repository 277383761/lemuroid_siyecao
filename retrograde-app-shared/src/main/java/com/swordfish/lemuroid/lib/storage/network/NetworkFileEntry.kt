package com.swordfish.lemuroid.lib.storage.network

data class NetworkFileEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val path: String
)
