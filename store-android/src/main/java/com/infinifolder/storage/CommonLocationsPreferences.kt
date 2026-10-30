package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.ArchiveUri
import org.json.JSONArray
import org.json.JSONObject

/** Persistent folder references and usage, shared by UI hosts; contains no credentials. */
class CommonLocationsPreferences(context: Context) {
    data class Location(val uri: String, val label: String, val selections: Long = 0,
        val lastUsed: Long = 0, val manual: Boolean = false, val randomWriteConfirmedAt: Long = 0)
    private val prefs = context.applicationContext.getSharedPreferences("common_locations", Context.MODE_PRIVATE)
    var rememberAutomatically: Boolean
        get() = prefs.getBoolean("remember_automatically", true)
        set(value) { prefs.edit().putBoolean("remember_automatically", value).apply() }

    @Synchronized fun list(): List<Location> = runCatching {
        val array = JSONArray(prefs.getString("locations", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val uri = item.optString("uri")
            if (!safeUri(uri)) return@mapNotNull null
            Location(uri, item.optString("label").ifBlank { "Folder" },
                item.optLong("selections").coerceAtLeast(0), item.optLong("lastUsed").coerceAtLeast(0),
                item.optBoolean("manual"), item.optLong("randomWriteConfirmedAt").coerceAtLeast(0))
        }.distinctBy { it.uri }.sortedWith(compareByDescending<Location> { it.selections }.thenByDescending { it.lastUsed })
    }.getOrElse { DiagnosticLog.record("common-locations.restore.failed", error = it); emptyList() }

    @Synchronized fun remember(uri: Uri, label: String) {
        if (!safeUri(uri.toString())) return
        val entries = list()
        val old = entries.firstOrNull { it.uri == uri.toString() }
        if (!rememberAutomatically && old == null) return
        val count = (old?.selections ?: 0).let { if (it == Long.MAX_VALUE) it else it + 1 }
        save(entries.filterNot { it.uri == uri.toString() } +
            (old ?: Location(uri.toString(), cleanLabel(label))).copy(selections = count, lastUsed = System.currentTimeMillis()))
    }

    @Synchronized fun add(uri: Uri, label: String) {
        require(safeUri(uri.toString())) { "Choose a storage folder without credentials in its URI" }
        val entries = list()
        val old = entries.firstOrNull { it.uri == uri.toString() }
        save(entries.filterNot { it.uri == uri.toString() } +
            (old ?: Location(uri.toString(), cleanLabel(label))).copy(label = cleanLabel(label), manual = true))
    }

    @Synchronized fun rename(uri: String, label: String) =
        save(list().map { if (it.uri == uri) it.copy(label = cleanLabel(label), manual = true) else it })

    @Synchronized fun remove(uri: String) = save(list().filterNot { it.uri == uri })

    @Synchronized fun replace(oldUri: String, newUri: Uri, label: String) {
        require(safeUri(newUri.toString())) { "Choose a storage folder without credentials in its URI" }
        val entries = list()
        val old = entries.firstOrNull { it.uri == oldUri } ?: return
        val existing = entries.firstOrNull { it.uri == newUri.toString() && it.uri != oldUri }
        val same = oldUri == newUri.toString()
        save(entries.filterNot { it.uri == oldUri || it.uri == newUri.toString() } +
            (existing ?: old.copy(uri = newUri.toString(), selections = if (same) old.selections else 0,
                lastUsed = if (same) old.lastUsed else 0, randomWriteConfirmedAt = if (same) old.randomWriteConfirmedAt else 0))
                .copy(label = cleanLabel(label), manual = true))
    }

    /** Evidence comes from successful direct data delivery and handle close, not protocol inference. */
    @Synchronized fun recordRandomWriteSuccess(uri: Uri) {
        if (!safeUri(uri.toString())) return
        val entries = list()
        val old = entries.firstOrNull { it.uri == uri.toString() }
        if (old == null && !rememberAutomatically) return
        val now = System.currentTimeMillis()
        val updated = (old ?: Location(uri.toString(), cleanLabel(uri.lastPathSegment ?: "Folder")))
            .copy(randomWriteConfirmedAt = now, lastUsed = now)
        save(entries.filterNot { it.uri == uri.toString() } + updated)
    }

    @Synchronized fun clearRecent() = save(list().filter { it.manual }.map { it.copy(selections = 0, lastUsed = 0) })

    private fun save(entries: List<Location>) {
        // Explicitly saved folders are retained; bound automatically collected history.
        val ordered = entries.sortedWith(compareByDescending<Location> { it.selections }.thenByDescending { it.lastUsed })
        val retained = ordered.filter { it.manual } + ordered.filterNot { it.manual }.take(24)
        val array = JSONArray()
        retained.forEach { item -> array.put(JSONObject().put("uri", item.uri).put("label", item.label)
            .put("selections", item.selections).put("lastUsed", item.lastUsed).put("manual", item.manual)
            .put("randomWriteConfirmedAt", item.randomWriteConfirmedAt)) }
        prefs.edit().putString("locations", array.toString()).apply()
    }
    private fun cleanLabel(label: String) = label.trim().take(256).ifBlank { "Folder" }
    private fun safeUri(value: String): Boolean = runCatching {
        var uri = Uri.parse(value)
        require(!uri.scheme.isNullOrBlank())
        repeat(16) {
            require(!ContainerUris.hasPassword(uri))
            if (!ArchiveUri.isArchiveUri(uri)) return@runCatching true
            uri = ArchiveUri.getSourceUri(uri)
        }
        false
    }.getOrDefault(false)
}
