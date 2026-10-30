package com.infinifolder.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.infinifolder.model.*
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SharedFileProvider : FileProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): android.database.Cursor {
        val cursor = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val requestedColumns = projection ?: return cursor
        if (!requestedColumns.contains(android.provider.MediaStore.MediaColumns.DATA)) {
            return cursor
        }
        val columnNames = cursor.columnNames.toMutableList()
        if (!columnNames.contains(android.provider.MediaStore.MediaColumns.DATA)) {
            columnNames.add(android.provider.MediaStore.MediaColumns.DATA)
        }
        val matrix = android.database.MatrixCursor(columnNames.toTypedArray(), cursor.count)
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) {
            val row = matrix.newRow()
            for (col in cursor.columnNames) {
                val idx = cursor.getColumnIndex(col)
                when (cursor.getType(idx)) {
                    android.database.Cursor.FIELD_TYPE_STRING -> row.add(col, cursor.getString(idx))
                    android.database.Cursor.FIELD_TYPE_INTEGER -> row.add(col, cursor.getLong(idx))
                    android.database.Cursor.FIELD_TYPE_FLOAT -> row.add(col, cursor.getDouble(idx))
                    android.database.Cursor.FIELD_TYPE_BLOB -> row.add(col, cursor.getBlob(idx))
                    else -> row.add(col, null)
                }
            }
            val path = uri.path?.removePrefix("/shared/")?.removePrefix("/root/")?.removePrefix("/cache/")
            val file = if (path != null) {
                val ext = File(Environment.getExternalStorageDirectory(), path)
                if (ext.exists()) ext else File("/", path)
            } else null
            if (file != null && file.exists()) {
                row.add(android.provider.MediaStore.MediaColumns.DATA, file.absolutePath)
            }
        }
        cursor.close()
        return matrix
    }

    override fun getType(uri: Uri): String? {
        val path = uri.path?.removePrefix("/shared/")?.removePrefix("/root/")?.removePrefix("/cache/")
        val file = if (path != null) {
            val ext = File(Environment.getExternalStorageDirectory(), path)
            if (ext.exists()) ext else File("/", path)
        } else null
        if (file != null && file.exists()) {
            val pkgMime = com.infinifolder.archive.ArchiveFormats.packageMimeType(file.name)
            if (pkgMime != null) return pkgMime
            val ext = file.extension.lowercase()
            val extMime = MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
                ?: com.infinifolder.archive.ArchiveMimeTypes.guessMimeType(file.name, false)
            if (FileMagicDetector.isAmbiguousOrUnknown(file.name, extMime)) {
                return FileMagicDetector.detect(file)?.mimeType ?: extMime
            }
            return extMime
        }
        return super.getType(uri)
    }
}

/** Primary shared storage only; root discovered from Android, enabled by an explicit system grant. */
class LocalStorageProvider(private val context: Context) : StorageProvider {
    override val id = "local"
    override val features = BackendFeatures(Support.SUPPORTED, Support.SUPPORTED, Support.SUPPORTED, CreationGuarantee.EXCLUSIVE, Support.SUPPORTED, Support.SUPPORTED, Support.SUPPORTED,
        transferTarget = Support.SUPPORTED)

    val authority = context.packageName + ".files"
    val store = LocalFileStore(Environment.getExternalStorageDirectory())
    val rootUri: Uri = uriFor(store.root)

    override fun refForUri(uri: Uri): ResourceRef? = if (owns(uri)) ResourceRef(id, uri.toString()) else null

    override fun deviceIdentity(ref: ResourceRef): String? = if (owns(Uri.parse(ref.documentId))) "local:primary" else null

