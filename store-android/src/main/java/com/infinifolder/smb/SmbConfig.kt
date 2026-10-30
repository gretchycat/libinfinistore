package com.infinifolder.smb

data class SmbConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val domain: String = "WORKGROUP",
    val username: String = "",
    val isGuest: Boolean = false,
    val initialShare: String? = null,
    val initialPath: String = "/"
)

data class SmbCredentials(
    val password: String? = null
)
