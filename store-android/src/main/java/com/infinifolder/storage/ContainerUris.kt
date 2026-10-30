package com.infinifolder.storage

import android.net.Uri
import java.util.Locale

/** Public host-based URIs bind to the existing credential-backed provider identities. */
object ContainerUris {
    fun networkType(uri: Uri): String? = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "ftp", "ftps" -> "ftp"
        "sftp", "sshfs", "ssh" -> "sftp"
        "smb", "cifs" -> "smb"
        "webdav", "dav", "davs" -> "webdav"
        else -> null
    }
    fun hasPassword(uri: Uri): Boolean = networkType(uri) != null && uri.userInfo?.contains(':') == true
    fun defaultPort(uri: Uri): Int = when (networkType(uri)) { "ftp" -> 21; "smb" -> 445; "webdav" -> if(uri.scheme.equals("dav",true)) 80 else 443; else -> 22 }
    fun candidates(uri: Uri, connections: List<ProviderConnection>): List<ProviderConnection> {
        val type = networkType(uri) ?: return emptyList()
        if (hasPassword(uri)) return emptyList()
        connections.firstOrNull { it.sourceTypeId == type && it.id == uri.authority }?.let { return listOf(it) }
        val host = uri.host?.trim('[', ']') ?: return emptyList()
        val username = uri.userInfo?.takeIf { it.isNotEmpty() }
        val port = uri.port.takeIf { it > 0 } ?: defaultPort(uri)
        return connections.filter { connection ->
            val endpointHost = connection.options["host"] ?: connection.endpoint?.substringBefore(':')
            connection.sourceTypeId == type && endpointHost?.trim('[', ']')?.equals(host, true) == true &&
                (if (type == "webdav" && uri.port <= 0) true else (connection.options["port"]?.toIntOrNull() ?: defaultPort(uri)) == port) &&
                (username == null || connection.options["username"] == username) &&
                (type != "ftp" || (connection.options["useFtps"] == "true") == uri.scheme.equals("ftps", true)) &&
                (type != "webdav" || webDavPathMatches(uri, connection))
        }
    }

    private fun webDavPathMatches(uri: Uri, connection: ProviderConnection): Boolean {
        if (uri.scheme.equals("dav", true) && connection.options["tls"] == "true") return false
        if (uri.scheme.equals("davs", true) && connection.options["tls"] == "false") return false
        val basePath=runCatching { Uri.parse(connection.options["url"] ?: connection.endpoint).path.orEmpty().trimEnd('/') }.getOrDefault("")
        val target=uri.path.orEmpty().trimEnd('/')
        return basePath.isEmpty() || target==basePath || target.startsWith("$basePath/")
    }

    fun bind(uri: Uri, connection: ProviderConnection): Uri {
        val type = networkType(uri) ?: error("Not a storage server URI")
        require(type == connection.sourceTypeId)
        require(!hasPassword(uri)) { "Enter passwords in connection settings, not in a saved URI" }
        return Uri.Builder().scheme(type).authority(connection.id).encodedPath(uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/").build()
    }
    /** null means missing/ambiguous credentials, not permission to pick an arbitrary account. */
    fun resolve(uri: Uri, connections: List<ProviderConnection>): Uri? {
        if (networkType(uri) == null) return uri
        return candidates(uri, connections).singleOrNull()?.let { bind(uri, it) }
    }
}