    override fun fileSystemInfo(ref: ResourceRef): ProviderResult<FileSystemInfo> = access { StorageFileSystems.forFile(file(ref)) }
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = access {
        val selected = file(ref)
        if (selected == store.root || selected == context.cacheDir.canonicalFile) null
        else selected.parentFile?.let { ResourceRef(id, uriFor(it).toString()) }
    }
    override fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = access {
        val selected = file(ref)
        require(Files.isSymbolicLink(selected.toPath())) { "Resource is not a native link" }
        val target = selected.canonicalFile
        require(target.exists()) { "Link target is unavailable" }
        ResourceRef(id, uriFor(target).toString())
    }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = access {
        val target = file(ref)
        checkAccess(target)
        if (!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw java.io.FileNotFoundException(target.path)
        entry(target)
    }

    override fun readTransferMetadata(ref: ResourceRef): ProviderResult<TransferMetadata> = access {
        val target = file(ref); checkAccess(target)
        require(!Files.isSymbolicLink(target.toPath())) { "Resolve the source link before reading transfer metadata" }
        val stat = android.system.Os.lstat(target.path)
        val basic = Files.readAttributes(target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val modified = basic.lastModifiedTime().toInstant(); val accessed = basic.lastAccessTime().toInstant()
        val attributes = try {
            android.system.Os.listxattr(target.path).associateWith { name -> android.system.Os.getxattr(target.path, name) }
        } catch (error: android.system.ErrnoException) {
            if (error.errno == android.system.OsConstants.EOPNOTSUPP) emptyMap() else throw error
        }
        TransferMetadata(stat.st_mode and 0xfff, stat.st_uid.toLong() and 0xffffffffL, stat.st_gid.toLong() and 0xffffffffL,
            modified.epochSecond, modified.nano, accessed.epochSecond, accessed.nano, attributes)
    }

    override fun applyTransferMetadata(ref: ResourceRef, metadata: TransferMetadata): ProviderResult<Unit> = access {
        val target = file(ref); checkAccess(target); requireMutable(target)
        require(!Files.isSymbolicLink(target.toPath())) { "Cannot apply transfer metadata through a symbolic link" }
        val before = android.system.Os.lstat(target.path)
        if (metadata.uid != null && metadata.uid != (before.st_uid.toLong() and 0xffffffffL) ||
            metadata.gid != null && metadata.gid != (before.st_gid.toLong() and 0xffffffffL)) {
            android.system.Os.chown(target.path, metadata.uid?.toInt() ?: -1, metadata.gid?.toInt() ?: -1)
        }
        metadata.mode?.let { if (it != before.st_mode and 0xfff) android.system.Os.chmod(target.path, it) }
        metadata.xattrs?.forEach { (name, value) ->
            val existing = runCatching { android.system.Os.getxattr(target.path, name) }.getOrNull()
            if (existing == null || !existing.contentEquals(value)) android.system.Os.setxattr(target.path, name, value, 0)
        }
        fun timestamp(seconds: Long?, nanos: Int) = seconds?.let {
            java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(it, nanos.toLong()))
        }
        Files.getFileAttributeView(target.toPath(), java.nio.file.attribute.BasicFileAttributeView::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).setTimes(timestamp(metadata.modifiedSeconds, metadata.modifiedNanos),
                timestamp(metadata.accessedSeconds, metadata.accessedNanos), null)
        val actual = readTransferMetadata(ref)
        if (actual !is ProviderResult.Success || !metadata.matches(actual.value))
            throw IOException("Destination metadata could not be retained exactly")
    }

    override fun replaceContent(ref: ResourceRef, data: ByteArray): ProviderResult<Unit> = access {
        val target = file(ref)
        checkAccess(target)
        requireMutable(target)
        AtomicFileReplacement.replace(target, data)
    }

    override fun replaceContentFromFile(ref: ResourceRef, file: File): ProviderResult<Unit> = access {
        val target = this.file(ref)
        checkAccess(target)
        requireMutable(target)
        AtomicFileReplacement.replaceFromFile(target, file)
    }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<com.infinifolder.archive.BlockDevice> = access {
        val target = file(ref)
        checkAccess(target)
        if (!target.isFile) throw IOException("Not a regular file: ${target.name}")
        val lease = DiskCache.get(context).pin(target)
        try {
            if (writable && lease != null) throw IOException("Cached snapshots are read-only; copy to storage before editing")
            val device = com.infinifolder.archive.FileBlockDevice(java.io.RandomAccessFile(target, if (writable) "rw" else "r"), !writable, ownsFile = true)
            object : com.infinifolder.archive.BlockDevice by device {
                override fun close() { try { device.close() } finally { lease?.close() } }
            }
        } catch (error: Throwable) { lease?.close(); throw error }
    }

    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun owns(uri: Uri): Boolean = try {
        if (uri.scheme == "content" && uri.authority == authority) {
            val first = uri.pathSegments.firstOrNull()
            first == "shared" || first == "root" || first == "cache"
        } else if (uri.scheme == "file") {
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    fun fileFor(uri: Uri): File {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Missing file path")
            return File(path).absoluteFile.toPath().normalize().toFile()
        }
        require(owns(uri)) { "Unknown local resource: $uri" }
        val first = uri.pathSegments.firstOrNull()
        return when (first) {
            "shared" -> lexicalChild(store.root, uri.pathSegments.drop(1))
            "root" -> lexicalChild(File("/"), uri.pathSegments.drop(1))
            "cache" -> lexicalChild(context.cacheDir, uri.pathSegments.drop(1))
            else -> throw IllegalArgumentException("Unknown local resource")
        }
    }
    private fun lexicalChild(root: File, components: List<String>): File {
        components.forEach(StorageSafety::validateName)
        return components.fold(root.absoluteFile) { parent, name -> File(parent, name) }
    }

    fun uriFor(file: File): Uri {
        // Preserve the selected directory entry, including a symbolic link's own path.
        val canonical = file.absoluteFile.toPath().normalize().toFile()
        val cacheCanonical = context.cacheDir.canonicalFile
        val sharedCanonical = store.root.canonicalFile
        if (canonical == cacheCanonical || canonical.toPath().startsWith(cacheCanonical.toPath())) {
            val builder = Uri.Builder().scheme("content").authority(authority).appendPath("cache")
            cacheCanonical.toPath().relativize(canonical.toPath()).map { it.toString() }.filter { it.isNotEmpty() }
                .forEach(builder::appendPath)
            return builder.build()
        }
        if (canonical == sharedCanonical || canonical.toPath().startsWith(sharedCanonical.toPath())) {
            val builder = Uri.Builder().scheme("content").authority(authority).appendPath("shared")
            sharedCanonical.toPath().relativize(canonical.toPath()).map { it.toString() }.filter { it.isNotEmpty() }
                .forEach(builder::appendPath)
            return builder.build()
        }
        val builder = Uri.Builder().scheme("content").authority(authority).appendPath("root")
        canonical.toPath().map { it.toString() }.filter { it.isNotEmpty() }.forEach(builder::appendPath)
        return builder.build()
    }
    fun entry(file: File): ResourceEntry {
        val uri = uriFor(file)
        val cachedSnapshot = DiskCache.get(context).isManaged(file)
        val isSymlink = try { Files.isSymbolicLink(file.toPath()) } catch (_: Throwable) { false }
        val targetPath = if (isSymlink) {
            try { Files.readSymbolicLink(file.toPath()).toString() } catch (_: Throwable) { null }
        } else null
        val canonicalTarget = if (isSymlink) {
            try { file.canonicalFile } catch (_: Throwable) { null }
        } else null
        val targetExists = canonicalTarget?.exists() ?: false
        val isBroken = isSymlink && !targetExists
        val targetFile = if (isSymlink && targetExists) canonicalTarget ?: file else file

        val isDir = targetFile.isDirectory
        val mime = if (isDir) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            val pkgMime = com.infinifolder.archive.ArchiveFormats.packageMimeType(targetFile.name)
            if (pkgMime != null) {
                pkgMime
            } else {
                val extMime = com.infinifolder.mime.MimeRegistry.guess(targetFile.name)
                if (FileMagicDetector.isAmbiguousOrUnknown(targetFile.name, extMime)) {
                    FileMagicDetector.detect(targetFile)?.mimeType ?: extMime
                } else {
                    extMime
                }
            }
        }
        val perm = if (isBroken) {
            FilePermissions(canRead = false, canWrite = false, canExecute = false, mode = null, formatted = null, owner = null, group = null)
        } else {
            PermissionHelper.getFilePermissions(targetFile)
        }
        val displayName = when {
            file == store.root -> "Internal shared storage"
            else -> file.name.ifBlank { file.path }
        }
        return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(
            name = displayName,
            mimeType = mime,
            sizeBytes = if (isDir) null else (if (isBroken) 0L else targetFile.length()),
            modifiedAtMillis = if (isBroken) file.lastModified() else targetFile.lastModified(),
            isDirectory = isDir,
            canRead = perm.canRead,
            canWrite = perm.canWrite && !cachedSnapshot,
            canExecute = perm.canExecute,
            permissionsMode = perm.mode,
            permissionsFormatted = perm.formatted,
            owner = perm.owner,
            group = perm.group,
            isSymbolicLink = isSymlink,
            linkTarget = targetPath,
            isBrokenLink = isBroken,
            operationHints = mapOf(
                ResourceCapability.RENAME to if (!cachedSnapshot && file != store.root && file.parentFile?.canWrite() == true) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.DELETE to if (!cachedSnapshot && file != store.root && file.parentFile?.canWrite() == true) Support.SUPPORTED else Support.UNSUPPORTED)
        ), uri)
    }
    private fun file(ref: ResourceRef): File {
        require(ref.providerId == id) { "Wrong storage provider" }
        return fileFor(Uri.parse(ref.documentId))
    }

