package com.infinifolder.smb

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import jcifs.smb.SmbFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SmbStorageProvider(
    private val context: Context,
    val credentialStore: SmbCredentialStore = SmbCredentialStore(context),
    val clientPool: SmbClientPool = SmbClientPool(),
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProvider {

    override fun close() { clientPool.close() }
    override val id = "smb"
    override val features = BackendFeatures(Support.SUPPORTED, Support.SUPPORTED, creation = CreationGuarantee.EXCLUSIVE, rename = Support.SUPPORTED, delete = Support.SUPPORTED,
        transferTarget = Support.SUPPORTED)


    override fun refForUri(uri: Uri): ResourceRef? = if (SmbUri.isSmbUri(uri)) ResourceRef(id, uri.toString()) else null
    override fun deviceIdentity(ref: ResourceRef): String? = runCatching {
        val uri = Uri.parse(ref.documentId)
        SmbUri.getConnectionId(uri).takeIf { it.isNotBlank() }?.let { "smb:$it" }
    }.getOrNull()

    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = try {
        ProviderResult.Success(SmbUri.getParentUri(Uri.parse(ref.documentId))?.let { ResourceRef(id, it.toString()) })
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve parent", e) }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> { return try {
        val uri = Uri.parse(ref.documentId)
        val path = SmbUri.getRemotePath(uri)
        if (path.trim('/').isEmpty()) {
            com.infinifolder.storage.StorageSafety.complete(list(ref))
            return ProviderResult.Success(ResourceEntry(ref, ResourceMetadata("/", "vnd.android.document/directory",
                null, null, true, operationHints = mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED,
                    ResourceCapability.RENAME to Support.UNSUPPORTED, ResourceCapability.MOVE to Support.UNSUPPORTED)), uri))
        }
        // A known share must be accessible without permission to enumerate the server.
        // Query the target itself rather than listing its parent (which also scans large folders).
        val (config, creds) = resolveConfigAndCredentials(SmbUri.getConnectionId(uri))
        val file = clientPool.getSmbFile(config, creds, path)
        if (!file.exists()) throw IOException("Resource not found: $path")
        val isDirectory = file.isDirectory
        val isShare = path.trim('/').split('/').size == 1
        ProviderResult.Success(ResourceEntry(ref, ResourceMetadata(
            name = path.trimEnd('/').substringAfterLast('/'),
            mimeType = if (isDirectory) "vnd.android.document/directory" else guessMimeType(file.name),
            sizeBytes = if (isDirectory) null else file.length(),
            modifiedAtMillis = runCatching { file.lastModified() }.getOrNull(),
            isDirectory = isDirectory,
            canRead = runCatching { file.canRead() }.getOrDefault(true),
            canWrite = runCatching { file.canWrite() }.getOrDefault(true),
            operationHints = if (isShare) mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED,
                ResourceCapability.RENAME to Support.UNSUPPORTED, ResourceCapability.MOVE to Support.UNSUPPORTED)
                else emptyMap()
        ), uri))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query remote resource", e) }
    }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<com.infinifolder.archive.BlockDevice> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(SmbUri.getConnectionId(uri))
        val path = SmbUri.getRemotePath(uri)
        val file = clientPool.getSmbFile(config, creds, path)
        if (!file.exists() || file.isDirectory) throw IOException("Not a regular file")
        ProviderResult.Success(com.infinifolder.archive.RangeCachedBlockDevice(SmbBlockDevice(file, !writable)))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open remote random access", e) }

    fun resolveConfigAndCredentials(connectionId: String): Pair<SmbConfig, SmbCredentials> {
        val conn = connectionLookup(connectionId)
            ?: throw IOException("Saved SMB connection not found: $connectionId")
        val creds = credentialStore.load(connectionId) ?: SmbCredentials()

        val endpoint = conn.endpoint ?: "localhost:445"
        val host = conn.options["host"]?.ifEmpty { null }
            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
        val port = conn.options["port"]?.toIntOrNull()
            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 445
        val domain = conn.options["domain"] ?: "WORKGROUP"
        val username = conn.options["username"] ?: ""
        val isGuest = conn.options["isGuest"]?.toBooleanStrictOrNull() ?: false
        val initialShare = conn.options["initialShare"]?.ifEmpty { null }
        val initialPath = conn.options["initialPath"] ?: "/"

        val config = SmbConfig(
            id = conn.id,
            displayName = conn.displayName,
            host = host,
            port = port,
            domain = domain,
            username = username,
            isGuest = isGuest,
            initialShare = initialShare,
            initialPath = initialPath
        )
        return config to creds
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
        var remotePath = "/"
        return try {
            val uri = Uri.parse(parent.documentId)
            val connId = SmbUri.getConnectionId(uri)
            remotePath = SmbUri.getRemotePath(uri)
            val (config, creds) = resolveConfigAndCredentials(connId)

            if (remotePath == "/" && !config.initialShare.isNullOrBlank()) {
                remotePath = "/${config.initialShare.trim('/')}"
            }

            val smbFile = clientPool.getSmbFile(config, creds, remotePath, isDirectory = true)
            val files = smbFile.listFiles() ?: emptyArray()
            val list = mutableListOf<ResourceEntry>()

            for (item in files) {
                var filename = item.name.trimEnd('/')
                if (filename.contains('/')) {
                    filename = filename.substringAfterLast('/')
                }
                if (filename.isEmpty() || filename == "." || filename == "..") continue
                // When at server root, hide IPC$ or administrative pipe shares by default
                if ((remotePath == "/" || remotePath.isEmpty()) && filename.equals("IPC$", ignoreCase = true)) continue

                val isDir = item.isDirectory
                val sizeBytes = if (isDir) null else try { item.length() } catch (_: Throwable) { null }
                val mtime = try { item.lastModified() } catch (_: Throwable) { 0L }
                val normalizedParent = remotePath.trimEnd('/')
                val childRemotePath = if (normalizedParent.isEmpty() || normalizedParent == "/") "/$filename" else "$normalizedParent/$filename"
                val childUri = SmbUri.build(connId, childRemotePath)
                val mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(filename)

                val canRead = try { item.canRead() } catch (_: Throwable) { true }
                val canWrite = try { item.canWrite() } catch (_: Throwable) { true }

                list += ResourceEntry(
                    ref = ResourceRef(id, childUri.toString()),
                    metadata = ResourceMetadata(
                        name = filename,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        modifiedAtMillis = mtime,
                        isDirectory = isDir,
                        canRead = canRead,
                        canWrite = canWrite
                    ),
                    uri = childUri
                )
            }
        list.sortedWith(compareBy({ !it.metadata.isDirectory }, { it.metadata.name.lowercase() }))
        ProviderResult.Success(list)
    } catch (e: Throwable) {
        val msg = e.message ?: ""
        val errorText = if (remotePath == "/" && (msg.contains("0xC000020C") || msg.contains("0xC0000022") || msg.contains("denied", ignoreCase = true) || msg.contains("refused", ignoreCase = true))) {
            "This server restricts public share listing ($msg). Please long-press this server on Home, tap 'Edit connection', and specify a Share name."
        } else {
            msg.ifEmpty { "Failed to list SMB shares or folders" }
        }
        ProviderResult.Failure(errorText, e)
    }
}

    fun getMetadata(uri: Uri): Pair<Long?, Long?>? = try {
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        if (smbFile.exists()) {
            val size = if (smbFile.isDirectory) null else smbFile.length()
            val mtime = smbFile.lastModified()
            size to mtime
        } else {
            val dirFile = clientPool.getSmbFile(config, creds, remotePath, isDirectory = true)
            if (dirFile.exists()) {
                val size = if (dirFile.isDirectory) null else dirFile.length()
                val mtime = dirFile.lastModified()
                size to mtime
            } else null
        }
    } catch (_: Throwable) {
        null
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        val stream = smbFile.openInputStream()
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open SMB file for reading", e)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        val stream = smbFile.openOutputStream()
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open SMB file for writing", e)
    }

    @Synchronized
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val childRemotePath = if (remotePath == "/") "/$name" else "$remotePath/$name"
        val childUri = SmbUri.build(connId, childRemotePath)
        val smbFile = clientPool.getSmbFile(config, creds, childRemotePath, isDirectory = true)
        smbFile.mkdir()

        ProviderResult.Success(
            ResourceEntry(
                ref = ResourceRef(id, childUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = "vnd.android.document/directory",
                    sizeBytes = null,
                    modifiedAtMillis = System.currentTimeMillis(),
                    isDirectory = true
                ),
                uri = childUri
            )
        )
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create SMB directory", e)
    }

    @Synchronized
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = try {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val childRemotePath = if (remotePath == "/") "/$name" else "$remotePath/$name"
        val childUri = SmbUri.build(connId, childRemotePath)
        val smbFile = clientPool.getSmbFile(config, creds, childRemotePath, isDirectory = false)
        // createNewFile() in this client uses OPEN_IF; request exclusive creation explicitly.
        smbFile.openOutputStream(false, jcifs.SmbConstants.O_WRONLY or jcifs.SmbConstants.O_CREAT or jcifs.SmbConstants.O_EXCL,
            jcifs.SmbConstants.FILE_WRITE_DATA, jcifs.SmbConstants.FILE_NO_SHARE).close()

        ProviderResult.Success(
            ResourceEntry(
                ref = ResourceRef(id, childUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = mimeType.ifEmpty { guessMimeType(name) },
                    sizeBytes = 0L,
                    modifiedAtMillis = System.currentTimeMillis(),
                    isDirectory = false
                ),
                uri = childUri
            )
        )
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create SMB file", e)
    }

    @Synchronized
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(ref.documentId)
        val renameParent = SmbUri.getParentUri(uri) ?: throw IOException("Cannot rename the connection root")
        com.infinifolder.storage.StorageSafety.requireNewName(this, ResourceRef(id, renameParent.toString()), name)
        val connId = SmbUri.getConnectionId(uri)
        val oldPath = SmbUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val parentPath = oldPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
        val newUri = SmbUri.build(connId, newPath)

        val srcFile = clientPool.getSmbFile(config, creds, oldPath)
        val destFile = clientPool.getSmbFile(config, creds, newPath)
        srcFile.renameTo(destFile)

        val isDir = destFile.isDirectory
        val sizeBytes = if (isDir) null else destFile.length()
        val mtime = destFile.lastModified()

        ProviderResult.Success(
            ResourceEntry(
                ref = ResourceRef(id, newUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(name),
                    sizeBytes = sizeBytes,
                    modifiedAtMillis = mtime,
                    isDirectory = isDir
                ),
                uri = newUri
            )
        )
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to rename SMB item", e)
    }

    @Synchronized
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SmbUri.getConnectionId(uri)
        val remotePath = SmbUri.getRemotePath(uri)
        require(remotePath != "/") { "Cannot delete the connection root" }
        val (config, creds) = resolveConfigAndCredentials(connId)

        val smbFile = clientPool.getSmbFile(config, creds, remotePath)
        if (smbFile.exists()) {
            smbFile.delete()
        } else {
            val dirFile = clientPool.getSmbFile(config, creds, remotePath, isDirectory = true)
            dirFile.delete()
        }
        ProviderResult.Success(Unit)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to delete SMB item", e)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = false)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = true)

    private fun guessMimeType(fileName: String): String = com.infinifolder.mime.MimeRegistry.guess(fileName)
}
