package com.infinifolder.sftp

import android.net.Uri

object SftpUri {

    const val SCHEME_SFTP = "sftp"
    const val SCHEME_SSHFS = "sshfs"

    var uriFactory: (String) -> Uri = { str ->
        try {
            Uri.parse(str) ?: Uri.Builder().scheme(SCHEME_SFTP).path(str).build()
        } catch (_: Throwable) {
            Uri.Builder().scheme(SCHEME_SFTP).path(str).build()
        }
    }

    fun build(connectionId: String, remotePath: String): Uri {
        val cleanPath = normalizeRemotePath(remotePath)
        val pathPart = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        return uriFactory("$SCHEME_SFTP://$connectionId$pathPart")
    }

    fun isSftpUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == SCHEME_SFTP || scheme == SCHEME_SSHFS
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
