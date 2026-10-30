package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.shortcut.ShortcutInfo
import java.io.*
import java.nio.file.Files

/** Virtual root's mutable contents: container shortcuts only. Targets are never deleted or modified. */
class ContainerLinksProvider(context: Context) : StorageProvider {
    companion object {
        const val SCHEME = "container-links"
        val ROOT: Uri get() = Uri.parse("$SCHEME:///")
        fun owns(uri: Uri) = uri.scheme == SCHEME
    }
    var validateTarget: ((ShortcutInfo) -> Unit)? = null
    override val id = "container-links"
    override val features = BackendFeatures(randomRead = Support.UNSUPPORTED, randomWrite = Support.UNSUPPORTED,
        creation = CreationGuarantee.EXCLUSIVE, rename = Support.SUPPORTED, delete = Support.SUPPORTED,
        nativeLinks = Support.UNSUPPORTED)
    private val root by lazy { File(context.applicationContext.filesDir, "container-links").apply {
        if (!isDirectory && !mkdirs()) throw IOException("Cannot create container-link storage")
    } }
    private fun file(ref: ResourceRef): File {
        require(ref.providerId == id)
        val uri = Uri.parse(ref.documentId)
        require(owns(uri))
        val name = uri.path.orEmpty().removePrefix("/")
        if (name.isEmpty()) return root
        StorageSafety.validateName(name)
        require(!name.startsWith(".pending-"))
        require(ShortcutInfo.isShortcut(name)) { "Virtual root accepts only container links" }
        return File(root, name)
    }
    private fun uri(file: File) = if (file == root) ROOT else Uri.Builder().scheme(SCHEME).path("/${file.name}").build()
    override fun refForUri(uri: Uri) = if (owns(uri)) ResourceRef(id, uri.toString()) else null
    override fun deviceIdentity(ref: ResourceRef) = id
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = guarded {
        ProviderResult.Success(if (file(ref) == root) null else ResourceRef(id, ROOT.toString()))
    }
    private fun info(file: File): ResourceEntry {
        val isRoot = file == root
        val parsed = if (!isRoot && file.isFile && file.length() <= 2 * 1024 * 1024) runCatching { ShortcutInfo.fromJson(file.readText()) }.getOrNull() else null
        val valid = isRoot || parsed?.let { runCatching { validate(it) }.isSuccess } == true
        val uri = uri(file)
        return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(
            if (isRoot) "Virtual root" else file.name,
            if (isRoot) "vnd.android.document/directory" else ShortcutInfo.SHORTCUT_MIME,
            if (isRoot) null else file.length(), file.lastModified(), isRoot,
            canRead = valid, canWrite = true,
            operationHints = if (isRoot) mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED,
                ResourceCapability.RENAME to Support.UNSUPPORTED, ResourceCapability.MOVE to Support.UNSUPPORTED) else emptyMap(),
            readError = if (valid) null else "Incomplete or invalid container link"), uri)
    }
    fun validate(shortcut: ShortcutInfo) {
        val target = Uri.parse(shortcut.targetUri)
        require(!target.scheme.isNullOrBlank()) { "A container link needs a URI" }
        require(!owns(target)) { "Container links must point to storage, not another Virtual root link" }
        var backing = target
        repeat(16) {
            require(!ContainerUris.hasPassword(backing)) { "Save passwords in connection settings, not in links" }
            if (ArchiveUri.isArchiveUri(backing)) backing = ArchiveUri.getSourceUri(backing)
        }
        val container = shortcut.targetIsDirectory || target.scheme.equals("magnet", true) ||
            ArchiveUri.isArchiveUri(target) || (target.isHierarchical && (
                ArchiveFormats.isArchive(target.lastPathSegment.orEmpty(), shortcut.targetMimeType.orEmpty()) ||
                    target.lastPathSegment.orEmpty().endsWith(".torrent", true)))
        require(container) { "Virtual root accepts links to containers, not regular files" }
    }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = guarded {
        val file = file(ref); if (!file.exists()) throw FileNotFoundException("Container link not found")
        ProviderResult.Success(info(file))
    }
    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = guarded {
        require(file(parent) == root) { "A link is not a directory; browse its target" }
        val files = root.listFiles() ?: throw IOException("Cannot list container links")
        ProviderResult.Success(files.filter { it.isFile && ShortcutInfo.isShortcut(it.name) }.sortedBy { it.name.lowercase() }.map(::info))
    }
    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = guarded {
        val file = file(ref); require(file != root && file.isFile)
        ProviderResult.Success(file.inputStream())
    }
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = guarded {
        require(file(parent) == root)
        StorageSafety.validateName(name)
        require(ShortcutInfo.isShortcut(name)) {
            "Virtual root stores only .iflink container links; use Link to for a folder or archive"
        }
        val created = File(root, name)
        if (!created.createNewFile()) throw IOException("A link with that name already exists")
        ProviderResult.Success(info(created))
    }
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = guarded {
        val destination = file(ref)
        require(destination != root && destination.isFile)
        val identity = android.system.Os.stat(destination.path)
        val staged = File.createTempFile(".pending-", ".json", root)
        val output = staged.outputStream()
        ProviderResult.Success(object : OutputStream(), PendingWrite {
            private var aborted = false
            private var closed = false
            private var count = 0L
            override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                count += length
                if (count > 2 * 1024 * 1024) throw IOException("Container-link metadata is too large")
                output.write(bytes, offset, length)
            }
            override fun flush() { output.flush() }
            override fun abort() { aborted = true }
            override fun close() {
                if (closed) return
                closed = true
                try {
                    if (!aborted) output.fd.sync()
                    output.close()
                    if (!aborted) {
                        val shortcut = ShortcutInfo.fromJson(staged.readText()) ?: throw IOException("Virtual root accepts only valid container links")
                        validate(shortcut)
                        validateTarget?.invoke(shortcut)
                        val current = android.system.Os.stat(destination.path)
                        if (current.st_ino != identity.st_ino || current.st_dev != identity.st_dev) throw IOException("Container link was replaced while saving")
                        if (!destination.isFile) throw IOException("Container link disappeared while saving")
                        // Replacement is confined to this reserved metadata file, never the linked target.
                        Files.move(staged.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { runCatching { output.close() }; staged.delete() }
            }
        })
    }
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Failure("Virtual root contains container links, not directories")
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = guarded {
        val file = file(ref); require(file != root) { "Cannot delete Virtual root" }
        if (!file.delete()) throw IOException("Cannot delete container link")
        ProviderResult.Success(Unit)
    }
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = guarded {
        val source = file(ref); require(source != root && source.isFile)
        StorageSafety.validateName(name); require(ShortcutInfo.isShortcut(name))
        val destination = File(root, name)
        if (source == destination) return@guarded ProviderResult.Success(info(source))
        Files.createLink(destination.toPath(), source.toPath()) // Exclusive, no-replace reservation.
        try { Files.delete(source.toPath()) } catch (error: Exception) {
            runCatching { Files.delete(destination.toPath()) }; throw error
        }
        ProviderResult.Success(info(destination))
    }
    override fun copy(source: ResourceRef, destination: ResourceRef) = StorageTransfers.transfer(this, source, destination, move = false)
    override fun move(source: ResourceRef, destination: ResourceRef) = StorageTransfers.transfer(this, source, destination, move = true)
    fun add(shortcut: ShortcutInfo, name: String): ProviderResult<ResourceEntry> = guarded {
        validate(shortcut)
        val base = name.removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION).ifBlank { "Container" }
        var candidate = base + ShortcutInfo.SHORTCUT_EXTENSION
        var suffix = 2
        while (File(root, candidate).exists()) candidate = "$base (${suffix++})${ShortcutInfo.SHORTCUT_EXTENSION}"
        val created = createFile(ResourceRef(id, ROOT.toString()), candidate, ShortcutInfo.SHORTCUT_MIME)
        if (created !is ProviderResult.Success) return@guarded created
        try {
            val opened = openWrite(created.value.ref)
            if (opened !is ProviderResult.Success) throw IOException((opened as? ProviderResult.Failure)?.message ?: "Cannot save link")
            opened.value.use { it.write(shortcut.toJson().toByteArray(Charsets.UTF_8)) }
            stat(created.value.ref)
        } catch (error: Exception) { delete(created.value.ref); throw error }
    }
    private inline fun <T> guarded(action: () -> ProviderResult<T>): ProviderResult<T> = try { action() }
        catch (error: Exception) { ProviderResult.Failure(error.message ?: "Container link operation failed", error) }
}