    private val appInternalRoots: List<java.nio.file.Path> by lazy {
        val folders = mutableListOf(context.filesDir, context.cacheDir)
        runCatching { folders.addAll(context.getExternalFilesDirs(null).filterNotNull()) }
        runCatching { folders.addAll(context.getExternalCacheDirs().filterNotNull()) }
        folders.map { it.canonicalFile.toPath() }.distinct()
    }

    private fun isAppInternal(file: File): Boolean {
        val canonical = file.canonicalFile.toPath()
        return appInternalRoots.any { canonical.startsWith(it) }
    }

    private fun checkAccess(file: File) {
        if (isAppInternal(file)) return
        if (!hasAccess() && !file.canRead()) {
            throw SecurityException("Storage permission is required to access ${file.name}")
        }
    }

    private fun requireMutable(file: File) {
        if (DiskCache.get(context).isManaged(file)) throw IOException("Cached snapshots are read-only. Copy to storage before editing.")
    }

    private fun <T> access(action: () -> T): ProviderResult<T> = try {
        ProviderResult.Success(action())
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Local storage operation failed", e) }

    override fun list(parent: ResourceRef) = access {
        val dir = file(parent)
        checkAccess(dir)
        val files = dir.listFiles()?.toList() ?: throw IOException("Android did not allow reading directory: ${dir.name}")
        files.map(::entry)
    }
    override fun openRead(ref: ResourceRef) = access {
        val f = file(ref)
        checkAccess(f)
        // Ordinary source reads must not initialize or wait on the private cache.
        val lease = if (f.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
            DiskCache.get(context).pin(f) else null
        try {
            object : java.io.FilterInputStream(f.inputStream()) {
                override fun close() { try { super.close() } finally { lease?.close() } }
            }
        } catch (error: Throwable) { lease?.close(); throw error }
    }
    override fun openWrite(ref: ResourceRef) = access {
        val f = file(ref)
        checkAccess(f)
        requireMutable(f)
        f.outputStream()
    }
    override fun createDirectory(parent: ResourceRef, name: String) = access {
        val parentDir = file(parent)
        checkAccess(parentDir)
        requireMutable(parentDir)
        StorageSafety.validateName(name)
        StorageFileSystems.forFile(parentDir).requireName(name)
        val created = File(parentDir, name)
        Files.createDirectory(created.toPath())
        entry(created)
    }
    override fun createFile(parent: ResourceRef, name: String, mimeType: String) = access {
        val parentDir = file(parent)
        checkAccess(parentDir)
        requireMutable(parentDir)
        StorageSafety.validateName(name)
        StorageFileSystems.forFile(parentDir).requireName(name)
        val created = File(parentDir, name)
        Files.createFile(created.toPath())
        entry(created)
    }
    override fun rename(ref: ResourceRef, name: String) = access {
        val src = file(ref)
        checkAccess(src)
        requireMutable(src)
        StorageSafety.validateName(name)
        StorageFileSystems.forFile(src).requireName(name)
        val dest = File(src.parentFile, name)
        Files.move(src.toPath(), dest.toPath())
        entry(dest)
    }
    override fun delete(ref: ResourceRef) = access {
        val target = file(ref)
        checkAccess(target)
        requireMutable(target)
        require(target.absoluteFile.toPath().normalize() != store.root.absoluteFile.toPath().normalize()) { "Cannot delete the storage root" }
        LocalFileStore(File("/")).delete(target)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = false)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = true)

}
