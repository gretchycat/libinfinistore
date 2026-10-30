package com.infinifolder.sftp

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector

class SftpStorageProvider(
    private val context: Context,
    val credentialStore: SftpCredentialStore = SftpCredentialStore(context),
    val clientPool: SftpClientPool = SftpClientPool(),
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProvider {

    override fun close() { clientPool.close() }
    override val id = "sftp"
    override val features = BackendFeatures(Support.SUPPORTED, Support.SUPPORTED, creation = CreationGuarantee.UNKNOWN, rename = Support.UNKNOWN, delete = Support.SUPPORTED, nativeLinks = Support.SUPPORTED,
        transferTarget = Support.SUPPORTED)


    override fun refForUri(uri: Uri): ResourceRef? = if (SftpUri.isSftpUri(uri)) ResourceRef(id, uri.toString()) else null
    override fun deviceIdentity(ref: ResourceRef): String? = runCatching {
        val uri = Uri.parse(ref.documentId)
        SftpUri.getConnectionId(uri).takeIf { it.isNotBlank() }?.let { "sftp:$it" }
    }.getOrNull()

    override fun capabilities(ref: ResourceRef): ProviderResult<ResourceCapabilities> = when (val result = stat(ref)) {
        is ProviderResult.Success -> try {
            val uri = Uri.parse(ref.documentId)
            val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
            val exclusive = clientPool.withChannel(config, creds) { it.getExtension("hardlink@openssh.com") == "1" }
            val meta = result.value.metadata
            ProviderResult.Success(ResourceCapabilities.from(meta, features, overrides = mapOf(
                ResourceCapability.CREATE to if (meta.isDirectory && meta.canWrite && exclusive) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.RENAME to if (SftpUri.getRemotePath(uri).trim('/').isNotEmpty() &&
                    ((meta.isDirectory && !meta.isSymbolicLink) || exclusive)) Support.SUPPORTED else Support.UNSUPPORTED),
                creation = if (exclusive) CreationGuarantee.EXCLUSIVE else CreationGuarantee.UNSUPPORTED))
        } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query server capabilities", e) }
        is ProviderResult.Failure -> result
        is ProviderResult.Unsupported -> result
    }
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = try {
        ProviderResult.Success(SftpUri.getParentUri(Uri.parse(ref.documentId))?.let { ResourceRef(id, it.toString()) })
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve parent", e) }
    override fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val path = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val target = clientPool.withChannel(config, creds) { it.readlink(path) }
        val resolved = if (target.startsWith('/')) target else path.substringBeforeLast('/', "") + "/" + target
        ProviderResult.Success(ResourceRef(id, SftpUri.build(connId, resolved).toString()))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve native link", e) }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> { return try {
        val uri = Uri.parse(ref.documentId)
        val path = SftpUri.getRemotePath(uri)
        if (path.trim('/').isEmpty()) {
            com.infinifolder.storage.StorageSafety.complete(list(ref))
            return ProviderResult.Success(ResourceEntry(ref, ResourceMetadata("/", "vnd.android.document/directory",
                null, null, true, operationHints = mapOf(ResourceCapability.DELETE to Support.UNSUPPORTED,
                    ResourceCapability.RENAME to Support.UNSUPPORTED, ResourceCapability.MOVE to Support.UNSUPPORTED)), uri))
        }
        val parentPath = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val parent = SftpUri.build(SftpUri.getConnectionId(uri), parentPath)
        val entry = com.infinifolder.storage.StorageSafety.complete(list(ResourceRef(id, parent.toString())))
            .firstOrNull { it.ref == ref } ?: throw IOException("Resource not found: $path")
        ProviderResult.Success(entry)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query remote resource", e) }
    }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<com.infinifolder.archive.BlockDevice> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
        val path = SftpUri.getRemotePath(uri)
        if (writable) {
            val attrs = clientPool.withChannel(config, creds) { it.lstat(path) }
            if (!attrs.isReg || attrs.isLink) throw IOException("Only regular SFTP files can be edited as image targets")
            ProviderResult.Success(com.infinifolder.storage.StagedRandomWriteDevice.open(this, ref,
                java.io.File(context.cacheDir, "image_working"), java.io.File(context.filesDir, "save_recovery")))
        } else {
            val size = clientPool.withChannel(config, creds) { it.stat(path).size }
            ProviderResult.Success(com.infinifolder.archive.RangeCachedBlockDevice(
                SftpBlockDevice(clientPool, config, creds, path, isReadOnly = true, initialSize = size)))
        }
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open remote random access", e) }

    /** Direct remote random-write target for new files; ordinary image edits remain staged. */
    override fun openTransferTarget(ref: ResourceRef): ProviderResult<com.infinifolder.archive.BlockDevice> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
        val path = SftpUri.getRemotePath(uri)
        val size = clientPool.withChannel(config, creds) { channel ->
            val attrs = channel.lstat(path)
            if (!attrs.isReg || attrs.isLink) throw IOException("Random-write transfer target must be a regular file")
            attrs.size
        }
        ProviderResult.Success(SftpBlockDevice(clientPool, config, creds, path, isReadOnly = false, initialSize = size))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open SFTP transfer target", e) }

    fun resolveConfigAndCredentials(connectionId: String): Pair<SftpConfig, SftpCredentials> {
        val conn = connectionLookup(connectionId)
            ?: throw IOException("Saved SFTP connection not found: $connectionId")
        val creds = credentialStore.load(connectionId)
            ?: throw IOException("Credentials not found for SFTP connection: ${conn.displayName}")

        val endpoint = conn.endpoint ?: "localhost:22"
        val host = conn.options["host"]?.ifEmpty { null }
            ?: (if (endpoint.contains(':')) endpoint.substringBefore(':').ifEmpty { "localhost" } else endpoint.ifEmpty { "localhost" })
        val port = conn.options["port"]?.toIntOrNull()
            ?: (if (endpoint.contains(':')) endpoint.substringAfter(':').toIntOrNull() else null) ?: 22
        val username = conn.options["username"] ?: "root"
        val initialPath = conn.options["initialPath"] ?: "/"

        val config = SftpConfig(
            id = conn.id,
            displayName = conn.displayName,
            host = host,
            port = port,
            username = username,
            initialPath = initialPath
        )
        return config to creds
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri = Uri.parse(parent.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)

        val entries = clientPool.withChannel(config, creds) { channel ->
            val list = mutableListOf<ResourceEntry>()
            @Suppress("UNCHECKED_CAST")
            val vector = channel.ls(remotePath) as Vector<ChannelSftp.LsEntry>
            val remoteSubject = try {
                val homeAttrs = channel.stat(channel.home)
                com.infinifolder.storage.SubjectIdentity(
                    uid = homeAttrs.uId,
                    username = config.username,
                    gid = homeAttrs.gId,
                    isRoot = config.username.equals("root", ignoreCase = true) || homeAttrs.uId == 0
                )
            } catch (_: Throwable) {
                com.infinifolder.storage.SubjectIdentity(
                    username = config.username,
                    isRoot = config.username.equals("root", ignoreCase = true)
                )
            }

            for (item in vector) {
                val filename = item.filename
                if (filename == "." || filename == "..") continue

                val attrs: SftpATTRS? = item.attrs
                val isDir = attrs?.isDir ?: false
                val sizeBytes = if (isDir) null else attrs?.size
                val mtime = (attrs?.mTime?.toLong() ?: 0L) * 1000L
                val childRemotePath = if (remotePath == "/") "/$filename" else "$remotePath/$filename"
                val childUri = SftpUri.build(connId, childRemotePath)
                val mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(filename)

                val isLink = attrs?.isLink == true
                var targetIsDir = isDir
                var targetSize = sizeBytes
                var linkTarget: String? = null
                var isBroken = false

                if (isLink) {
                    linkTarget = try { channel.readlink(childRemotePath) } catch (_: Throwable) { null }
                    try {
                        val statAttrs = channel.stat(childRemotePath)
                        targetIsDir = statAttrs.isDir
                        targetSize = if (targetIsDir) null else statAttrs.size
                    } catch (_: Throwable) {
                        isBroken = true
                    }
                }

                val finalIsDir = if (isLink && !isBroken) targetIsDir else isDir
                val finalMime = if (finalIsDir) "vnd.android.document/directory" else guessMimeType(filename)

                val permMode = attrs?.permissions
                val permString = attrs?.permissionsString
                val permFormatted = if (permMode != null) {
                    if (permString != null) "$permString (${String.format("%04o", permMode and 0xFFF)})"
                    else com.infinifolder.storage.PermissionHelper.formatPosix(permMode, finalIsDir)
                } else null
                val (canRead, canWrite, canExec) = if (isBroken) {
                    Triple(false, false, false)
                } else if (permMode != null) {
                    com.infinifolder.storage.PermissionHelper.evaluatePosixPermissions(
                        mode = permMode,
                        fileUid = attrs?.uId,
                        fileGid = attrs?.gId,
                        subject = remoteSubject
                    )
                } else {
                    Triple(true, true, false)
                }
                val owner = attrs?.uId?.takeIf { it >= 0 }?.toString()
                val group = attrs?.gId?.takeIf { it >= 0 }?.toString()

                list += ResourceEntry(
                    ref = ResourceRef(id, childUri.toString()),
                    metadata = ResourceMetadata(
                        name = filename,
                        mimeType = finalMime,
                        sizeBytes = if (finalIsDir) null else (if (isBroken) 0L else targetSize),
                        modifiedAtMillis = mtime,
                        isDirectory = finalIsDir,
                        canRead = canRead,
                        canWrite = canWrite,
                        canExecute = canExec,
                        permissionsMode = permMode,
                        permissionsFormatted = permFormatted,
                        owner = owner,
                        group = group,
                        isSymbolicLink = isLink,
                        linkTarget = linkTarget,
                        isBrokenLink = isBroken
                    ),
                    uri = childUri
                )
            }
            list.sortedWith(compareBy({ !it.metadata.isDirectory }, { it.metadata.name.lowercase() }))
        }
        ProviderResult.Success(entries)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to list remote directory", e)
    }

    fun getMetadata(uri: Uri): Pair<Long?, Long?>? = try {
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        clientPool.withChannel(config, creds) { channel ->
            val attrs = channel.stat(remotePath)
            val size = if (attrs.isDir) null else attrs.size
            val mtime = attrs.mTime.toLong() * 1000L
            Pair(size, mtime)
        }
    } catch (_: Throwable) {
        null
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val stream = clientPool.openStream(config, creds, remotePath)
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to read remote file", e)
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val stream = clientPool.openWriteStream(config, creds, remotePath)
        ProviderResult.Success(stream)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to write remote file", e)
    }

    @Synchronized
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remoteParent = SftpUri.getRemotePath(uri)
        val newRemotePath = if (remoteParent == "/") "/$name" else "$remoteParent/$name"
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            channel.mkdir(newRemotePath)
        }

        val childUri = SftpUri.build(connId, newRemotePath)
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
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create remote directory", e)
    }

    @Synchronized
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = try {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remoteParent = SftpUri.getRemotePath(uri)
        val newRemotePath = if (remoteParent == "/") "/$name" else "$remoteParent/$name"
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            if (channel.getExtension("hardlink@openssh.com") != "1") {
                throw IOException("This SFTP server cannot guarantee creation without replacing another client's file")
            }
            val reservation = (if (remoteParent == "/") "" else remoteParent) + "/.infinifolder-create-" + java.util.UUID.randomUUID()
            channel.mkdir(reservation)
            try {
                val temporary = "$reservation/content"
                channel.put(ByteArrayInputStream(ByteArray(0)), temporary, ChannelSftp.OVERWRITE)
                // Server-side hardlink creation fails when the final name already exists.
                channel.hardlink(temporary, newRemotePath)
            } finally {
                runCatching { channel.rm("$reservation/content") }
                runCatching { channel.rmdir(reservation) }
            }
        }

        val childUri = SftpUri.build(connId, newRemotePath)
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
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create remote file", e)
    }

    @Synchronized
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> {
        var stage = "checking destination name"
        return try {
        val uri = Uri.parse(ref.documentId)
        val renameParent = SftpUri.getParentUri(uri) ?: throw IOException("Cannot rename the connection root")
        com.infinifolder.storage.StorageSafety.requireNewName(this, ResourceRef(id, renameParent.toString()), name)
        val connId = SftpUri.getConnectionId(uri)
        val oldPath = SftpUri.getRemotePath(uri)
        val parentPath = oldPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            stage = "reading original attributes"
            val original = channel.lstat(oldPath)
            if (original.isDir && !original.isLink) {
                stage = "renaming directory"
                channel.rename(oldPath, newPath)
            } else {
                if (channel.getExtension("hardlink@openssh.com") != "1") throw IOException("Safe rename is unavailable on this server")
                stage = "creating final name without overwriting"
                channel.hardlink(oldPath, newPath)
                stage = "removing original name after linking"
                try { channel.rm(oldPath) } catch (e: Exception) { throw IOException("New link created, but original name could not be removed", e) }
            }
            stage = "reading renamed attributes"
            val stat = channel.stat(newPath)
            val isDir = stat.isDir
            val newUri = SftpUri.build(connId, newPath)
            ResourceEntry(
                ref = ResourceRef(id, newUri.toString()),
                metadata = ResourceMetadata(
                    name = name,
                    mimeType = if (isDir) "vnd.android.document/directory" else guessMimeType(name),
                    sizeBytes = if (isDir) null else stat.size,
                    modifiedAtMillis = stat.mTime.toLong() * 1000L,
                    isDirectory = isDir
                ),
                uri = newUri
            )
        }.let { ProviderResult.Success(it) }
    } catch (e: Exception) {
        val code = (e as? com.jcraft.jsch.SftpException)?.id?.let { " (SFTP code $it)" }.orEmpty()
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        ProviderResult.Failure("SFTP rename failed while $stage: $detail$code", e)
    }
    }

    @Synchronized
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val remotePath = SftpUri.getRemotePath(uri)
        require(remotePath != "/") { "Cannot delete the connection root" }
        val (config, creds) = resolveConfigAndCredentials(connId)

        clientPool.withChannel(config, creds) { channel ->
            val stat = channel.lstat(remotePath)
            if (stat.isDir && !stat.isLink) {
                deleteDirRecursive(channel, remotePath)
            } else {
                channel.rm(remotePath)
            }
        }
        ProviderResult.Success(Unit)
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to delete remote item", e)
    }

    private fun deleteDirRecursive(channel: ChannelSftp, path: String) {
        @Suppress("UNCHECKED_CAST")
        val vector = channel.ls(path) as Vector<ChannelSftp.LsEntry>
        for (item in vector) {
            val filename = item.filename
            if (filename == "." || filename == "..") continue
            val childPath = if (path == "/") "/$filename" else "$path/$filename"
            if (item.attrs.isDir && !item.attrs.isLink) {
                deleteDirRecursive(channel, childPath)
            } else {
                channel.rm(childPath)
            }
        }
        channel.rmdir(path)
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = false)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        com.infinifolder.storage.StorageTransfers.transfer(this, source, destination, move = true)

    private fun guessMimeType(fileName: String): String = com.infinifolder.mime.MimeRegistry.guess(fileName)
}
