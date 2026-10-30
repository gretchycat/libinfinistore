package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import org.json.JSONArray
import org.json.JSONObject
import com.infinifolder.shortcut.ShortcutInfo
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.io.InputStream
import java.io.OutputStream

/** Discovery records point to original resources; listing never scans or connects to storage. */
class PartialDownloadsProvider(context: Context) : StorageProvider {
    override val id = "partial-downloads"
    override val features = BackendFeatures(randomRead = Support.UNSUPPORTED, randomWrite = Support.UNSUPPORTED,
        creation = CreationGuarantee.UNSUPPORTED, rename = Support.UNSUPPORTED, delete = Support.SUPPORTED,
        nativeLinks = Support.UNSUPPORTED)
    private val directory = File(context.applicationContext.filesDir, "partial-download-links").apply {
        if (!isDirectory && !mkdirs()) throw IOException("Cannot create private partial-download links")
    }
    private val linkTargets = mutableMapOf<String, ResourceRef>()
    private fun linkName(ref: ResourceRef): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${ref.providerId}:${ref.documentId}".toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString { bytes.forEach { byte -> val v = byte.toInt() and 255; append(hex[v ushr 4]); append(hex[v and 15]) }; append(ShortcutInfo.SHORTCUT_EXTENSION) }
    }
    private fun materialize(record: Found) {
        val name = linkName(record.entry.ref)
        linkTargets[name] = record.entry.ref
        val text = ShortcutInfo(targetUri = record.entry.uri.toString(),
            displayNameOverride = record.entry.metadata.name, targetMimeType = record.entry.metadata.mimeType,
            targetIsDirectory = false, createdAtMillis = record.seenAt).toJson()
        val file = File(directory, name)
        if (!file.isFile || file.readText() != text) {
            val temporary = File.createTempFile(".pending-", ".iflink", directory)
            try {
                temporary.writeText(text)
                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }
    private fun sourceRef(ref: ResourceRef): ResourceRef? = if (ref.providerId != id) ref else
        linkTargets[Uri.parse(ref.documentId).lastPathSegment]
    private fun linkedEntry(record: Found): ResourceEntry {
        val name = linkName(record.entry.ref)
        val file = File(directory, name)
        val uri = Uri.Builder().scheme(SCHEME).path("/$name").build()
        return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(
            record.entry.metadata.name + ShortcutInfo.SHORTCUT_EXTENSION, ShortcutInfo.SHORTCUT_MIME,
            file.length(), file.lastModified(), false, canRead = file.isFile, canWrite = false,
            operationHints = mapOf(ResourceCapability.MOVE to Support.UNSUPPORTED)), uri)
    }
    private val prefs = context.applicationContext.getSharedPreferences("partial-download-discovery", Context.MODE_PRIVATE)
    data class Found(val entry: ResourceEntry, val parent: ResourceRef, val kind: String, val seenAt: Long)
    private val found = linkedMapOf<ResourceRef, Found>()
    init {
        runCatching {
            val array = JSONArray(prefs.getString("entries", "[]"))
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val ref = ResourceRef(item.getString("provider"), item.getString("document"))
                val metadata = ResourceMetadata(item.getString("name"), item.optString("mime", "application/octet-stream"),
                    if (item.has("size")) item.getLong("size") else null,
                    if (item.has("modified")) item.getLong("modified") else null, false)
                found[ref] = Found(ResourceEntry(ref, metadata, Uri.parse(item.getString("uri"))),
                    ResourceRef(item.getString("parentProvider"), item.getString("parentDocument")),
                    item.getString("kind"), item.optLong("seenAt"))
            }
        }.onFailure { DiagnosticLog.record("partial-downloads.restore.failed", error = it) }
        found.values.forEach { record -> runCatching { materialize(record) }
            .onFailure { DiagnosticLog.record("partial-downloads.link.failed", error = it) } }
    }
    @Synchronized fun observe(parent: ResourceRef, entries: List<ResourceEntry>, complete: Boolean) {
        if (parent.providerId == id) return
        val byName = entries.associateBy { it.metadata.name.lowercase(java.util.Locale.ROOT) }
        var changed = false
        entries.filterNot { it.metadata.isDirectory || it.metadata.isSymbolicLink }.forEach { entry ->
            val name = entry.metadata.name.lowercase(java.util.Locale.ROOT)
            // Prefer the data object when a sidecar and its data are both visible.
            if ((name.endsWith(".aria2") && byName.containsKey(name.removeSuffix(".aria2"))) ||
                (name.endsWith(".part.met") && byName.containsKey(name.removeSuffix(".met")))) {
                if (found.remove(entry.ref) != null) changed = true
                return@forEach
            }
            val kind = if (byName.containsKey("$name.aria2")) "aria2 partial data" else classify(name)
            if (kind == null) {
                if (complete && found[entry.ref]?.parent == parent && found.remove(entry.ref) != null) changed = true
                return@forEach
            }
            val old = found[entry.ref]
            if (old?.entry != entry || old.kind != kind || old.parent != parent) {
                found[entry.ref] = Found(entry, parent, kind, System.currentTimeMillis())
                changed = true
            }
        }
        if (complete) {
            val current = entries.mapTo(hashSetOf()) { it.ref }
            val removed = found.entries.removeAll { it.value.parent == parent && it.key !in current }
            changed = changed || removed
        }
        if (changed) persist()
    }
    @Synchronized fun find(ref: ResourceRef): Found? = sourceRef(ref)?.let(found::get)
    @Synchronized fun forget(ref: ResourceRef) { if (sourceRef(ref)?.let(found::remove) != null) persist() }
    private fun persist() {
        val retained = found.keys.mapTo(hashSetOf(), ::linkName)
        linkTargets.keys.filter { it !in retained }.toList().forEach { name ->
            File(directory, name).delete()
            linkTargets.remove(name)
        }
        found.values.forEach { record -> runCatching { materialize(record) }
            .onFailure { DiagnosticLog.record("partial-downloads.link.failed", error = it) } }
        val array = JSONArray()
        found.values.forEach { record ->
            val entry = record.entry
            array.put(JSONObject().put("provider", entry.ref.providerId).put("document", entry.ref.documentId)
                .put("uri", entry.uri.toString()).put("name", entry.metadata.name).put("mime", entry.metadata.mimeType)
                .put("size", entry.metadata.sizeBytes).put("modified", entry.metadata.modifiedAtMillis)
                .put("parentProvider", record.parent.providerId).put("parentDocument", record.parent.documentId)
                .put("kind", record.kind).put("seenAt", record.seenAt))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }
    override fun refForUri(uri: Uri) = if (uri.scheme == SCHEME) ResourceRef(id, uri.toString()) else null
    override fun deviceIdentity(ref: ResourceRef) = id
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = ProviderResult.Success(
        if (Uri.parse(ref.documentId).path.orEmpty().trim('/').isEmpty()) null else ResourceRef(id, ROOT.toString()))
    @Synchronized override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> =
        if (Uri.parse(ref.documentId).path.orEmpty().trim('/').isEmpty()) ProviderResult.Success(
            ResourceEntry(ResourceRef(id, ROOT.toString()), ResourceMetadata("Partial downloads", "vnd.android.document/directory", null, null, true, canWrite = false,
                operationHints = mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED, ResourceCapability.MOVE to Support.UNSUPPORTED)), ROOT))
        else find(ref)?.let { ProviderResult.Success(linkedEntry(it)) }
            ?: ProviderResult.Failure("Unknown partial-download link")
    @Synchronized override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> =
        if (Uri.parse(parent.documentId).path.orEmpty().trim('/').isNotEmpty()) ProviderResult.Failure("A partial-download link is not a directory")
        else ProviderResult.Success(found.values.sortedByDescending { it.seenAt }.map(::linkedEntry))
    @Synchronized override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val record = find(ref) ?: return ProviderResult.Failure("Unknown partial-download link")
        ProviderResult.Success(File(directory, linkName(record.entry.ref)).inputStream())
    } catch (error: Exception) { ProviderResult.Failure("Cannot read private partial-download link", error) }
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = ProviderResult.Unsupported(ResourceCapability.WRITE)
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.CREATE)
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.CREATE)
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.RENAME)
    @Synchronized override fun delete(ref: ResourceRef): ProviderResult<Unit> {
        val record = find(ref) ?: return ProviderResult.Failure("Unknown partial-download link")
        val file = File(directory, linkName(record.entry.ref))
        if (file.exists() && !file.delete()) return ProviderResult.Failure("Cannot remove private partial-download link")
        forget(ref)
        return ProviderResult.Success(Unit)
    }
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = ProviderResult.Unsupported(ResourceCapability.COPY)
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = ProviderResult.Unsupported(ResourceCapability.MOVE)
    companion object {
        const val SCHEME = "partial-downloads"
        val ROOT: Uri = Uri.parse("partial-downloads:///")
        const val MIME = "application/vnd.infinifolder.partial-download"
        fun classify(name: String): String? {
            val lower = name.lowercase(java.util.Locale.ROOT)
            return when {
                lower.endsWith(".incomplete") -> "Partial download (.incomplete)"
                lower.endsWith(".!qb") -> "qBittorrent partial download"
                lower.endsWith(".!ut") -> "µTorrent partial download"
                lower.endsWith(".crdownload") -> "Chromium partial download"
                lower.endsWith(".aria2") -> "aria2 control file"
                lower.endsWith(".part.met") -> "eMule/aMule control file"
                lower.endsWith(".part") || lower.endsWith(".partial") -> "Possible partial download (ambiguous extension)"
                else -> null
            }
        }
    }
}
