package com.infinifolder.webdav

import android.net.Uri

object WebDavUri {
    fun build(id: String, path: String): Uri = Uri.Builder().scheme("webdav").authority(id)
        .encodedPath("/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }).build()
    fun owns(uri: Uri) = uri.scheme.equals("webdav", true)
    fun id(uri: Uri) = uri.authority.orEmpty()
    fun path(uri: Uri) = "/" + (uri.pathSegments.joinToString("/") { it }.trim('/'))
    fun parent(uri: Uri): Uri? {
        val p = path(uri).trimEnd('/')
        if (p.isEmpty()) return null
        return build(id(uri), p.substringBeforeLast('/', ""))
    }
}
