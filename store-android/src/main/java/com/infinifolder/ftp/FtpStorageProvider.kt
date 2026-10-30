package com.infinifolder.ftp

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FtpStorageProvider(
    private val context: Context,
    val credentialStore: FtpCredentialStore = FtpCredentialStore(context),
    val clientPool: FtpClientPool = FtpClientPool(),
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProvider {

    override fun close() { clientPool.close() }
    override val id = "ftp"
    override val features = BackendFeatures(randomWrite = Support.UNSUPPORTED, creation = CreationGuarantee.SERVER_UNIQUE_NAME, rename = Support.UNSUPPORTED, delete = Support.SUPPORTED, nativeLinks = Support.UNKNOWN)


    override fun refForUri(uri: Uri): ResourceRef? = if (FtpUri.isFtpUri(uri)) ResourceRef(id, uri.toString()) else null
    override fun deviceIdentity(ref: ResourceRef): String? = runCatching {
        val uri = Uri.parse(ref.documentId)
        FtpUri.getConnectionId(uri).takeIf { it.isNotBlank() }?.let { "ftp:$it" }
    }.getOrNull()

    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = try {
        ProviderResult.Success(FtpUri.getParentUri(Uri.parse(ref.documentId))?.let { ResourceRef(id, it.toString()) })
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve parent", e) }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> { return try {
        val uri = Uri.parse(ref.documentId)
        val path = FtpUri.getRemotePath(uri)
        if (path.trim('/').isEmpty()) {
            com.infinifolder.storage.StorageSafety.complete(list(ref))
            return ProviderResult.Success(ResourceEntry(ref, ResourceMetadata("/", "vnd.android.document/directory",
                null, null, true, operationHints = mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED,
                    ResourceCapability.RENAME to Support.UNSUPPORTED, ResourceCapability.MOVE to Support.UNSUPPORTED)), uri))
        }
        val parentPath = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val parent = FtpUri.build(FtpUri.getConnectionId(uri), parentPath)
        val entry = com.infinifolder.storage.StorageSafety.complete(list(ResourceRef(id, parent.toString())))
            .firstOrNull { it.ref == ref } ?: throw IOException("Resource not found: $path")
        ProviderResult.Success(entry)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query remote resource", e) }
    }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<com.infinifolder.archive.BlockDevice> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(FtpUri.getConnectionId(uri))
        val path = FtpUri.getRemotePath(uri)
        if (writable) ProviderResult.Unsupported(ResourceCapability.SEEK_WRITE) else {
            val size = getMetadata(uri)?.first ?: throw IOException("Cannot determine remote file size")
            val supportsRestart = clientPool.withClient(config, creds) { it.sendCommand("REST", "0") == 350 }
            if (!supportsRestart) ProviderResult.Unsupported(ResourceCapability.SEEK) else
                ProviderResult.Success(com.infinifolder.archive.RangeCachedBlockDevice(FtpBlockDevice(clientPool, config, creds, path, size)))
        }
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open remote random access", e) }

    fun resolveConfigAndCredentials(connectionId: String): Pair<FtpConfig, FtpCredentials> {
        val conn = connectionLookup(connectionId)
            ?: throw IOException("Saved FTP connection not found: $connectionId")
        val creds = credentialStore.load(connectionId) ?: FtpCredentials()

        val endpoint = conn.endpoint ?: "localhost:21"
        val host = conn.options["host"]?.ifEmpty { null }
            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
        val port = conn.options["port"]?.toIntOrNull()
            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 21
        val username = conn.options["username"] ?: "anonymous"
        val isAnonymous = conn.options["isAnonymous"]?.toBooleanStrictOrNull() ?: (username == "anonymous")
        val initialPath = conn.options["initialPath"] ?: "/"
        val useFtps = conn.options["useFtps"]?.toBooleanStrictOrNull() ?: false
        val passiveMode = conn.options["passiveMode"]?.toBooleanStrictOrNull() ?: true
        val encoding = conn.options["encoding"] ?: "UTF-8"

        val config = FtpConfig(
            id = conn.id,
            displayName = conn.displayName,
            host = host,
            port = port,
            username = username,
            isAnonymous = isAnonymous,
            initialPath = initialPath,
            useFtps = useFtps,
            passiveMode = passiveMode,
            encoding = encoding
        )
        return config to creds
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val entries = clientPool.withClient(config, creds) { client ->
            val list = mutableListOf<ResourceEntry>()
            val files: Array<FTPFile> = client.listFiles(remotePath) ?: throw IOException("FTP listing returned no result")
            if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IOException("FTP listing failed: ${client.safeReplyString()}")
            }

            for (item in files) {
                var filename = item.name ?: continue
                if (filename.contains('/')) {
                    filename = filename.substringAfterLast('/')
                }
                if (filename == "." || filename == ".." || filename.isEmpty()) continue

                val isDir = item.isDirectory
                val sizeBytes = if (isDir) null else item.size
                val mtime = item.timestamp?.timeInMillis ?: 0L
                val childRemotePath = if (remotePath == "/") "/$filename" else "$remotePath/$filename"
                val childUri = FtpUri.build(connId, childRemotePath)
                val mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(filename)

                val loggedInUser = config.username.ifEmpty { "anonymous" }
                val owner = try { item.user?.ifEmpty { null } } catch (_: Throwable) { null }
                val group = try { item.group?.ifEmpty { null } } catch (_: Throwable) { null }

                val isOwner = loggedInUser.equals(owner, ignoreCase = true) || loggedInUser == "root"
                val isGroup = loggedInUser.equals(group, ignoreCase = true)

                val canRead = try {
                    when {
                        isOwner -> item.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION)
                        isGroup -> item.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.READ_PERMISSION)
                        else -> item.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.READ_PERMISSION)
                    }
                } catch (_: Throwable) { true }

                val canWrite = try {
                    when {
                        isOwner -> item.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION)
                        isGroup -> item.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.WRITE_PERMISSION)
                        else -> item.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.WRITE_PERMISSION)
                    }
                } catch (_: Throwable) { true }

                val canExec = try {
                    when {
                        isOwner -> item.hasPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION)
                        isGroup -> item.hasPermission(FTPFile.GROUP_ACCESS, FTPFile.EXECUTE_PERMISSION)
                        else -> item.hasPermission(FTPFile.WORLD_ACCESS, FTPFile.EXECUTE_PERMISSION)
                    }
                } catch (_: Throwable) { false }

                list += ResourceEntry(
                    ref = ResourceRef(id, childUri.toString()),
                    metadata = ResourceMetadata(
                        name = filename,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        modifiedAtMillis = mtime,
                        isDirectory = isDir,
                        canRead = canRead,
                        canWrite = canWrite,
                        canExecute = canExec,
                        owner = owner,
                        group = group,
                        isSymbolicLink = item.isSymbolicLink,
                        linkTarget = item.link
                    ),
                    uri = childUri
                )
            }
            list.sortedWith(compareBy({ !it.metadata.isDirectory }, { it.metadata.name.lowercase() }))
        }
        ProviderResult.Success(entries)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to list FTP remote directory", e)
    }

    fun getMetadata(uri: Uri): Pair<Long?, Long?>? = try {
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        clientPool.withClient(config, creds) { client ->
            val files = client.listFiles(remotePath)
            val file = files?.firstOrNull()
            if (file != null) {
                val size = if (file.isDirectory) null else file.size
                val mtime = file.timestamp?.timeInMillis
                size to mtime
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        // Use a dedicated client connection for this stream so it doesn't block command pool
        val client = FtpClientPool().getClient(config, creds)
        val stream = client.retrieveFileStream(remotePath)
            ?: throw IOException("Failed to retrieve file stream: ${client.safeReplyString()}")

        val wrapped = object : FilterInputStream(stream) {
            private var closed = false
            override fun close() {
                if (!closed) {
                    closed = true
                    try { super.close() } finally {
                        try {
                            if (!client.completePendingCommand()) throw IOException("FTP transfer did not complete: ${client.safeReplyString()}")
                        } finally { try { client.disconnect() } catch (_: Exception) {} }
                    }
                }
            }
        }
        ProviderResult.Success(wrapped)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open FTP file for reading", e)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val client = FtpClientPool().getClient(config, creds)
        val stream = client.storeFileStream(remotePath)
            ?: throw IOException("Failed to store file stream: ${client.safeReplyString()}")

        val wrapped = object : FilterOutputStream(stream) {
            private var closed = false
            override fun close() {
                if (!closed) {
                    closed = true
                    try { super.close() } finally {
                        try {
                            if (!client.completePendingCommand()) throw IOException("FTP transfer did not complete: ${client.safeReplyString()}")
                        } finally { try { client.disconnect() } catch (_: Exception) {} }
                    }
                }
            }
        }
        ProviderResult.Success(wrapped)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to open FTP file for writing", e)
    }

    @Synchronized
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val childRemotePath = if (remotePath == "/") "/$name" else "$remotePath/$name"
        val childUri = FtpUri.build(connId, childRemotePath)

        clientPool.withClient(config, creds) { client ->
            val ok = client.makeDirectory(childRemotePath)
            if (!ok) throw IOException("FTP server failed to create directory: ${client.safeReplyString()}")
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
        }.let { ProviderResult.Success(it) }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create remote directory", e)
    }

    @Synchronized
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = try {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        // FTP STOR can replace a racing client's file. STOU delegates uniqueness to the server.
        val requestName = "infinifolder_${java.util.UUID.randomUUID()}_$name"
        clientPool.withClient(config, creds) { client ->
            val actualName = if (client.storeUniqueFile(
                    remoteFilePath(remotePath, requestName),
                    java.io.ByteArrayInputStream(ByteArray(0))
                )) {
                val matches = listFilesChecked(client, remotePath)
                    .filter { it.name.startsWith(requestName) && it.isFile }
                if (matches.size != 1) {
                    throw IOException("FTP created a unique file but its name could not be confirmed; original files were retained")
                }
                matches.single().name
            } else {
                val replyCode = client.replyCode
                if (replyCode !in setOf(500, 502, 504)) {
                    throw IOException("FTP server cannot safely create a unique file: ${client.safeReplyString()}")
                }
                com.infinifolder.storage.DiagnosticLog.record(
                    "ftp.create.stou-unsupported",
                    "replyCode=$replyCode name=$name; using a checked random STOR name"
                )
                createCheckedFallbackFile(client, remotePath, requestName, name)
            }
            val childUri = FtpUri.build(connId, remoteFilePath(remotePath, actualName))
            ResourceEntry(
                ref = ResourceRef(id, childUri.toString()),
                metadata = ResourceMetadata(
                    name = actualName,
                    mimeType = mimeType.ifEmpty { guessMimeType(name) },
                    sizeBytes = 0L,
                    modifiedAtMillis = System.currentTimeMillis(),
                    isDirectory = false
                ),
                uri = childUri
            )
        }.let { ProviderResult.Success(it) }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to create remote file", e)
    }

    /**
     * Some FTP servers do not implement STOU. STOR is only used with an app-generated random
     * name after confirming it is absent. FTP has no standard atomic exclusive-create operation
     * in this case, so a server-side client racing the check remains outside our control.
     */
    private fun createCheckedFallbackFile(
        client: FTPClient,
        parentPath: String,
        initialName: String,
        originalName: String
    ): String {
        var candidate = initialName
        for (attempt in 0 until 5) {
            val entries = listFilesChecked(client, parentPath)
            if (entries.any { it.name.equals(candidate, ignoreCase = true) }) {
                candidate = "infinifolder_${java.util.UUID.randomUUID()}_$originalName"
                continue
            }

            val path = remoteFilePath(parentPath, candidate)
            val created = client.storeFile(path, java.io.ByteArrayInputStream(ByteArray(0)))
            if (created) {
                val confirmed = listFilesChecked(client, parentPath).any {
                    it.name.equals(candidate, ignoreCase = true) && it.isFile
                }
                if (!confirmed) {
                    throw IOException("FTP created a fallback file but its name could not be confirmed")
                }
                return candidate
            }

            val reply = client.safeReplyString()
            if (listFilesChecked(client, parentPath).any { it.name.equals(candidate, ignoreCase = true) }) {
                candidate = "infinifolder_${java.util.UUID.randomUUID()}_$originalName"
                continue
            }
            throw IOException("FTP server rejected the checked fallback file: $reply")
        }
        throw IOException("FTP could not find an unused fallback name after several attempts")
    }

    private fun listFilesChecked(client: FTPClient, path: String): List<FTPFile> {
        val entries = client.listFiles(path)
            ?: throw IOException("FTP listing returned no result while checking for a unique filename")
        if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(client.replyCode)) {
            throw IOException("FTP listing failed while checking for a unique filename: ${client.safeReplyString()}")
        }
        return entries.toList()
    }

    private fun remoteFilePath(parentPath: String, name: String): String =
        (if (parentPath == "/") "" else parentPath.trimEnd('/')) + "/$name"

    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.RENAME)

    @Synchronized
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = FtpUri.getConnectionId(uri)
        val remotePath = FtpUri.getRemotePath(uri)
        require(remotePath != "/") { "Cannot delete the connection root" }
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withClient(config, creds) { client ->
            // Try deleting as file first; if failed, try removing directory
            var ok = client.deleteFile(remotePath)
            if (!ok) {
                ok = client.removeDirectory(remotePath)
            }
            if (!ok) {
                throw IOException("FTP server failed to delete '$remotePath': ${client.safeReplyString()}")
            }
            ProviderResult.Success(Unit)
        }
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to delete FTP item", e)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = false)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = true)

    private fun guessMimeType(fileName: String): String = com.infinifolder.mime.MimeRegistry.guess(fileName)
}
