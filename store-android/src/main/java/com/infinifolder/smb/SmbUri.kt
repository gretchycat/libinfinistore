package com.infinifolder.smb

import android.net.Uri

object SmbUri {

    const val SCHEME_SMB = "smb"

    var uriFactory: (String) -> Uri = { str ->
        try {
            Uri.parse(str) ?: Uri.Builder().scheme(SCHEME_SMB).path(str).build()
        } catch (_: Throwable) {
            Uri.Builder().scheme(SCHEME_SMB).path(str).build()
        }
    }

    fun build(connectionId: String, remotePath: String): Uri {
        val cleanPath = normalizeRemotePath(remotePath)
        val pathPart = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
        return uriFactory("$SCHEME_SMB://$connectionId$pathPart")
    }

    fun isSmbUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == SCHEME_SMB
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

    fun getShareName(uri: Uri): String? {
        val path = getRemotePath(uri)
        if (path == "/" || path.isEmpty()) return null
        val segments = path.trim('/').split('/')
        return segments.firstOrNull()?.ifEmpty { null }
    }

    fun getShareRelativePath(uri: Uri): String {
        val path = getRemotePath(uri)
        if (path == "/" || path.isEmpty()) return ""
        val segments = path.trim('/').split('/')
        if (segments.size <= 1) return ""
        return segments.drop(1).joinToString("/")
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
