package com.infinifolder.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.infinifolder.model.*
import java.io.InputStream
import java.io.OutputStream
import android.content.ContentValues
import java.io.FileNotFoundException
import java.io.IOException

class SafProvider(private val resolver: ContentResolver) : StorageProvider {
    private val observedParents = android.util.LruCache<String, ResourceRef>(8192)
    override val id = "saf"
    override val features = BackendFeatures(creation = CreationGuarantee.SERVER_UNIQUE_NAME, nativeLinks = Support.UNSUPPORTED)


    override fun refForUri(uri: Uri): ResourceRef? =
        if (uri.scheme == "content") ResourceRef(id, uri.toString()) else null

    override fun deviceIdentity(ref: ResourceRef): String? = try {
        val uri = Uri.parse(ref.documentId)
        // SAF grants are independent storage sources. Keep each selected tree distinct,
        // even when two trees happen to live on the same underlying volume.
        val rootId = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
            else documentId(uri)
        "saf:${uri.authority}:$rootId"
    } catch (_: Exception) { null }

    override fun fileSystemInfo(ref: ResourceRef): ProviderResult<FileSystemInfo> =
        ProviderResult.Success(StorageFileSystems.forExternalDocument(Uri.parse(ref.documentId)))

    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = try {
        val uri = Uri.parse(ref.documentId)
        val observed = synchronized(observedParents) { observedParents.get(ref.documentId) }
        if (observed != null) ProviderResult.Success(observed)
        else if (!DocumentsContract.isTreeUri(uri)) ProviderResult.Unsupported(ResourceCapability.PARENT) else {
            val idValue = documentId(uri)
            if (idValue == DocumentsContract.getTreeDocumentId(uri)) ProviderResult.Success(null) else {
                val path = DocumentsContract.findDocumentPath(resolver, uri)?.path
                val parentId = path?.getOrNull(path.size - 2)
                if (parentId == null) ProviderResult.Unsupported(ResourceCapability.PARENT)
                else ProviderResult.Success(ResourceRef(id, DocumentsContract.buildDocumentUriUsingTree(uri, parentId).toString()))
            }
        }
    } catch (_: UnsupportedOperationException) { ProviderResult.Unsupported(ResourceCapability.PARENT) }
      catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve document parent", e) }

