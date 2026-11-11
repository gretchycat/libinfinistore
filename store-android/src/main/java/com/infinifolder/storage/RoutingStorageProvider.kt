package com.infinifolder.storage

import android.net.Uri
import com.infinifolder.archive.BlockDevice
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Common routing, normalization, error boundaries, and diagnostics for every backend. */
class RoutingStorageProvider(vararg providers: StorageProvider, private val uriResolver: ((Uri) -> Uri?)? = null) : StorageProvider, RandomAccessTransferRouter, NativeRandomAccessProvider {
    override val id = "router"
    var onEntriesObserved: ((ResourceRef, List<ResourceEntry>, Boolean) -> Unit)? = null
    var onResourceRemoved: ((ResourceRef) -> Unit)? = null
    var onRandomAccessTargetWritten: ((ResourceRef) -> Unit)? = null
    private val completedCheckpointCleanup = java.util.concurrent.ConcurrentHashMap<ResourceRef, TransferCheckpointStore>()
    private fun cleanupPublishedCheckpoint(destination: ResourceRef) {
        val checkpoint = completedCheckpointCleanup.remove(destination) ?: return
        try {
            checkpoint.delete()
            DiagnosticLog.record("torrent.checkpoint.published-cleanup", "destination=${destination.providerId}")
        } catch (error: Exception) {
            // A leftover cache must not undo successful publication of the data.
            DiagnosticLog.record("torrent.checkpoint.published-cleanup-failed", "Cache retained after publication", error)
        } finally { runCatching { checkpoint.close() } }
    }
    private val byId = java.util.concurrent.ConcurrentHashMap(providers.associateBy { it.id }.also { require(it.size == providers.size) { "Duplicate provider ID" } })
    fun registerAdditional(provider:StorageProvider):Boolean=byId.putIfAbsent(provider.id,provider)==null

    fun setTorrentAutoResume(destination: ResourceRef, enabled: Boolean) {
        (byId["torrent"] as? RandomAccessTransferSource)?.setAutoResume(destination, enabled)
    }

