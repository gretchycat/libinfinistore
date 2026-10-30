package com.infinifolder.ftp

data class FtpConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 21,
    val username: String = "anonymous",
    val isAnonymous: Boolean = false,
    val initialPath: String = "/",
    val useFtps: Boolean = false,
    val passiveMode: Boolean = true,
    val encoding: String = "UTF-8"
)

data class FtpCredentials(
    val password: String? = null
)