    private fun operationHints(flags: Int, directory: Boolean): Map<ResourceCapability, Support> {
        fun flag(value: Int) = if (flags and value != 0) Support.SUPPORTED else Support.UNSUPPORTED
        return mapOf(ResourceCapability.WRITE to if (directory) Support.UNSUPPORTED else flag(DocumentsContract.Document.FLAG_SUPPORTS_WRITE),
            ResourceCapability.CREATE to if (directory) flag(DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) else Support.UNSUPPORTED,
            ResourceCapability.RENAME to flag(DocumentsContract.Document.FLAG_SUPPORTS_RENAME),
            ResourceCapability.DELETE to flag(DocumentsContract.Document.FLAG_SUPPORTS_DELETE),
            ResourceCapability.MOVE to flag(DocumentsContract.Document.FLAG_SUPPORTS_DELETE))
    }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(ref.documentId)
        val result = try { entry(uri) } catch (original: Exception) {
            // Android Open with can supply an ordinary grantable content URI,
            // rather than a DocumentsProvider URI. Query only its standard
            // OpenableColumns; actual write access must still be attempted.
            val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?: throw original
            cursor.use { c ->
                if (!c.moveToFirst()) throw original
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !c.isNull(nameIndex)) c.getString(nameIndex) else uri.lastPathSegment ?: "File"
                val size = if (sizeIndex >= 0 && !c.isNull(sizeIndex)) c.getLong(sizeIndex) else null
                val mime = com.infinifolder.mime.MimeRegistry.effective(name, resolver.getType(uri))
                ResourceEntry(ref, ResourceMetadata(name, mime, size, null, false, canWrite = true,
                    operationHints = mapOf(ResourceCapability.WRITE to Support.UNKNOWN,
                        ResourceCapability.RENAME to Support.UNSUPPORTED, ResourceCapability.DELETE to Support.UNSUPPORTED)), uri)
            }
        }
        ProviderResult.Success(result)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query document", e) }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<com.infinifolder.archive.BlockDevice> {
        var descriptor: android.os.ParcelFileDescriptor? = null
        return try {
            descriptor = resolver.openFileDescriptor(Uri.parse(ref.documentId), if (writable) "rw" else "r")
                ?: return ProviderResult.Failure("Provider returned no file descriptor")
            val device = com.infinifolder.archive.ContentUriBlockDevice(descriptor, !writable)
            descriptor = null // Ownership transferred to device.
            ProviderResult.Success(com.infinifolder.archive.RangeCachedBlockDevice(device))
        } catch (e: Exception) {
            val nonSeekable = generateSequence<Throwable>(e) { it.cause }.take(16).any {
                (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ESPIPE) ||
                    it is UnsupportedOperationException
            }
            if (nonSeekable) ProviderResult.Unsupported(if (writable) ResourceCapability.SEEK_WRITE else ResourceCapability.SEEK)
            else ProviderResult.Failure(e.message ?: "Cannot open document descriptor", e)
        } finally { descriptor?.close() }
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val parentUri = Uri.parse(parent.documentId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri, documentId(parentUri)
        )
        val rows = mutableListOf<ResourceEntry>()
        val cursor = resolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        ), null, null, null)
        if (cursor == null) directoryQueryResult<ResourceEntry>(null) else cursor.use {
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: "Unnamed"
                val mime = com.infinifolder.mime.MimeRegistry.effective(name, cursor.getString(mimeCol))
                val folder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId)
                synchronized(observedParents) { observedParents.put(uri.toString(), parent) }
                val flags = if (flagsCol >= 0 && !cursor.isNull(flagsCol)) cursor.getInt(flagsCol) else 0
                val canWrite = if (folder) {
                    (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0 ||
                    (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                } else {
                    (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                }
                rows += ResourceEntry(
                    ResourceRef(id, uri.toString()),
                    ResourceMetadata(
                        name = name,
                        mimeType = mime,
                        sizeBytes = if (sizeCol < 0 || cursor.isNull(sizeCol)) null else cursor.getLong(sizeCol),
                        modifiedAtMillis = if (modifiedCol < 0 || cursor.isNull(modifiedCol)) null else cursor.getLong(modifiedCol),
                        isDirectory = folder,
                        canRead = true,
                        canWrite = canWrite,
                        operationHints = operationHints(flags, folder)
                    ),
                    uri
                )
            }
            directoryQueryResult(rows, cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false),
                cursor.extras.getString(DocumentsContract.EXTRA_ERROR), cursor.extras.getString(DocumentsContract.EXTRA_INFO))
        }
    } catch (e: SecurityException) { ProviderResult.Failure("Access to this folder was revoked", e) }
      catch (e: Exception) { ProviderResult.Failure(e.message ?: "Could not read folder", e) }

    override fun openRead(ref: ResourceRef) = try { ProviderResult.Success(resolver.openInputStream(Uri.parse(ref.documentId))!!) }
        catch (e: Exception) { ProviderResult.Failure(e.message ?: "Could not open file", e) }
    override fun openWrite(ref: ResourceRef) = try { ProviderResult.Success(resolver.openOutputStream(Uri.parse(ref.documentId), "wt")!!) }
        catch (e: Exception) { ProviderResult.Failure(e.message ?: "Could not write file", e) }
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR)
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = create(parent, name, mimeType)
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        StorageSafety.validateName(name)
        StorageFileSystems.forExternalDocument(Uri.parse(ref.documentId)).requireName(name)
        val renamed = DocumentsContract.renameDocument(resolver, Uri.parse(ref.documentId), name)
            ?: return ProviderResult.Failure("Provider did not rename the item")
        ProviderResult.Success(entry(renamed))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Rename failed", e) }
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        if (DocumentsContract.deleteDocument(resolver, Uri.parse(ref.documentId))) ProviderResult.Success(Unit)
        else ProviderResult.Failure("Provider refused to delete the item")
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Delete failed", e) }
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = false)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = true)

    @Synchronized
    private fun create(parent: ResourceRef, name: String, mime: String): ProviderResult<ResourceEntry> = try {
        StorageFileSystems.forExternalDocument(Uri.parse(parent.documentId)).requireName(name)
        StorageSafety.requireNewName(this, parent, name)
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent.documentId), mime, name)
            ?: return ProviderResult.Failure("Provider could not create this item")
        ProviderResult.Success(entry(uri))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Create failed", e) }
    private fun entry(uri: Uri): ResourceEntry {
        val idValue = documentId(uri)
        val query = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.buildDocumentUriUsingTree(uri, idValue) else uri
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        resolver.query(query, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "Unnamed"
                val mime = com.infinifolder.mime.MimeRegistry.effective(name, c.getString(1))
                val folder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val flags = if (c.columnCount > 4 && !c.isNull(4)) c.getInt(4) else 0
                val canWrite = if (folder) {
                    (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0 ||
                    (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                } else {
                    (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                }
                return ResourceEntry(
                    ResourceRef(id, uri.toString()),
                    ResourceMetadata(
                        name = name,
                        mimeType = mime,
                        sizeBytes = if (c.isNull(2)) null else c.getLong(2),
                        modifiedAtMillis = if (c.isNull(3)) null else c.getLong(3),
                        isDirectory = folder,
                        canRead = true,
                        canWrite = canWrite,
                        operationHints = operationHints(flags, folder)
                    ),
                    uri
                )
            }
        }
        throw FileNotFoundException("Created document is unavailable")
    }
    /** Return the selected document ID for descendants, or the root ID for a picker tree URI. */
    private fun documentId(uri: Uri): String = try {
        if (uri.pathSegments.firstOrNull() == "document" || uri.pathSegments.getOrNull(2) == "document") {
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: uri.lastPathSegment ?: uri.path.orEmpty()
        } else {
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: uri.lastPathSegment ?: uri.path.orEmpty()
        }
    } catch (_: Throwable) {
        uri.lastPathSegment ?: uri.path.orEmpty()
    }
}
