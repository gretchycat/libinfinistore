package com.infinifolder.ftp

import android.net.Uri

object FtpUri {

    const val SCHEME_FTP = "ftp"
    const val SCHEME_FTPS = "ftps"

    var uriFactory: (String) -> Uri = { str ->
        try {
            Uri.parse(str) ?: Uri.Builder().scheme(SCHEME_FTP).path(str).build()
        } catch (_: Throwable) {
            Uri.Builder().scheme(SCHEME_FTP).path(str).build()
        }
    }

    fun build(connectionId: String, remotePath: String): Uri {
        val cleanPath = normalizeRemotePath(remotePath)
        val pathPart = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        return uriFactory("$SCHEME_FTP://$connectionId$pathPart")
    }

    fun isFtpUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == SCHEME_FTP || scheme == SCHEME_FTPS
    }

    fun getConnectionId(uri: Uri): String = try {
        uri.authority ?: uri.host ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun getRemotePath(uri: Uri): String = try {
        val path = uri.path ?: "/"
        normalizeRemotePath(path)
    } catch (_: Throwable) {
        "/"
    }

    fun normalizeRemotePath(path: String): String {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized == "/") return "/"
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        val resolved = mutableListOf<String>()
        for (seg in segments) {
            if (seg == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            } else {
                resolved.add(seg)
            }
        }
        return "/" + resolved.joinToString("/")
    }

    fun getParentUri(uri: Uri): Uri? {
        val connId = getConnectionId(uri)
        val currentPath = getRemotePath(uri)
        if (currentPath == "/" || currentPath.isEmpty()) return null
        val parentPath = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
        return build(connId, parentPath)
    }
}