    /** Restart durable direct-to-storage torrent sessions after the process is recreated. */
    fun resumePendingTorrentTransfers(destination: ResourceRef? = null): Int {
        val sessions = (byId["torrent"] as? RandomAccessTransferSource)?.pendingResumeTransfers().orEmpty()
        val torrentSource = byId["torrent"] as? RandomAccessTransferSource
        var scheduled = 0
        for (session in sessions) {
            if (destination != null && session.destination != destination) continue
            if (destination != null) torrentSource?.setAutoResume(session.destination, true)
            val label = "Resume torrent: ${session.incompleteName}"
            if (com.infinifolder.ops.AppOperations.coordinator.snapshots().any {
                    (it.label == label || it.resumeDestination == session.destination) && (it.state == com.infinifolder.ops.OperationState.RUNNING || it.state == com.infinifolder.ops.OperationState.QUEUED)
                }) {
                if (destination != null) scheduled++
                continue
            }
            if (destination == null && torrentSource?.claimAutoResume(session.source, session.destination) == false) {
                DiagnosticLog.record("torrent.resume.auto-skip", "reason=already-attempted destination=${session.destination.providerId}")
                continue
            }
            val conflicts = setOf(
                "resource:${session.source.providerId}:${session.source.documentId}",
                "object:${session.destinationParent.providerId}:${session.destinationParent.documentId}:${session.finalName.lowercase(java.util.Locale.ROOT)}",
                "object:${session.destinationParent.providerId}:${session.destinationParent.documentId}:${session.incompleteName.lowercase(java.util.Locale.ROOT)}"
            )
            com.infinifolder.ops.AppOperations.coordinator.submit(label, null, 1, conflictKeys = conflicts,
                displayName = session.finalName,
                destination = session.destinationParent.documentId,
                resumeDestination = session.destination) { publishUpdate, cancelled ->
                val update: (com.infinifolder.ops.OperationUpdate) -> Unit = { value ->
                    publishUpdate(value.copy(resumeDestination = session.destination))
                }
                val received = java.util.concurrent.atomic.AtomicLong()
                val remaining = java.util.concurrent.atomic.AtomicLong(-1L)
                val existing = java.util.concurrent.atomic.AtomicLong()
                val ready = java.util.concurrent.atomic.AtomicBoolean(false)
                val startupStatus = java.util.concurrent.atomic.AtomicReference("Preparing torrent download…")
                val lastReport = java.util.concurrent.atomic.AtomicLong()
                val torrentStats = java.util.concurrent.atomic.AtomicReference<TorrentTransferStats?>()
                val expected = (stat(session.source) as? ProviderResult.Success)?.value?.metadata?.sizeBytes
                fun downloadUpdate(force: Boolean = false) {
                    val now = System.nanoTime()
                    if (!force && now - lastReport.get() < 250_000_000L) return
                    lastReport.set(now)
                    val completedBytes = (existing.get() + received.get()).let { count -> expected?.let { count.coerceAtMost(it) } ?: count }
                    update(com.infinifolder.ops.OperationUpdate(bytes = completedBytes, totalBytes = expected,
                        currentItem = session.incompleteName,
                        message = torrentStats.get()?.statusMessage() ?: "Connecting to peers…",
                        indeterminate = false, currentItemBytes = completedBytes, currentItemTotalBytes = expected,
                        remainingBytes = remaining.get().takeIf { it >= 0 },
                        bytesPerSecond = torrentStats.get()?.downloadBytesPerSecond ?: 0L,
                        torrentStats = torrentStats.get()))
                }
                update(com.infinifolder.ops.OperationUpdate(currentItem = session.incompleteName,
                    message = "Preparing torrent download…", indeterminate = true, resumable = true))
                when (val result = transferRandomAccess(session.source, session.destination, cancelled, onBytesWritten = { delta ->
                    received.addAndGet(delta)
                    downloadUpdate()
                }, onPieceVerification = { checked, total ->
                    if (!ready.get()) update(com.infinifolder.ops.OperationUpdate(currentItem = session.incompleteName,
                        message = startupStatus.get(), indeterminate = false,
                        torrentPiecesChecked = checked, torrentPiecesTotal = total))
                }, onBytesRemaining = { missing ->
                    remaining.set(missing)
                    if (ready.compareAndSet(false, true)) {
                        existing.set(expected?.let { (it - missing).coerceAtLeast(0L) } ?: 0L)
                        downloadUpdate(force = true)
                    }
                }, onResumeStatus = { message ->
                    startupStatus.set(message)
                    if (!ready.get()) update(com.infinifolder.ops.OperationUpdate(currentItem = session.incompleteName,
                        message = message, indeterminate = true))
                }, onTorrentStats = { stats ->
                    torrentStats.set(stats)
                    if (ready.get()) downloadUpdate(force = true)
                })) {
                    is ProviderResult.Success -> {
                        if (cancelled()) throw java.io.InterruptedIOException("Torrent paused before final rename; completed data retained")
                        update(com.infinifolder.ops.OperationUpdate(bytes = result.value, totalBytes = expected,
                            currentItem = session.finalName, message = "Finalizing completed download…",
                            currentItemBytes = result.value, currentItemTotalBytes = expected))
                        DiagnosticLog.record("torrent.publication.start", "destination=${session.destination.providerId} interrupted=${Thread.currentThread().isInterrupted}")
                        val renamed = rename(session.destination, session.finalName)
                        if (renamed !is ProviderResult.Success) {
                            val detail = (renamed as? ProviderResult.Failure)?.message?.takeIf { it.isNotBlank() } ?: "Rename unsupported"
                            DiagnosticLog.record("torrent.publication.failed", detail, (renamed as? ProviderResult.Failure)?.cause)
                            throw IOException("Torrent data is complete but final rename failed. Downloaded data and resume state retained; use Retry. $detail",
                                (renamed as? ProviderResult.Failure)?.cause)
                        }
                        migrateResumeState(session.source, session.destination, renamed.value.ref)
                        forgetResumeDestination(session.source, session.destinationParent, session.requestedName)
                        finishResume(session.source, renamed.value.ref)
                        DiagnosticLog.record("torrent.publication.complete", "destination=${renamed.value.ref.providerId}")
                    }
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw IOException("Destination no longer supports random-access torrent resume")
                }
            }
            scheduled++
        }
        return scheduled
    }
    override fun close() { byId.values.forEach { it.close() } }
    override fun refForUri(uri: Uri): ResourceRef? {
        val resolved = if (uriResolver != null) uriResolver.invoke(uri) ?: return null else uri
        return byId.values.sortedBy { it.id == "saf" }.asSequence().mapNotNull { it.refForUri(resolved) }.firstOrNull()
    }
    private fun normalized(entry: ResourceEntry) = entry.copy(metadata = entry.metadata.copy(
        mimeType = MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType)))
    private fun connectionKey(ref: ResourceRef): NetworkConnectionStatus.Target? = NetworkConnectionStatus.target(ref)
    private fun <T> watchTransport(connection: NetworkConnectionStatus.Target?, work: () -> T): T {
        val target = connection ?: return work()
        return NetworkConnectionStatus.watchIo(target.key, target.path, work)
    }
    private fun <T> call(operation: String, ref: ResourceRef, action: (StorageProvider) -> ProviderResult<T>): ProviderResult<T> = StorageActivity.track(
        if (operation in setOf("batch.begin", "replace", "replace-file", "write", "mkdir", "create", "rename", "delete", "seekable-write", "native-offset-write", "transfer-target", "metadata.apply", "native-move", "server-copy"))
            StorageActivity.Kind.FILE_OPERATION else StorageActivity.Kind.LOADING
    ) {
        // Metadata-only helpers do not prove that a network round trip succeeded.
        val connectionRequest = if (ref.providerId in setOf("sftp", "ftp", "smb", "webdav") &&
            operation in setOf("list", "stat", "read", "write", "mkdir", "create", "rename", "delete", "seekable", "seekable-write", "native-offset", "native-offset-write", "transfer-target", "replace", "replace-file", "native-move", "server-copy", "transfer.capabilities")) {
            Uri.parse(ref.documentId).authority?.takeIf { it.isNotBlank() }?.let {
                NetworkConnectionStatus.begin(NetworkConnectionStatus.Key(ref.providerId, it))
            }
        } else null
        val result = try {
            byId[ref.providerId]?.let(action) ?: ProviderResult.Failure("Unknown provider: ${ref.providerId}")
        } catch (e: Exception) { ProviderResult.Failure(e.message ?: "$operation failed", e) }
        connectionRequest?.let { request -> when (result) {
            is ProviderResult.Success -> if (!result.isLoading && operation != "stat") NetworkConnectionStatus.succeeded(request)
                else NetworkConnectionStatus.observed(request)
            is ProviderResult.Failure -> NetworkConnectionStatus.failed(request, result.cause, result.message)
            is ProviderResult.Unsupported -> NetworkConnectionStatus.observed(request)
        } }
        if (connectionRequest != null && operation in setOf("list", "stat", "read", "seekable", "native-offset")) {
            val path = NetworkConnectionStatus.target(ref)?.path
            if (path != null) when (result) {
                is ProviderResult.Success -> if (!result.isLoading && operation != "stat") NetworkConnectionStatus.accessible(connectionRequest, path)
                is ProviderResult.Failure -> NetworkConnectionStatus.inaccessible(connectionRequest, path, result.cause, result.message)
                is ProviderResult.Unsupported -> Unit
            }
        }
        when (result) {
            is ProviderResult.Failure -> DiagnosticLog.record("storage.$operation.failed", "provider=${ref.providerId} ${result.message}", result.cause)
            is ProviderResult.Unsupported -> DiagnosticLog.record("storage.$operation.unsupported", "provider=${ref.providerId} capability=${result.capability}")
            is ProviderResult.Success -> DiagnosticLog.record("storage.$operation", "provider=${ref.providerId} loading=${result.isLoading}" + (result.notice?.let { " notice=$it" } ?: ""))
        }
        result
    }
    override fun beginWriteBatch(destination: ResourceRef, isCancelled: () -> Boolean): ProviderResult<StorageWriteBatch> =
        call("batch.begin", destination) { it.beginWriteBatch(destination, isCancelled) }
    override fun backingResource(ref: ResourceRef): ResourceRef? = byId[ref.providerId]?.backingResource(ref)
    override fun recoverySource(ref: ResourceRef): ResourceRef? = byId[ref.providerId]?.recoverySource(ref)
    override fun deviceIdentity(ref: ResourceRef): String? = byId[ref.providerId]?.deviceIdentity(ref)
    override fun transferCapabilities(request: StorageTransferRequest): ProviderResult<StorageTransferCapabilities> {
        if (request.source.providerId != request.destinationParent.providerId)
            return ProviderResult.Success(StorageTransferCapabilities())
        return call("transfer.capabilities", request.source) { it.transferCapabilities(request) }
    }
    override fun nativeMove(request: StorageTransferRequest, isCancelled: () -> Boolean): ProviderResult<ResourceEntry> {
        if (request.source.providerId != request.destinationParent.providerId)
            return ProviderResult.Unsupported(ResourceCapability.MOVE)
        return call("native-move", request.source) { backend ->
            backend.nativeMove(request, isCancelled).also { result ->
                if (result is ProviderResult.Success) {
                    onResourceRemoved?.invoke(request.source)
                    runCatching { onEntriesObserved?.invoke(request.destinationParent, listOf(normalized(result.value)), false) }
                }
            }
        }
    }
    override fun serverSideCopyFile(request: StorageTransferRequest, isCancelled: () -> Boolean): ProviderResult<ResourceEntry> {
        if (request.source.providerId != request.destinationParent.providerId)
            return ProviderResult.Unsupported(ResourceCapability.COPY)
        return call("server-copy", request.source) { backend ->
            backend.serverSideCopyFile(request, isCancelled).also { result ->
                if (result is ProviderResult.Success)
                    runCatching { onEntriesObserved?.invoke(request.destinationParent, listOf(normalized(result.value)), false) }
            }
        }
    }
    override fun capabilities(ref: ResourceRef): ProviderResult<ResourceCapabilities> = call("capabilities", ref) { it.capabilities(ref) }
    override fun fileSystemInfo(ref: ResourceRef): ProviderResult<FileSystemInfo> = call("filesystem", ref) { it.fileSystemInfo(ref) }
    override fun readTransferMetadata(ref: ResourceRef): ProviderResult<TransferMetadata> = call("metadata.read", ref) { it.readTransferMetadata(ref) }
    override fun applyTransferMetadata(ref: ResourceRef, metadata: TransferMetadata): ProviderResult<Unit> = call("metadata.apply", ref) { it.applyTransferMetadata(ref, metadata) }
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = call("parent", ref) { it.parent(ref) }
    override fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = call("link", ref) { it.resolveLink(ref) }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = call("stat", ref) { backend ->
        when (val result = backend.stat(ref)) {
            is ProviderResult.Success -> result.copy(value = normalized(result.value))
            else -> result
        }
    }
    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = call("list", parent) { backend ->
        when (val result = backend.list(parent)) {
            is ProviderResult.Success -> result.copy(value = result.value.map(::normalized)).also { listed ->
                runCatching { onEntriesObserved?.invoke(parent, listed.value, !listed.isLoading) }
                    .onFailure { DiagnosticLog.record("partial-downloads.observe.failed", error = it) }
            }
            else -> result
        }
    }
    override fun openPartialRecovery(ref: ResourceRef): ProviderResult<RecoveryExport> =
        call("recovery.partial", ref) { it.openPartialRecovery(ref) }
    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<BlockDevice> = call(if (writable) "seekable-write" else "seekable", ref) { backend ->
        checkedDevice(backend.openSeekable(ref, writable), writable, ref = ref)
    }
    override fun openTransferTarget(ref: ResourceRef): ProviderResult<BlockDevice> = call("transfer-target", ref) { backend ->
        checkedDevice(backend.openTransferTarget(ref), writable = true, resizable = true, ref = ref)
    }
    override fun openNativeSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<BlockDevice> = call(if(writable) "native-offset-write" else "native-offset", ref) { backend ->
        checkedDevice(backend.openNativeSeekable(ref, writable), writable, ref = ref)
    }
    private fun checkedDevice(result: ProviderResult<BlockDevice>, writable: Boolean,
                              resizable: Boolean = false, ref: ResourceRef): ProviderResult<BlockDevice> {
        val checked = validateSeekableResult(result, writable, resizable)
        if (checked is ProviderResult.Success) {
            val device = checked.value
            val connection = connectionKey(ref)
            return checked.copy(value = object : BlockDevice by device {
                override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int =
                    watchTransport(connection) { device.readAt(offset, buffer, dstOffset, length) }.also {
                        if (it > 0) StorageActivity.pulse(StorageActivity.Kind.LOADING)
                    }
                override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) =
                    watchTransport(connection) { device.writeAt(offset, buffer, srcOffset, length) }
                override fun flush() = watchTransport(connection) { device.flush() }
                override fun truncate(newSize: Long) = watchTransport(connection) { device.truncate(newSize) }
                override fun close() = watchTransport(connection) { device.close() }
            })
        }
        return checked
    }
    override fun requiresRandomAccess(source: ResourceRef): Boolean = byId[source.providerId] is RandomAccessTransferSource

    override fun transferRandomAccess(
        source: ResourceRef,
        destination: ResourceRef,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        onPieceVerification: (Int, Int) -> Unit,
        onBytesRemaining: (Long) -> Unit,
        onResumeStatus: (String) -> Unit,
        onTorrentStats: (TorrentTransferStats) -> Unit
    ): ProviderResult<Long> = StorageActivity.track(StorageActivity.Kind.FILE_OPERATION) {
        val producer = byId[source.providerId] as? RandomAccessTransferSource
            ?: return@track ProviderResult.Unsupported(ResourceCapability.SEEK)
        val targetProvider = byId[destination.providerId]
            ?: return@track ProviderResult.Failure("Unknown destination provider: ${destination.providerId}")
        val targetResult = openTransferTarget(destination)
        val target = when (targetResult) {
            is ProviderResult.Success -> targetResult.value
            is ProviderResult.Failure -> return@track ProviderResult.Failure(targetResult.message, targetResult.cause)
            is ProviderResult.Unsupported -> return@track ProviderResult.Unsupported(targetResult.capability)
        }
        completedCheckpointCleanup.remove(destination)
        val checkpoint = if (destination.providerId != "archive") siblingTransferCheckpointStore(targetProvider, destination) else null
        var result: ProviderResult<Long> = try {
            producer.transferTo(source, destination, target, isCancelled, onBytesWritten, onPieceVerification,
                checkpoint, onBytesRemaining, onResumeStatus, onTorrentStats)
        } catch (e: Exception) {
            ProviderResult.Failure(e.message ?: "Random-access transfer failed", e)
        }
        try { target.close() }
        catch (error: Exception) {
            DiagnosticLog.record("transfer.random-access.target-close-failed", destination.documentId, error)
            if (result is ProviderResult.Success) result = ProviderResult.Failure("Closing the transfer destination failed; partial data and resume state retained", error)
            else (result as? ProviderResult.Failure)?.cause?.addSuppressed(error)
        }
        if (result is ProviderResult.Success && checkpoint != null) completedCheckpointCleanup[destination] = checkpoint
        if (result is ProviderResult.Success) runCatching { onRandomAccessTargetWritten?.invoke(destination) }
            .onFailure { DiagnosticLog.record("common-locations.observe.failed", error = it) }
        result
    }

    override fun findResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String): ResumeDestination? =
        (byId[source.providerId] as? RandomAccessTransferSource)?.findResumeDestination(source, destinationParent, requestedName)

    override fun rememberResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String, destination: ResourceRef, destinationName: String) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.rememberResumeDestination(source, destinationParent, requestedName, destination, destinationName)
    }

    override fun forgetResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.forgetResumeDestination(source, destinationParent, requestedName)
    }

    override fun discardResumeState(source: ResourceRef, destination: ResourceRef) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.discardResumeState(source, destination)
    }

    override fun migrateResumeState(source: ResourceRef, oldDestination: ResourceRef, newDestination: ResourceRef) {
        (byId[source.providerId] as? RandomAccessTransferSource)?.migrateResumeState(source, oldDestination, newDestination)
    }

    override fun finishResume(source: ResourceRef, destination: ResourceRef) {
        cleanupPublishedCheckpoint(destination)
        (byId[source.providerId] as? RandomAccessTransferSource)?.finishResume(source, destination)
    }
    override fun replaceContent(ref: ResourceRef, data: ByteArray): ProviderResult<Unit> = call("replace", ref) { it.replaceContent(ref, data) }
    override fun replaceContentFromFile(ref: ResourceRef, file: java.io.File): ProviderResult<Unit> =
        call("replace-file", ref) { it.replaceContentFromFile(ref, file) }
    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = call("read", ref) { backend ->
        val connection = connectionKey(ref)
        when (val result = backend.openRead(ref)) {
            is ProviderResult.Success -> result.copy(value = object : java.io.FilterInputStream(result.value) {
                override fun read(): Int = watchTransport(connection) { super.read() }.also { if (it >= 0) StorageActivity.pulse(StorageActivity.Kind.LOADING) }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = watchTransport(connection) { super.read(bytes, offset, length) }.also {
                    if (it > 0) StorageActivity.pulse(StorageActivity.Kind.LOADING)
                }
                override fun skip(count: Long): Long = watchTransport(connection) { super.skip(count) }
                override fun close() = watchTransport(connection) { super.close() }
            })
            else -> result
        }
    }
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = call("write", ref) { backend ->
        val connection = connectionKey(ref) ?: return@call backend.openWrite(ref)
        when (val result = backend.openWrite(ref)) {
            is ProviderResult.Success -> result.copy(value = object : OutputStream() {
                override fun write(value: Int) = watchTransport(connection) { result.value.write(value) }
                override fun write(bytes: ByteArray) = watchTransport(connection) { result.value.write(bytes) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) = watchTransport(connection) { result.value.write(bytes, offset, length) }
                override fun flush() = watchTransport(connection) { result.value.flush() }
                override fun close() = watchTransport(connection) { result.value.close() }
            })
            else -> result
        }
    }
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = call("mkdir", parent) { it.createDirectory(parent, name) }
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = call("create", parent) {
        it.createFile(parent, name, MimeRegistry.effective(name, mimeType)).also { result ->
            if (result is ProviderResult.Success) runCatching { onEntriesObserved?.invoke(parent, listOf(result.value), false) }
        }
    }
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = call("rename", ref) {
        it.rename(ref, name).also { result ->
            if (result is ProviderResult.Success) {
                cleanupPublishedCheckpoint(ref)
                onResourceRemoved?.invoke(ref)
            }
        }
    }
    override fun delete(ref: ResourceRef): ProviderResult<Unit> = call("delete", ref) { backend ->
        val result = backend.delete(ref)
        if (result is ProviderResult.Success) {
            onResourceRemoved?.invoke(ref)
            val torrent = byId["torrent"] as? RandomAccessTransferSource
            torrent?.pendingResumeTransfers()?.filter { it.destination == ref }?.forEach { session ->
                torrent.forgetResumeDestination(session.source, session.destinationParent, session.requestedName)
                torrent.discardResumeState(session.source, ref)
                DiagnosticLog.record("torrent.resume.destination-deleted", "destination=${ref.providerId}:${ref.documentId}")
            }
        }
        result
    }
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = StorageTransfers.transfer(this, source, destination, false)
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = StorageTransfers.transfer(this, source, destination, true)
}
