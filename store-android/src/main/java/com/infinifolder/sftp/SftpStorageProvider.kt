package com.infinifolder.sftp

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
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
) : StorageProvider, com.infinifolder.storage.NativeRandomAccessProvider {

    override fun close() {
        try { clientPool.close() } finally { synchronized(remoteSubjects) { remoteSubjects.clear() } }
    }
    private val remoteSubjects = java.util.WeakHashMap<ChannelSftp, com.infinifolder.storage.SubjectIdentity>()
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
            val exclusive = clientPool.withReadChannel(config, creds) { it.getExtension("hardlink@openssh.com") == "1" }
            val meta = result.value.metadata
            ProviderResult.Success(ResourceCapabilities.from(meta, features, overrides = mapOf(
                ResourceCapability.CREATE to if (meta.isDirectory && meta.canWrite && exclusive) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.RENAME to if (SftpUri.getRemotePath(uri).trim('/').isNotEmpty())
                    Support.SUPPORTED else Support.UNSUPPORTED),
                creation = if (exclusive) CreationGuarantee.EXCLUSIVE else CreationGuarantee.UNSUPPORTED))
        } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query server capabilities", e) }
        is ProviderResult.Failure -> result
        is ProviderResult.Unsupported -> result
    }

    override fun readTransferMetadata(ref: ResourceRef): ProviderResult<TransferMetadata> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
        val metadata = clientPool.withReadChannel(config, creds) { channel ->
            val attrs = channel.lstat(literalPath(SftpUri.getRemotePath(uri)))
            TransferMetadata(
                mode = attrs.permissions and 0xFFF,
                uid = attrs.uId.toLong().takeIf { attrs.uId >= 0 },
                gid = attrs.gId.toLong().takeIf { attrs.gId >= 0 },
                modifiedSeconds = attrs.mTime.toLong() and 0xffffffffL,
                accessedSeconds = attrs.aTime.toLong() and 0xffffffffL
            )
        }
        ProviderResult.Success(metadata)
    } catch (error: Exception) {
        ProviderResult.Failure(error.message ?: "Cannot read SFTP file metadata", error)
    }

    override fun applyTransferMetadata(ref: ResourceRef, metadata: TransferMetadata): ProviderResult<Unit> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
        clientPool.withChannel(config, creds) { channel ->
            val attrs = SftpATTRS()
            var hasFields = false
            val path = literalPath(SftpUri.getRemotePath(uri))
            val current = channel.lstat(path)
            metadata.mode?.takeIf { (current.permissions and 0xFFF) != it }?.let { attrs.setPERMISSIONS(it); hasFields = true }
            val uid = metadata.uid
            val gid = metadata.gid
            val accessedSeconds = metadata.accessedSeconds
            val modifiedSeconds = metadata.modifiedSeconds
            if (uid != null && gid != null &&
                (current.uId.toLong() != uid || current.gId.toLong() != gid)) {
                attrs.setUIDGID(uid.toInt(), gid.toInt())
                hasFields = true
            }
            if (accessedSeconds != null && modifiedSeconds != null &&
                ((current.aTime.toLong() and 0xffffffffL) != accessedSeconds ||
                    (current.mTime.toLong() and 0xffffffffL) != modifiedSeconds)) {
                attrs.setACMODTIME(accessedSeconds.toInt(), modifiedSeconds.toInt())
                hasFields = true
            }
            if (!hasFields) return@withChannel
            channel.setStat(path, attrs)
        }
        ProviderResult.Success(Unit)
    } catch (error: Exception) {
        ProviderResult.Failure(error.message ?: "Cannot set SFTP file metadata", error)
    }

    override fun transferCapabilities(request: com.infinifolder.storage.StorageTransferRequest): ProviderResult<com.infinifolder.storage.StorageTransferCapabilities> {
        return try {
        if (request.source.providerId != id || request.destinationParent.providerId != id)
            return ProviderResult.Success(com.infinifolder.storage.StorageTransferCapabilities())
        val sourceUri = Uri.parse(request.source.documentId)
        val parentUri = Uri.parse(request.destinationParent.documentId)
        val connectionId = SftpUri.getConnectionId(sourceUri)
        if (connectionId.isBlank() || connectionId != SftpUri.getConnectionId(parentUri))
            return ProviderResult.Success(com.infinifolder.storage.StorageTransferCapabilities())
        val sourcePath = SftpUri.getRemotePath(sourceUri)
        val parentPath = SftpUri.getRemotePath(parentUri)
        if (sourcePath.trim('/').isEmpty())
            return ProviderResult.Success(com.infinifolder.storage.StorageTransferCapabilities(sameBackendSession = true))
        val (config, creds) = resolveConfigAndCredentials(connectionId)
        val caps = clientPool.withReadChannel(config, creds) { channel ->
            val sourceAttrs = channel.lstat(literalPath(sourcePath))
            val parentAttrs = channel.stat(literalPath(parentPath))
            if (!parentAttrs.isDir) throw IOException("SFTP transfer destination is not a directory")
            var sameFilesystem: Boolean? = null
            if (channel.getExtension("statvfs@openssh.com") == "2") {
                try {
                    val sourceFsPath = if (sourceAttrs.isLink) sourcePath.substringBeforeLast('/', "").ifEmpty { "/" } else sourcePath
                    val sourceFs = channel.statVFS(literalPath(sourceFsPath)).fileSystemID
                    val destinationFs = channel.statVFS(literalPath(parentPath)).fileSystemID
                    if (sourceFs != 0L && destinationFs != 0L) sameFilesystem = sourceFs == destinationFs
                } catch (error: Exception) {
                    if (!optionalMetadataUnavailable(error)) throw error
                }
            }
            val copyVersion = channel.getExtension("copy-data")
            val posixRename = channel.getExtension("posix-rename@openssh.com") == "1"
            com.infinifolder.storage.StorageTransferCapabilities(
                sameBackendSession = true,
                sameFilesystem = sameFilesystem,
                nativeMove = sourcePath != "/" && sameFilesystem != false,
                serverSideCopy = copyVersion == "1" && sourceAttrs.isReg && !sourceAttrs.isLink,
                // We issue the v3 no-overwrite RENAME request even when this extension exists.
                // The extension is reported as an atomic capability only when the server
                // supplied filesystem identity confirms a single filesystem.
                atomicRename = posixRename && sameFilesystem == true
            )
        }
        ProviderResult.Success(caps)
        } catch (error: Exception) {
            ProviderResult.Failure(error.message ?: "Cannot query SFTP transfer capabilities", error)
        }
    }

    override fun nativeMove(
        request: com.infinifolder.storage.StorageTransferRequest,
        isCancelled: () -> Boolean
    ): ProviderResult<ResourceEntry> {
        var connectionIdForReconcile: String? = null
        var sourcePathForReconcile: String? = null
        var destinationPathForReconcile: String? = null
        var connectionForReconcile: Pair<SftpConfig, SftpCredentials>? = null
        var originalForReconcile: SftpATTRS? = null
        var renameAttempted = false
        return try {
            if (request.source.providerId != id || request.destinationParent.providerId != id)
                return ProviderResult.Unsupported(ResourceCapability.MOVE)
            com.infinifolder.storage.StorageSafety.validateName(request.destinationName)
            val sourceUri = Uri.parse(request.source.documentId)
            val parentUri = Uri.parse(request.destinationParent.documentId)
            val connectionId = SftpUri.getConnectionId(sourceUri)
            if (connectionId.isBlank() || connectionId != SftpUri.getConnectionId(parentUri))
                return ProviderResult.Unsupported(ResourceCapability.MOVE)
            val sourcePath = SftpUri.getRemotePath(sourceUri)
            if (sourcePath == "/") return ProviderResult.Unsupported(ResourceCapability.MOVE)
            val destinationParent = SftpUri.getRemotePath(parentUri)
            val destinationPath = if (destinationParent == "/") "/${request.destinationName}" else "$destinationParent/${request.destinationName}"
            connectionIdForReconcile = connectionId
            sourcePathForReconcile = sourcePath
            destinationPathForReconcile = destinationPath
            val caps = transferCapabilities(request)
            if (caps !is ProviderResult.Success || !caps.value.nativeMove)
                return if (caps is ProviderResult.Failure) caps else ProviderResult.Unsupported(ResourceCapability.MOVE)
            val (config, creds) = resolveConfigAndCredentials(connectionId)
            connectionForReconcile = config to creds
            val moved = clientPool.withChannel(config, creds) { channel ->
                com.infinifolder.archive.RecoveryCancellation.check()
                if (isCancelled()) throw java.io.InterruptedIOException("SFTP move cancelled")
                val original = channel.lstat(literalPath(sourcePath))
                originalForReconcile = original
                try {
                    channel.lstat(literalPath(destinationPath))
                    throw java.nio.file.FileAlreadyExistsException(request.destinationName)
                } catch (error: SftpException) {
                    if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
                }
                renameAttempted = true
                SftpRawProtocol.renameNoReplace(channel, sourcePath, destinationPath, isCancelled)
                val finalAttrs = channel.lstat(literalPath(destinationPath))
                val sourceStillExists = try { channel.lstat(literalPath(sourcePath)); true }
                    catch (error: SftpException) { if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw error }
                if (sourceStillExists) throw IOException("SFTP rename returned success but the source name still exists")
                if (!sameMoveAttributes(finalAttrs, original))
                    throw IOException("SFTP rename completed, but destination attributes did not match the source")
                val finalUri = SftpUri.build(connectionId, destinationPath)
                resourceEntry(connectionId, channel, destinationPath, request.destinationName, finalAttrs,
                    remoteSubject(channel, config)).copy(ref = ResourceRef(id, finalUri.toString()), uri = finalUri)
            }
            ProviderResult.Success(moved)
        } catch (error: Exception) {
            val connection = connectionForReconcile
            val connectionId = connectionIdForReconcile
            val sourcePath = sourcePathForReconcile
            val destinationPath = destinationPathForReconcile
            val expected = originalForReconcile
            if (renameAttempted && error !is SftpRawProtocol.StatusFailure && connection != null &&
                connectionId != null && sourcePath != null && destinationPath != null && expected != null) {
                val reconciled = runCatching {
                    com.infinifolder.archive.RecoveryCancellation.withoutCancellation {
                        clientPool.withReadChannel(connection.first, connection.second) { channel ->
                            val sourceExists = try { channel.lstat(literalPath(sourcePath)); true }
                                catch (missing: SftpException) {
                                    if (missing.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw missing
                                }
                            if (sourceExists) return@withReadChannel null
                            val destinationAttrs = channel.lstat(literalPath(destinationPath))
                            if (!sameMoveAttributes(destinationAttrs, expected)) return@withReadChannel null
                            val uri = SftpUri.build(connectionId, destinationPath)
                            resourceEntry(connectionId, channel, destinationPath, request.destinationName, destinationAttrs,
                                remoteSubject(channel, connection.first)).copy(ref = ResourceRef(id, uri.toString()), uri = uri)
                        }
                    }
                }
                val recovered = reconciled.getOrNull()
                if (recovered != null) {
                    com.infinifolder.storage.DiagnosticLog.record("sftp.native-move.reconciled",
                        "requestReplyLost=true destination=${request.destinationParent.providerId}")
                    return ProviderResult.Success(recovered, notice = "Rename completed; its SFTP reply was interrupted")
                }
                reconciled.exceptionOrNull()?.let(error::addSuppressed)
            }
            ProviderResult.Failure(error.message ?: "SFTP native move failed", error)
        }
    }

    private fun sameMoveAttributes(actual: SftpATTRS, expected: SftpATTRS): Boolean =
        actual.isDir == expected.isDir && actual.isLink == expected.isLink && actual.size == expected.size &&
            actual.permissions == expected.permissions && actual.mTime == expected.mTime

    override fun serverSideCopyFile(
        request: com.infinifolder.storage.StorageTransferRequest,
        isCancelled: () -> Boolean
    ): ProviderResult<ResourceEntry> {
        if (request.source.providerId != id || request.destinationParent.providerId != id)
            return ProviderResult.Unsupported(ResourceCapability.COPY)
        var abandonedTemporary: String? = null
        var cleanupConnection: Pair<SftpConfig, SftpCredentials>? = null
        var publishedFinal = false
        return try {
            com.infinifolder.storage.StorageSafety.validateName(request.destinationName)
            val sourceUri = Uri.parse(request.source.documentId)
            val parentUri = Uri.parse(request.destinationParent.documentId)
            val connectionId = SftpUri.getConnectionId(sourceUri)
            if (connectionId.isBlank() || connectionId != SftpUri.getConnectionId(parentUri))
                return ProviderResult.Unsupported(ResourceCapability.COPY)
            val sourcePath = SftpUri.getRemotePath(sourceUri)
            val parentPath = SftpUri.getRemotePath(parentUri)
            val destinationPath = if (parentPath == "/") "/${request.destinationName}" else "$parentPath/${request.destinationName}"
            val (config, creds) = resolveConfigAndCredentials(connectionId)
            cleanupConnection = config to creds
            clientPool.withChannel(config, creds) { channel ->
                com.infinifolder.archive.RecoveryCancellation.check()
                if (isCancelled()) throw java.io.InterruptedIOException("SFTP copy cancelled")
                if (channel.getExtension("copy-data") != "1") return@withChannel ProviderResult.Unsupported(ResourceCapability.COPY)
                val sourceAttrs = channel.stat(literalPath(sourcePath))
                if (!sourceAttrs.isReg) return@withChannel ProviderResult.Unsupported(ResourceCapability.COPY)
                val parentAttrs = channel.stat(literalPath(parentPath))
                if (!parentAttrs.isDir) throw IOException("SFTP transfer destination is not a directory")
                try {
                    channel.lstat(literalPath(destinationPath))
                    throw java.nio.file.FileAlreadyExistsException(request.destinationName)
                } catch (error: SftpException) {
                    if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
                }
                val temporaryPath = if (parentPath == "/") "/.infinifolder-copy-${java.util.UUID.randomUUID()}.part"
                    else "$parentPath/.infinifolder-copy-${java.util.UUID.randomUUID()}.part"
                abandonedTemporary = temporaryPath
                var published = false
                var copyCompleted = false
                try {
                    SftpRawProtocol.copyDataFile(channel, sourcePath, temporaryPath, sourceAttrs.size, isCancelled)
                    copyCompleted = true
                    if (isCancelled()) throw java.io.InterruptedIOException("SFTP copy cancelled before publication")
                    val copiedAttrs = channel.lstat(literalPath(temporaryPath))
                    if (!copiedAttrs.isReg || copiedAttrs.size != sourceAttrs.size)
                        throw IOException("SFTP copy-data destination size did not match the source")
                    val currentSource = channel.stat(literalPath(sourcePath))
                    if (!currentSource.isReg || currentSource.size != sourceAttrs.size || currentSource.mTime != sourceAttrs.mTime ||
                        currentSource.permissions != sourceAttrs.permissions)
                        throw IOException("SFTP source changed while the server was copying it")
                    SftpRawProtocol.renameNoReplace(channel, temporaryPath, destinationPath, isCancelled)
                    published = true
                    publishedFinal = true
                    abandonedTemporary = null
                    val finalAttrs = channel.lstat(literalPath(destinationPath))
                    if (!finalAttrs.isReg || finalAttrs.size != sourceAttrs.size)
                        throw IOException("SFTP copy destination verification failed after publication")
                    val finalUri = SftpUri.build(connectionId, destinationPath)
                    ProviderResult.Success(resourceEntry(connectionId, channel, destinationPath, request.destinationName,
                        finalAttrs, remoteSubject(channel, config)).copy(ref = ResourceRef(id, finalUri.toString()), uri = finalUri))
                } catch (error: Exception) {
                    val unsupported = error as? SftpRawProtocol.StatusFailure
                    val unsupportedBeforeCopy = unsupported?.operation == "copy-data" && unsupported.status == 8 &&
                        error.suppressed.isEmpty()
                    var cleaned = false
                    // An interrupted or malformed request may still have a reply in flight.
                    // Do not send another packet on that channel; the pool discards it and
                    // the outer cleanup reconnects before removing the unique temporary name.
                    if (!published && (copyCompleted || unsupportedBeforeCopy)) {
                        val cleanupResult = runCatching {
                            channel.rm(literalPath(temporaryPath))
                        }
                        if (cleanupResult.isSuccess || (cleanupResult.exceptionOrNull() as? SftpException)?.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                            abandonedTemporary = null
                            cleaned = true
                        } else if (cleanupResult.exceptionOrNull() != null) {
                            error.addSuppressed(cleanupResult.exceptionOrNull()!!)
                        }
                    }
                    if (unsupportedBeforeCopy && !published && cleaned) {
                        return@withChannel ProviderResult.Unsupported(ResourceCapability.COPY)
                    }
                    throw error
                }
            }
        } catch (error: Exception) {
            val temporary = abandonedTemporary
            val connection = cleanupConnection
            if (temporary != null && connection != null && !publishedFinal) {
                val cleanup = runCatching {
                    com.infinifolder.archive.RecoveryCancellation.withoutCancellation {
                        clientPool.withChannel(connection.first, connection.second) { channel ->
                            try {
                                val attrs = channel.lstat(literalPath(temporary))
                                if (attrs.isReg) channel.rm(literalPath(temporary))
                            } catch (missing: SftpException) {
                                if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing
                            }
                        }
                    }
                }
                if (cleanup.isFailure) error.addSuppressed(cleanup.exceptionOrNull()!!)
                else com.infinifolder.storage.DiagnosticLog.record("sftp.copy-data.temp-cleanup", "removed=true")
            }
            ProviderResult.Failure(error.message ?: "SFTP server-side copy failed", error)
        }
    }
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = try {
        ProviderResult.Success(SftpUri.getParentUri(Uri.parse(ref.documentId))?.let { ResourceRef(id, it.toString()) })
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve parent", e) }
    override fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = try {
        val uri = Uri.parse(ref.documentId)
        val connId = SftpUri.getConnectionId(uri)
        val path = SftpUri.getRemotePath(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val target = clientPool.withReadChannel(config, creds) { it.readlink(path) }
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
        val connId = SftpUri.getConnectionId(uri)
        val (config, creds) = resolveConfigAndCredentials(connId)
        val entry = clientPool.withReadChannel(config, creds) { channel ->
            // A stat must not enumerate the parent and follow every sibling link.
            val attrs = channel.lstat(literalPath(path))
            resourceEntry(connId, channel, path, path.substringAfterLast('/'), attrs,
                remoteSubject(channel, config)).copy(ref = ref, uri = uri)
        }
        ProviderResult.Success(entry)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query remote resource", e) }
    }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<com.infinifolder.archive.BlockDevice> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
        val path = SftpUri.getRemotePath(uri)
        if (writable) {
            val attrs = clientPool.withReadChannel(config, creds) { it.lstat(path) }
            if (!attrs.isReg || attrs.isLink) throw IOException("Only regular SFTP files can be edited as image targets")
            ProviderResult.Success(com.infinifolder.storage.StagedRandomWriteDevice.open(this, ref,
                java.io.File(context.cacheDir, "image_working"), java.io.File(context.filesDir, "save_recovery")))
        } else {
            // This lookup is idempotent. Recover once if JSch reports a stale
            // pooled session before creating the independent range reader.
            val size = clientPool.withReadChannel(config, creds) { it.stat(path).size }
            ProviderResult.Success(com.infinifolder.archive.RangeCachedBlockDevice(
                SftpBlockDevice(clientPool, config, creds, path, isReadOnly = true, initialSize = size)))
        }
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open remote random access", e) }

    override fun openNativeSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<com.infinifolder.archive.BlockDevice> =
        if (writable) openTransferTarget(ref) else openSeekable(ref, false)

    /** Direct offset writes without truncating; existing files and transfer targets are supported. */
    override fun openTransferTarget(ref: ResourceRef): ProviderResult<com.infinifolder.archive.BlockDevice> = try {
        val uri = Uri.parse(ref.documentId)
        val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
        val path = SftpUri.getRemotePath(uri)
        val size = clientPool.withReadChannel(config, creds) { channel ->
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

        val entries = clientPool.withReadChannel(config, creds) { channel ->
            val list = mutableListOf<ResourceEntry>()
            @Suppress("UNCHECKED_CAST")
            val vector = channel.ls(remotePath) as Vector<ChannelSftp.LsEntry>
            val remoteSubject = remoteSubject(channel, config)

            for (item in vector) {
                val filename = item.filename
                if (filename == "." || filename == "..") continue

                val childRemotePath = if (remotePath == "/") "/$filename" else "$remotePath/$filename"
                list += resourceEntry(connId, channel, childRemotePath, filename, item.attrs, remoteSubject)
            }
            list.sortedWith(compareBy({ !it.metadata.isDirectory }, { it.metadata.name.lowercase() }))
        }
        ProviderResult.Success(entries)
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to list remote directory", e)
    }

    // JSch expands glob characters in stat/readlink arguments. Resource refs are literal names.
    private fun literalPath(path: String): String = buildString {
        for (c in path) {
            if (c == '*' || c == '?' || c == '\\') append('\\')
            append(c)
        }
    }

    private fun remoteSubject(channel: ChannelSftp, config: SftpConfig): com.infinifolder.storage.SubjectIdentity {
        synchronized(remoteSubjects) { remoteSubjects[channel] }?.let { return it }
        val subject = try {
            val homeAttrs = channel.stat(literalPath(channel.home))
            com.infinifolder.storage.SubjectIdentity(
                uid = homeAttrs.uId,
                username = config.username,
                gid = homeAttrs.gId,
                isRoot = config.username.equals("root", ignoreCase = true) || homeAttrs.uId == 0
            )
        } catch (error: Exception) {
            if (!optionalMetadataUnavailable(error)) throw error
            return com.infinifolder.storage.SubjectIdentity(
                username = config.username,
                isRoot = config.username.equals("root", ignoreCase = true)
            )
        }
        // Cache only successful subject inference, for this authenticated channel.
        // File attributes and revision checks always query the server afresh.
        synchronized(remoteSubjects) { remoteSubjects[channel] = subject }
        return subject
    }

    private fun resourceEntry(connId: String, channel: ChannelSftp, childRemotePath: String,
        filename: String, attrs: SftpATTRS?, remoteSubject: com.infinifolder.storage.SubjectIdentity): ResourceEntry {
        val isDir = attrs?.isDir ?: false
        val sizeBytes = if (isDir) null else attrs?.size
        val mtime = (attrs?.mTime?.toLong() ?: 0L) * 1000L
        val childUri = SftpUri.build(connId, childRemotePath)

        val isLink = attrs?.isLink == true
        var targetIsDir = isDir
        var targetSize = sizeBytes
        var linkTarget: String? = null
        var isBroken = false

        if (isLink) {
            linkTarget = try { channel.readlink(literalPath(childRemotePath)) } catch (error: Exception) {
                if (!optionalMetadataUnavailable(error)) throw error
                null
            }
            try {
                val statAttrs = channel.stat(literalPath(childRemotePath))
                targetIsDir = statAttrs.isDir
                targetSize = if (targetIsDir) null else statAttrs.size
            } catch (error: Exception) {
                if (!optionalMetadataUnavailable(error)) throw error
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

        return ResourceEntry(
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

    /** A complete server error may omit optional metadata; broken I/O must reach the pool. */
    private fun optionalMetadataUnavailable(error: Exception): Boolean {
        com.infinifolder.archive.RecoveryCancellation.check()
        return error is SftpException && error.cause == null &&
            error.id !in setOf(ChannelSftp.SSH_FX_BAD_MESSAGE, ChannelSftp.SSH_FX_NO_CONNECTION, ChannelSftp.SSH_FX_CONNECTION_LOST) &&
            (error.id != ChannelSftp.SSH_FX_FAILURE || !error.message.isNullOrBlank())
    }

    private fun requireNewRemoteName(parent: ResourceRef, name: String) {
        com.infinifolder.storage.StorageSafety.validateName(name)
        val uri = Uri.parse(parent.documentId)
        val (config, creds) = resolveConfigAndCredentials(SftpUri.getConnectionId(uri))
        val exists = clientPool.withReadChannel(config, creds) { channel ->
            // Collision checks need names, not MIME/permission mapping or a
            // readlink/stat round trip for every symlink in the destination.
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(literalPath(SftpUri.getRemotePath(uri))) as Vector<ChannelSftp.LsEntry>
            entries.any { it.filename.equals(name, ignoreCase = true) }
        }
        if (exists) throw java.nio.file.FileAlreadyExistsException(name)
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
        requireNewRemoteName(parent, name)
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
        requireNewRemoteName(parent, name)
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
            stage = "renaming without replacing an existing destination"
            SftpRawProtocol.renameNoReplace(channel, oldPath, newPath) { false }
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
