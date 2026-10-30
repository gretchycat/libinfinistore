package com.infinifolder.apps

import android.net.Uri

object AppsUri {
    const val SCHEME = "apps"
    const val HOST_INSTALLED = "installed"
    const val HOST_CATEGORY = "category"
    const val HOST_PACKAGE = "package"

    var uriFactory: (String) -> Uri = { str ->
        try {
            Uri.parse(str) ?: Uri.Builder().scheme(SCHEME).path(str).build()
        } catch (_: Throwable) {
            Uri.Builder().scheme(SCHEME).path(str).build()
        }
    }

    fun parse(str: String): Uri = uriFactory(str)

    val ROOT_URI: Uri
        get() = uriFactory("apps://installed")

    fun isAppsUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val str = uri.toString()
        val s = try { uri.scheme } catch (_: Throwable) { null }
        return s == SCHEME || str.startsWith("apps://")
    }

    fun isRoot(uri: Uri?): Boolean {
        if (!isAppsUri(uri)) return false
        val str = uri.toString().removeSuffix("/")
        return str == "apps://installed" || str == "apps://" || str == "apps:"
    }

    fun isCategoryUri(uri: Uri?): Boolean {
        if (!isAppsUri(uri) || isRoot(uri)) return false
        val str = uri.toString()
        if (str.startsWith("apps://category/")) return true
        return try {
            uri?.host == HOST_CATEGORY || (uri?.pathSegments?.firstOrNull() == HOST_CATEGORY)
        } catch (_: Throwable) {
            false
        }
    }

    fun isAppUri(uri: Uri?): Boolean {
        if (!isAppsUri(uri) || isRoot(uri)) return false
        val str = uri.toString()
        if (str.startsWith("apps://package/")) return true
        return try {
            uri?.host == HOST_PACKAGE || (uri?.pathSegments?.firstOrNull() == HOST_PACKAGE)
        } catch (_: Throwable) {
            false
        }
    }

    fun categoryUri(categoryName: String): Uri {
        val clean = categoryName.trim()
        return uriFactory("apps://category/$clean")
    }

    fun appUri(packageName: String, categoryName: String): Uri {
        val cleanPkg = packageName.trim()
        val cleanCat = categoryName.trim()
        return uriFactory("apps://package/$cleanPkg?category=$cleanCat")
    }

    fun getCategoryName(uri: Uri?): String? {
        if (uri == null) return null
        val str = uri.toString()
        if (str.startsWith("apps://category/")) {
            return str.removePrefix("apps://category/").substringBefore('?').substringBefore('/')
        }
        val queryCat = try { uri.getQueryParameter("category") } catch (_: Throwable) { null }
        if (!queryCat.isNullOrEmpty()) return queryCat
        if (str.contains("category=")) {
            return str.substringAfter("category=").substringBefore('&')
        }
        return try {
            if (uri.host == HOST_CATEGORY) uri.lastPathSegment else null
        } catch (_: Throwable) {
            null
        }
    }

    fun getPackageName(uri: Uri?): String? {
        if (uri == null) return null
        val str = uri.toString()
        if (str.startsWith("apps://package/")) {
            return str.removePrefix("apps://package/").substringBefore('?').substringBefore('/')
        }
        return try {
            if (uri.host == HOST_PACKAGE) uri.lastPathSegment else null
        } catch (_: Throwable) {
            null
        }
    }
}
