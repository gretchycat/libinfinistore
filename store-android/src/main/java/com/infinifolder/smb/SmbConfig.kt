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
) {
    val rootPath get() = rootPath(initialShare, initialPath)
    companion object {
        fun rootPath(share: String?, path: String?): String {
            val parts = listOfNotNull(share?.trim('/')?.takeIf { it.isNotBlank() }, path?.trim('/')?.takeIf { it.isNotBlank() })
            return "/" + parts.joinToString("/")
        }
    }
}

data class SmbCredentials(
    val password: String? = null
)
