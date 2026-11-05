package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.shortcut.ShortcutInfo
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Private, deletable .iflink directory. Its entries never own or delete their targets. */
class RecentExternalLinksProvider(context: Context) : StorageProvider {
    override val id = SCHEME
    override val features = BackendFeatures(randomRead = Support.UNSUPPORTED, randomWrite = Support.UNSUPPORTED,
        creation = CreationGuarantee.UNSUPPORTED, rename = Support.UNSUPPORTED, delete = Support.SUPPORTED,
        nativeLinks = Support.UNSUPPORTED)
    private val directory = File(context.applicationContext.filesDir, "recent-external-links").apply {
        if (!isDirectory && !mkdirs()) throw java.io.IOException("Cannot create recent external links")
    }

    private fun name(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) } + ShortcutInfo.SHORTCUT_EXTENSION
    }
    private fun file(ref: ResourceRef): File? {
        if (ref.providerId != id) return null
        val uri = Uri.parse(ref.documentId)
        if (uri.scheme != SCHEME || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
        val part = uri.path.orEmpty().removePrefix("/")
        return if (part.isEmpty()) directory else if (part.matches(Regex("[0-9a-f]{64}\\.iflink"))) File(directory, part) else null
    }
    private fun entry(file: File): ResourceEntry {
        val root = file == directory
        val uri = if (root) ROOT else Uri.Builder().scheme(SCHEME).path("/${file.name}").build()
        val label = if (root) "Recent external links" else runCatching {
            if (file.length() > 1024 * 1024) null else ShortcutInfo.fromJson(file.readText())?.displayNameOverride
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "$it${ShortcutInfo.SHORTCUT_EXTENSION}" } ?: file.name
        return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(
            label,
            if (root) "vnd.android.document/directory" else ShortcutInfo.SHORTCUT_MIME,
            if (root) null else file.length(), if (root) null else file.lastModified(), root,
            canRead = root || file.isFile, canWrite = false,
            operationHints = if (root) mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED) else emptyMap()), uri)
    }
    /** Returns a stable link for a URI; reopening it updates its name and recent timestamp. */
    @Synchronized fun remember(uri: Uri, title: String, mime: String? = null, directoryTarget: Boolean = false,
        ifAbsent: Boolean = false): ResourceEntry {
        require(!uri.scheme.isNullOrBlank()) { "A recent link needs a URI scheme" }
        val file = File(directory, name(uri))
        if (ifAbsent && file.isFile) return entry(file)
        val old = if (file.isFile && file.length() <= 1024 * 1024) runCatching { ShortcutInfo.fromJson(file.readText()) }.getOrNull() else null
        val info = ShortcutInfo(targetUri = uri.toString(), displayNameOverride = title.ifBlank { old?.displayNameOverride ?: uri.host ?: uri.lastPathSegment ?: "External link" },
            targetMimeType = mime ?: old?.targetMimeType, targetIsDirectory = directoryTarget || old?.targetIsDirectory == true)
        val staged = File.createTempFile(".pending-", ".iflink", directory)
        try {
            staged.writeText(info.toJson())
            java.nio.file.Files.move(staged.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.delete() }
        return entry(file)
    }
    override fun refForUri(uri: Uri) = if (uri.scheme == SCHEME && uri.authority.isNullOrEmpty()) ResourceRef(id, uri.toString()) else null
    override fun deviceIdentity(ref: ResourceRef) = id
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = ProviderResult.Success(if (file(ref) == directory) null else ResourceRef(id, ROOT.toString()))
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = file(ref)?.takeIf { it.exists() }?.let { ProviderResult.Success(entry(it)) }
        ?: ProviderResult.Failure("Recent external link not found")
    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = if (file(parent) == directory)
        ProviderResult.Success(directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.iflink")) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name }).map(::entry))
        else ProviderResult.Failure("A recent external link is not a directory")
    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val source = file(ref)?.takeIf { it != directory && it.isFile } ?: return ProviderResult.Failure("Recent external link not found")
        ProviderResult.Success(source.inputStream())
    } catch (error: Exception) { ProviderResult.Failure("Cannot read recent external link", error) }
    override fun delete(ref: ResourceRef): ProviderResult<Unit> {
        val target = file(ref)?.takeIf { it != directory && it.isFile } ?: return ProviderResult.Failure("Recent external link not found")
        return if (target.delete()) ProviderResult.Success(Unit) else ProviderResult.Failure("Cannot delete recent external link")
    }
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = ProviderResult.Unsupported(ResourceCapability.WRITE)
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.CREATE)
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.CREATE)
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.RENAME)
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = ProviderResult.Unsupported(ResourceCapability.COPY)
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = ProviderResult.Unsupported(ResourceCapability.MOVE)
    companion object {
        const val SCHEME = "recent-external-links"
        val ROOT: Uri = Uri.parse("$SCHEME:///")
    }
}
