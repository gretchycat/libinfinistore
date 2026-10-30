package com.infinifolder.ops

import android.net.Uri
import com.infinifolder.apps.AppCategories
import com.infinifolder.apps.AppsUri
import com.infinifolder.model.RecoveryReport
import com.infinifolder.model.RecoveryDisposition
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.TransferMetadata
import com.infinifolder.model.ResourceRef
import com.infinifolder.model.ResourceCapability
import com.infinifolder.model.Support
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageAction
import com.infinifolder.storage.StorageActionPolicy
import com.infinifolder.storage.StorageSafety
import java.security.MessageDigest
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class FolderStats(
    val totalBytes: Long,
    val fileCount: Int,
    val directoryCount: Int
)

object FileOperations {
    private class VerificationSkipped : Exception()
    private class SourceReadException(message: String, cause: Throwable? = null) : IOException(message, cause)
    private fun sourceChange(error: Throwable): com.infinifolder.storage.SourceChangedException? =
        generateSequence(error) { it.cause }.take(16)
            .filterIsInstance<com.infinifolder.storage.SourceChangedException>().firstOrNull()

    fun calculateFolderStats(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean = { false },
        onProgress: ((FolderStats) -> Unit)? = null
    ): FolderStats {
        var bytes = 0L
        var files = 0
        var subdirs = 0
        var lastUpdate = System.currentTimeMillis()

        fun countChildren(parentRef: ResourceRef) {
            if (isCancelled()) return
            val children = StorageSafety.complete(provider.list(parentRef))
            run {
                for (child in children) {
                    if (isCancelled()) return
                    if (child.metadata.isDirectory && !child.metadata.isSymbolicLink) {
                        subdirs++
                        countChildren(child.ref)
                    } else {
                        files++
                        bytes += child.metadata.sizeBytes ?: 0L
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 250) {
                        lastUpdate = now
                        onProgress?.invoke(FolderStats(bytes, files, subdirs))
                    }
                }
            }
        }

        for (entry in entries) {
            if (isCancelled()) break
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                countChildren(entry.ref)
            } else {
                files++
                bytes += entry.metadata.sizeBytes ?: 0L
            }
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 250) {
                lastUpdate = now
                onProgress?.invoke(FolderStats(bytes, files, subdirs))
            }
        }
        return FolderStats(bytes, files, subdirs)
    }

    fun calculateTotalBytes(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean = { false }
    ): Long {
        var total = 0L
        fun count(entry: ResourceEntry) {
            if (isCancelled()) return
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                if (entry.ref.providerId == "apps" || AppsUri.isCategoryUri(entry.uri)) {
                    // Copying an app category creates lightweight shortcut files, not full APKs
                    return
                }
                for (child in StorageSafety.complete(provider.list(entry.ref))) count(child)
            } else {
                total += entry.metadata.sizeBytes ?: 0L
            }
        }
        for (entry in entries) count(entry)
        return total
    }

    fun batchTransfer(
        entries: List<ResourceEntry>,
        destinationFolderUri: Uri,
        move: Boolean,
        provider: StorageProvider,
        resourceRef: (Uri) -> ResourceRef,
        update: (OperationUpdate) -> Unit,
        isCancelled: () -> Boolean,
        createLinks: Boolean = false,
        onWarning: (String) -> Unit = {},
        partialRecovery: Boolean = false,
        verifyCopies: Boolean = true,
        copyVerificationEnabled: (() -> Boolean)? = null,
        confirmRandomAccessFallback: ((com.infinifolder.storage.RandomAccessFallbackRequest) -> Boolean)? = null
    ): List<ResourceRef> = com.infinifolder.archive.RecoveryCancellation.withCancellation(isCancelled) {
        if (com.infinifolder.storage.ContainerLinksProvider.owns(destinationFolderUri) && !createLinks &&
            entries.any { it.metadata.isDirectory || !ShortcutInfo.isShortcut(it.metadata.name, it.metadata.mimeType) })
            throw IOException("Virtual root accepts container links only. Use Link to for folders and archives; regular files belong inside storage containers.")
        // Reuse backing image range caches for ordinary copies only. Recovery
        // needs fresh safety reads, and mutation paths must not reuse snapshots.
        val reuseReads = !move && !createLinks && !partialRecovery &&
            !com.infinifolder.archive.ArchiveUri.isArchiveUri(destinationFolderUri) &&
            entries.none { it.metadata.recoveryNote != null || provider.recoverySource(it.ref) != null }
        com.infinifolder.archive.ReadDeviceScope.withScope(reuseReads, onWarning) {
        batchTransferInternal(entries, destinationFolderUri, move, provider, resourceRef, update,
            { isCancelled() || Thread.currentThread().isInterrupted }, createLinks, onWarning, partialRecovery, verifyCopies, copyVerificationEnabled,
            confirmRandomAccessFallback)
        }
    }

    private fun batchTransferInternal(
        entries: List<ResourceEntry>, destinationFolderUri: Uri, move: Boolean, provider: StorageProvider,
        resourceRef: (Uri) -> ResourceRef, update: (OperationUpdate) -> Unit, isCancelled: () -> Boolean,
        createLinks: Boolean, onWarning: (String) -> Unit, partialRecovery: Boolean, verifyCopies: Boolean, copyVerificationEnabled: (() -> Boolean)?,
        confirmRandomAccessFallback: ((com.infinifolder.storage.RandomAccessFallbackRequest) -> Boolean)?
    ): List<ResourceRef> {
        var activeProvider = provider
        fun verificationWanted() = copyVerificationEnabled?.invoke() ?: verifyCopies
        if (partialRecovery && (move || createLinks)) throw IOException("Partial recovery only creates copies with reports")
        if (move && createLinks) throw IOException("Creating shortcuts never removes source entries")
        val destRef = resourceRef(destinationFolderUri)
        var totalBytes: Long? = null
        var transferredBytes = 0L
        var itemsDone = 0
        val totalItems = entries.size
        var activeFileName: String? = null
        var activeFileBytes: Long? = null
        var activeFileTotalBytes: Long? = null
        var torrentRemainingBytes: Long? = null
        var activeTorrentTransfer = false
        var activeResumeDestination: ResourceRef? = null
        val torrentStats = java.util.concurrent.atomic.AtomicReference<com.infinifolder.storage.TorrentTransferStats?>()
        fun phase(message: String, item: String? = null) {
            com.infinifolder.storage.DiagnosticLog.record("transfer.phase", "stage=$message item=$item bytes=$transferredBytes")
            update(OperationUpdate(bytes = transferredBytes, totalBytes = totalBytes,
                itemsProcessed = itemsDone, totalItems = totalItems, currentItem = item,
                message = message, indeterminate = true,
                resumable = activeTorrentTransfer, resumeDestination = activeResumeDestination,
                currentItemBytes = activeFileBytes.takeIf { item == activeFileName },
                currentItemTotalBytes = activeFileTotalBytes.takeIf { item == activeFileName }))
        }
        fun <T> timed(label: String, item: String? = null, work: () -> T): T {
            phase(label, item)
            val start = System.nanoTime()
            var success = false
            try { return work().also { success = it !is ProviderResult.Failure && it !is ProviderResult.Unsupported } }
            finally {
                com.infinifolder.storage.DiagnosticLog.record("transfer.stage",
                    "stage=$label item=$item elapsedMs=${(System.nanoTime() - start) / 1_000_000} success=$success")
            }
        }
        val destinationAssessment = timed("Checking destination capabilities") {
            when (val result = StorageActionPolicy.destination(activeProvider, destRef, entries, move)) {
                is ProviderResult.Success -> result.value.also { it.decision.requireAllowed() }
                is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                is ProviderResult.Unsupported -> throw IOException("Cannot check destination capabilities: ${result.capability}")
            }
        }
        val destinationContainers = destinationAssessment.containers
        val nativeLinkCandidate = move && destinationContainers.size == 1 && entries.all {
            provider.backingResource(it.ref) == destinationContainers.first()
        }
        var requiresNativeLinkPreservation = false
        if (move) timed("Checking source move capabilities") {
            entries.forEach { entry ->
                val capabilities = when (val result = activeProvider.capabilities(entry.ref)) {
                    is ProviderResult.Success -> result.value
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> null
                }
                StorageActionPolicy.action(StorageAction.MOVE, entry.metadata, capabilities).requireAllowed()
            }
        }
        val shortcutPayloads = mutableMapOf<ResourceRef, ByteArray>()
        val sourceChildren = mutableMapOf<ResourceRef, List<ResourceEntry>>()
        val scanning = mutableSetOf<ResourceRef>()
        val incompleteRoots = mutableSetOf<ResourceRef>()
        var currentSourceRoot: ResourceRef? = null
        fun skipped(entry: ResourceEntry, reason: String) {
            currentSourceRoot?.let { incompleteRoots.add(it) }
            val message = "Skipped '${entry.metadata.name}': $reason"
            com.infinifolder.storage.DiagnosticLog.record("transfer.source-skipped", "$message source=${entry.ref.documentId}")
            onWarning(message)
        }
        fun scan(entry: ResourceEntry): ResourceEntry? {
            if (isCancelled()) throw InterruptedException("Transfer cancelled")
            val recoverySource = activeProvider.recoverySource(entry.ref)
                ?: entry.metadata.recoveryNote?.let { activeProvider.backingResource(entry.ref)
                    ?: throw IOException("Cannot establish the recovery source identity") }
            if (recoverySource != null) {
                if (move) throw IOException("Deleted-file candidates can only be recovered by copying")
                val visited = mutableSetOf<ResourceRef>()
                var sourceBacking: ResourceRef? = recoverySource
                while (sourceBacking != null) {
                    if (!visited.add(sourceBacking) || visited.size > 16) throw IOException("Invalid recovery source dependency chain")
                    if (sourceBacking == destRef || sourceBacking in destinationContainers)
                        throw IOException("Recover to a different location outside the source image and its enclosing containers")
                    sourceBacking = activeProvider.backingResource(sourceBacking)
                }
            }
            if (createLinks) {
                val resolved = if (entry.metadata.isSymbolicLink)
                    (com.infinifolder.storage.LinkResolver.resolve(activeProvider, entry) as? ProviderResult.Success)?.value else null
                val shortcut = ShortcutInfo(
                    targetUri = (resolved?.uri ?: entry.uri).toString(),
                    targetPath = entry.metadata.linkTarget,
                    displayNameOverride = entry.metadata.name,
                    targetMimeType = resolved?.mime ?: entry.metadata.mimeType,
                    targetIsDirectory = resolved?.entry?.metadata?.isDirectory ?: entry.metadata.isDirectory
                )
                shortcutPayloads[entry.ref] = shortcut.toJson().toByteArray(Charsets.UTF_8)
                com.infinifolder.storage.DiagnosticLog.record("transfer.link-shortcut",
                    "sourceProvider=${entry.ref.providerId} name=${entry.metadata.name} originalRetained=true")
                return entry.copy(metadata = entry.metadata.copy(isDirectory = false, isSymbolicLink = false))
            }
            if (entry.metadata.isSymbolicLink && nativeLinkCandidate) {
                requiresNativeLinkPreservation = true
                return entry // Relink the containing inode tree; never follow or recreate these links.
            }
            if (!entry.metadata.canRead && !(partialRecovery && entry.metadata.partialRecoveryAvailable) && entry.ref.providerId != "apps") {
                skipped(entry, entry.metadata.readError ?: "source cannot be read")
                return null
            }
            if (entry.metadata.isSymbolicLink) {
                if (move) throw IOException("Moving folders containing symbolic links requires native link preservation; originals retained. Use Copy or Link to instead.")
                val resolved = try { when (val result = com.infinifolder.storage.LinkResolver.resolve(activeProvider, entry)) {
                    is ProviderResult.Success -> result.value.entry ?: throw IOException("The symbolic-link target is not a copyable storage resource")
                    is ProviderResult.Failure -> throw IOException("Cannot copy symbolic-link target '${entry.metadata.name}': ${result.message}", result.cause)
                    is ProviderResult.Unsupported -> throw IOException("Cannot resolve symbolic-link target '${entry.metadata.name}'")
                } } catch (e: Exception) {
                    sourceChange(e)?.let { throw it }
                    if (e is InterruptedException || isCancelled()) throw e
                    skipped(entry, e.message ?: "Cannot read symbolic-link target")
                    return null
                }
                // Keep the selected link's name, but use the actual target identity for reads,
                // ancestry/cycle checks, space accounting and copy verification.
                return scan(resolved.copy(metadata = resolved.metadata.copy(name = entry.metadata.name)))
            }
            if (entry.ref == destRef) throw IOException("Cannot transfer a folder into itself")
            if (entry.ref in destinationContainers) throw IOException("Cannot copy a container into its own mounted contents; original retained")
            if (entry.metadata.isDirectory) {
                if (!scanning.add(entry.ref)) throw IOException("Cyclic folder or symbolic-link reference")
                val listed = try {
                    val result = activeProvider.list(entry.ref)
                    // Recovery enumeration is complete or throws; notices describe uncertainty and blocked files.
                    if (recoverySource != null && result is ProviderResult.Success && !result.isLoading) {
                        result.notice?.takeIf { it.isNotBlank() }?.let { onWarning("Recovery listing '${entry.metadata.name}': $it") }
                        result.value
                    } else StorageSafety.complete(result)
                }
                    catch (e: Exception) {
                        scanning.remove(entry.ref)
                        sourceChange(e)?.let { throw it }
                        if (e is InterruptedException || isCancelled()) throw e
                        skipped(entry, "cannot read directory: ${e.message}")
                        return null
                    }
                val children = listed.mapNotNull(::scan)
                sourceChildren[entry.ref] = children
                scanning.remove(entry.ref)
            }
            return entry
        }
        val transferEntries = timed("Checking source folders") { entries.mapNotNull { currentSourceRoot = it.ref; scan(it) } }
        if (move) {
            fun containsSource(ref: ResourceRef, target: ResourceRef, visited: MutableSet<ResourceRef>): Boolean {
                if (ref == target) return true
                if (!visited.add(ref)) return false
                return sourceChildren[ref].orEmpty().any { child ->
                    child.metadata.isDirectory && !child.metadata.isSymbolicLink &&
                        containsSource(child.ref, target, visited)
                }
            }
            if (transferEntries.any { source ->
                    source.metadata.isDirectory && containsSource(source.ref, destRef, mutableSetOf())
                }) throw IOException("Cannot move a folder into itself or one of its subfolders; sources retained")
        }
        var fileSystem = timed("Checking destination filesystem") {
            (activeProvider.fileSystemInfo(destRef) as? ProviderResult.Success)?.value
        }
        com.infinifolder.storage.DiagnosticLog.record("transfer.filesystem",
            "destination=${destRef.documentId} type=${fileSystem?.type ?: "unknown"} " +
                "maxFileSizeBytes=${fileSystem?.maxFileSizeBytes} availableBytes=${fileSystem?.availableBytes} " +
                "readOnly=${fileSystem?.readOnly} evidence=${fileSystem?.evidence}")
        fun requestedDestinationName(entry: ResourceEntry): String = when {
            partialRecovery && entry.metadata.partialRecoveryAvailable -> {
                val name = entry.metadata.name
                val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                name.substring(0, dot) + ".partial" + name.substring(dot)
            }
            shortcutPayloads.containsKey(entry.ref) -> entry.metadata.name + ShortcutInfo.SHORTCUT_EXTENSION
            entry.ref.providerId == "apps" && !entry.metadata.name.endsWith(".apk", ignoreCase = true) -> entry.metadata.name + ".apk"
            else -> entry.metadata.name
        }
        fun transferSize(entry: ResourceEntry): Long? = shortcutPayloads[entry.ref]?.size?.toLong() ?: entry.metadata.sizeBytes

        fun validateSize(entry: ResourceEntry) {
            val requested = requestedDestinationName(entry)
            val compatible = fileSystem?.compatibleName(requested) ?: requested
            StorageSafety.validateName(compatible)
            fileSystem?.requireName(compatible)
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) sourceChildren.getValue(entry.ref).forEach(::validateSize)
            else fileSystem?.requireFileFits(requested, transferSize(entry))
        }
        // All known file sizes are checked before the first create, even when a later tree entry is oversized.
        transferEntries.forEach(::validateSize)
        if (fileSystem?.readOnly == true) throw IOException("The destination filesystem is mounted read-only. Source folders retained.")
        fun checkCapacity() {
            val allocationUnit = fileSystem?.allocationUnitBytes?.takeIf { it > 0 } ?: 1L
            fun requiredSpace(entry: ResourceEntry): Long {
                if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                    return sourceChildren.getValue(entry.ref).fold(if (allocationUnit > 1) allocationUnit else 0L) { sum, child ->
                        Math.addExact(sum, requiredSpace(child))
                    }
                }
                val bytes = transferSize(entry)?.coerceAtLeast(0L) ?: 0L
                if (bytes == 0L) return 0L
                return Math.multiplyExact(Math.addExact((bytes - 1) / allocationUnit, 1L), allocationUnit)
            }
            val requiredBytes = transferEntries.fold(0L) { sum, entry -> Math.addExact(sum, requiredSpace(entry)) }
            val availableBytes = fileSystem?.availableBytes
            if (availableBytes != null && requiredBytes > availableBytes) {
                val message = "Not enough space on ${fileSystem?.displayName}. " +
                    "About ${com.infinifolder.storage.StorageMessages.approximateSize(requiredBytes)} needed; " +
                    "${com.infinifolder.storage.StorageMessages.approximateSize(availableBytes)} available. Sources retained."
                com.infinifolder.storage.DiagnosticLog.record("transfer.capacity-rejected",
                    "requiredBytes=$requiredBytes availableBytes=$availableBytes move=$move filesystem=${fileSystem?.type}")
                throw IOException(message)
            }
        }
        // A private batch may relocate existing allocations without duplicating payloads.
        // Defer its payload-space check until the batch declares whether it can do so.
        val tryBatchRelocation = move && destinationContainers.isNotEmpty() && incompleteRoots.isEmpty()
        if (!tryBatchRelocation) checkCapacity()
        var useStagedRandomAccessFallback = false
        fun approveStagedFallback(source: ResourceEntry, reason: String) {
            if (useStagedRandomAccessFallback) return
            val request = com.infinifolder.storage.RandomAccessFallbackRequest(source.ref, source.metadata.name,
                destRef, transferSize(source), reason)
            phase("Waiting for target choice", source.metadata.name)
            val approved = confirmRandomAccessFallback?.invoke(request)
                ?: throw IOException(request.message + "\nExplicit approval is required for staged transfer.")
            if (isCancelled()) throw InterruptedException("Transfer cancelled")
            if (!approved) throw OperationDeclinedException("Transfer stopped; choose a different target")
            useStagedRandomAccessFallback = true
            com.infinifolder.storage.DiagnosticLog.record("transfer.random-access.fallback-approved",
                "source=${source.ref.providerId} destination=${destRef.providerId} bytes=${transferSize(source)}")
        }
        val transferRouter = provider as? com.infinifolder.storage.RandomAccessTransferRouter
        fun firstRandomAccessSource(entry: ResourceEntry): ResourceEntry? =
            if (entry.metadata.isDirectory) sourceChildren[entry.ref].orEmpty().firstNotNullOfOrNull(::firstRandomAccessSource)
            else entry.takeIf { transferRouter?.requiresRandomAccess(it.ref) == true }
        if (!move && !createLinks && !partialRecovery) {
            val source = transferEntries.firstNotNullOfOrNull(::firstRandomAccessSource)
            if (source != null) {
                when (val targetCapabilities = provider.capabilities(destRef)) {
                    is ProviderResult.Success -> {
                        if (targetCapabilities.value[ResourceCapability.CREATE] == Support.UNSUPPORTED)
                            throw IOException("The target cannot safely create new files. Choose a different target; source retained.")
                        if (targetCapabilities.value[ResourceCapability.TRANSFER_TARGET] == Support.UNSUPPORTED)
                            approveStagedFallback(source, "The destination reports no support for random-access transfer targets.")
                    }
                    is ProviderResult.Failure -> throw IOException(targetCapabilities.message, targetCapabilities.cause)
                    is ProviderResult.Unsupported -> Unit // Probe the actual created handle before deciding.
                }
            }
        }
        val batch = when (val result = timed("Preparing destination batch") { activeProvider.beginWriteBatch(destRef, isCancelled) }) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Unsupported -> null
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
        }
        try {
            if (batch != null) {
                activeProvider = batch.provider
                val snapshotInfo = activeProvider.fileSystemInfo(destRef)
                when (snapshotInfo) {
                    is ProviderResult.Success -> fileSystem = snapshotInfo.value
                    is ProviderResult.Failure -> throw IOException("Cannot verify container limits: ${snapshotInfo.message}", snapshotInfo.cause)
                    is ProviderResult.Unsupported -> Unit
                }
                transferEntries.forEach(::validateSize)
                if (!tryBatchRelocation || !batch.supportsRelocation) checkCapacity()
            } else if (tryBatchRelocation) {
                checkCapacity()
            }
            val destinationNames = mutableMapOf<ResourceRef, MutableSet<String>>()
            destinationNames[destRef] = timed("Checking destination") {
                StorageSafety.complete(activeProvider.list(destRef)).map { it.metadata.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
            }

            fun availableName(parent: ResourceRef, original: String): String {
                val compatible = fileSystem?.compatibleName(original) ?: original
                StorageSafety.validateName(compatible)
                fileSystem?.requireName(compatible)
                if (compatible != original) com.infinifolder.storage.DiagnosticLog.record(
                    "transfer.filename-converted", "filesystem=${fileSystem?.type} original=$original destination=$compatible")
                val names = destinationNames.getOrPut(parent) {
                    StorageSafety.complete(activeProvider.list(parent)).map { it.metadata.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
                }
                if (names.add(compatible.lowercase(java.util.Locale.ROOT))) return compatible
                val dot = compatible.lastIndexOf('.').takeIf { it > 0 } ?: compatible.length
                var index = 2
                var candidate: String
                do {
                    candidate = fileSystem?.collisionName(compatible, index)
                        ?: (compatible.substring(0, dot) + " ($index)" + compatible.substring(dot))
                    fileSystem?.requireName(candidate)
                    index++
                } while (candidate.lowercase(java.util.Locale.ROOT) in names)
                names.add(candidate.lowercase(java.util.Locale.ROOT))
                return candidate
            }

            if (tryBatchRelocation && batch?.supportsRelocation == true) {
                fun containsSelectedDescendant(entry: ResourceEntry, selected: Set<ResourceRef>): Boolean =
                    sourceChildren[entry.ref].orEmpty().any { it.ref in selected || containsSelectedDescendant(it, selected) }
                val selected = transferEntries.map { it.ref }.toSet()
                if (selected.size != transferEntries.size || transferEntries.any { containsSelectedDescendant(it, selected) })
                    throw IOException("Select each source once, without also selecting its descendants; sources retained")
                val plan = transferEntries.map {
                    com.infinifolder.storage.StorageRelocation(it.ref, availableName(destRef, requestedDestinationName(it)))
                }
                val relocated = timed("Relocating entries without copying file data") { batch.relocate(plan, destRef) }
                when (relocated) {
                    is ProviderResult.Success -> {
                        if (relocated.value.size != plan.size) throw IOException("Container relocation returned an incomplete result; sources retained")
                        fun logicalBytes(entry: ResourceEntry): Long = if (entry.metadata.isDirectory)
                            sourceChildren[entry.ref].orEmpty().fold(0L) { sum, child -> Math.addExact(sum, logicalBytes(child)) }
                        else entry.metadata.sizeBytes?.coerceAtLeast(0L) ?: 0L
                        totalBytes = transferEntries.fold(0L) { sum, entry -> Math.addExact(sum, logicalBytes(entry)) }
                        if (isCancelled()) throw InterruptedException("Move cancelled before container publication")
                        timed("Publishing relocated container entries") { batch.commit() }
                        update(OperationUpdate(bytes = totalBytes ?: 0L, totalBytes = totalBytes,
                            itemsProcessed = transferEntries.size, totalItems = totalItems,
                            message = "Moved without copying file data", indeterminate = false))
                        return relocated.value.map { it.ref }
                    }
                    is ProviderResult.Failure -> throw IOException(relocated.message, relocated.cause)
                    is ProviderResult.Unsupported -> {
                        // Contract: unsupported relocation has made no changes. Use the
                        // normal verified copy/delete path, with its full space requirement.
                        checkCapacity()
                        destinationNames.clear()
                    }
                }
            }
            if (requiresNativeLinkPreservation)
                throw IOException("This move requires native link preservation; the destination cannot relocate this selection. Sources retained.")

            val metadataWarnings = mutableSetOf<String>()
            val capturedMetadata = mutableMapOf<ResourceRef, TransferMetadata>()
            fun metadataWarning(source: ResourceEntry, reason: String) {
                if (move) currentSourceRoot?.let { incompleteRoots.add(it) }
                com.infinifolder.storage.DiagnosticLog.record("transfer.metadata.warning",
                    "name=${source.metadata.name} reason=$reason sourceRetained=$move")
                if (metadataWarnings.add("metadata")) onWarning("Some permissions or attributes could not be preserved." +
                    if (move) " Affected originals will be kept." else " Check the copied files' permissions.")
            }
            fun captureMetadata(source: ResourceEntry): TransferMetadata? {
                if (createLinks || partialRecovery || source.ref.providerId in setOf("torrent", "apps")) return null
                return when (val result = activeProvider.readTransferMetadata(source.ref)) {
                    is ProviderResult.Success -> result.value.also {
                        if (it.mode != null && it.xattrs == null)
                            metadataWarning(source, "Source provider ${source.ref.providerId} does not export ACLs or extended attributes")
                    }.takeUnless { it.isEmpty }?.also { capturedMetadata[source.ref] = it }
                    is ProviderResult.Failure -> { metadataWarning(source, "Cannot read source attributes: ${result.message}"); null }
                    is ProviderResult.Unsupported -> { metadataWarning(source, "Source provider cannot export attributes"); null }
                }
            }
            fun preserveMetadata(source: ResourceEntry, destination: ResourceRef, metadata: TransferMetadata?) {
                if (metadata == null) return
                if (isCancelled()) throw InterruptedException("Transfer cancelled while preserving metadata")
                when (val applied = activeProvider.applyTransferMetadata(destination, metadata)) {
                    is ProviderResult.Success -> {
                        val actual = activeProvider.readTransferMetadata(destination)
                        if (actual !is ProviderResult.Success || !metadata.matches(actual.value))
                            metadataWarning(source, "Destination attributes did not match after saving")
                        else com.infinifolder.storage.DiagnosticLog.record("transfer.metadata.preserved", "name=${source.metadata.name} destinationProvider=${destination.providerId}")
                    }
                    is ProviderResult.Unsupported -> metadataWarning(source, "Destination provider ${destination.providerId} cannot retain the requested attributes")
                    is ProviderResult.Failure -> metadataWarning(source, "Destination attributes failed: ${applied.message}")
                }
            }

            data class TorrentResumeCleanup(
                val router: com.infinifolder.storage.RandomAccessTransferRouter,
                val source: ResourceRef,
                val parent: ResourceRef,
                val requestedName: String,
                val destination: ResourceRef? = null,
                val clearPieceState: Boolean = false
            )
            val pendingTorrentResumeCleanup = linkedMapOf<String, TorrentResumeCleanup>()
            fun deferTorrentResumeCleanup(
                router: com.infinifolder.storage.RandomAccessTransferRouter?,
                source: ResourceRef,
                parent: ResourceRef,
                requestedName: String,
                destination: ResourceRef? = null,
                clearPieceState: Boolean = false
            ) {
                if (router == null) return
                val key = "${source.documentId}\u0000${parent.documentId}\u0000$requestedName"
                pendingTorrentResumeCleanup[key] = TorrentResumeCleanup(
                    router, source, parent, requestedName, destination, clearPieceState
                )
            }

            val sourceDigests = mutableMapOf<ResourceRef, ByteArray>()
            fun digest(ref: ResourceRef, name: String? = null, expectedBytes: Long? = null, allowSkip: Boolean = false): ByteArray =
                com.infinifolder.storage.StorageActivity.track(com.infinifolder.storage.StorageActivity.Kind.VERIFICATION) {
                val opened = activeProvider.openRead(ref)
                if (opened !is ProviderResult.Success) throw IOException((opened as? ProviderResult.Failure)?.message ?: "Cannot verify file", (opened as? ProviderResult.Failure)?.cause)
                opened.value.use { input ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(128 * 1024)
                    var checked = 0L
                    var lastReport = 0L
                    var lastLog = System.nanoTime()
                    fun report() {
                        val percent = expectedBytes?.takeIf { it > 0 }?.let { (checked.toDouble() / it * 100).toInt().coerceIn(0, 100) }
                        update(OperationUpdate(bytes = transferredBytes, totalBytes = totalBytes,
                            itemsProcessed = itemsDone, totalItems = totalItems, currentItem = name,
                            message = "Verifying: $name" + (percent?.let { " ($it%)" } ?: ""),
                            indeterminate = false, currentItemBytes = checked, currentItemTotalBytes = expectedBytes))
                    }
                    if (name != null) report()
                    while (true) {
                        if (isCancelled()) throw InterruptedException("Transfer cancelled")
                        if (allowSkip && !verificationWanted()) throw VerificationSkipped()
                        val n = input.read(buffer)
                        if (n < 0) break
                        hash.update(buffer, 0, n)
                        checked += n
                        val now = System.nanoTime()
                        if (name != null && now - lastReport >= 300_000_000L) { report(); lastReport = now }
                        if (name != null && now - lastLog >= 5_000_000_000L) {
                            com.infinifolder.storage.DiagnosticLog.record("transfer.verification.progress", "item=$name checkedBytes=$checked expectedBytes=$expectedBytes")
                            lastLog = now
                        }
                    }
                    if (name != null) {
                        report()
                        com.infinifolder.storage.DiagnosticLog.record("transfer.verification.complete", "item=$name checkedBytes=$checked expectedBytes=$expectedBytes")
                    }
                    hash.digest()
                }
            }

            fun verifySource(entry: ResourceEntry) {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                capturedMetadata[entry.ref]?.let { expected ->
                    val now = activeProvider.readTransferMetadata(entry.ref)
                    // Access time can change as a consequence of our own reads.
                    if (now !is ProviderResult.Success || !expected.copy(accessedSeconds=null, accessedNanos=0).matches(now.value))
                        throw IOException("Source permissions or attributes changed during transfer; original retained")
                }
                if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                    val before = sourceChildren.getValue(entry.ref)
                    val after = StorageSafety.complete(activeProvider.list(entry.ref))
                    fun signature(items: List<ResourceEntry>) = items.associate { it.ref to it.metadata }
                    if (signature(before) != signature(after)) throw IOException("Source folder changed; original retained")
                    before.forEach(::verifySource)
                } else if (entry.ref.providerId != "apps") {
                    if (!digest(entry.ref, "source ${entry.metadata.name}", transferSize(entry)).contentEquals(sourceDigests.getValue(entry.ref))) {
                        throw IOException("Source changed during transfer; original retained")
                    }
                }
            }
            fun plannedBytes(entry: ResourceEntry): Long = when {
                shortcutPayloads.containsKey(entry.ref) -> transferSize(entry) ?: 0L
                entry.metadata.isDirectory && (entry.ref.providerId == "apps" || AppsUri.isCategoryUri(entry.uri)) -> 0L
                entry.metadata.isDirectory -> sourceChildren.getValue(entry.ref).sumOf(::plannedBytes)
                else -> entry.metadata.sizeBytes ?: 0L
            }
            totalBytes = transferEntries.sumOf(::plannedBytes).takeIf { it > 0 }
            var lastRateTime = System.currentTimeMillis()
            var bytesSinceLast = 0L

            fun reportProgress(currentItem: String, currentRate: Long, statusMessage: String? = null, torrentPiecesChecked: Int? = null, torrentPiecesTotal: Int? = null) {
                update(
                    OperationUpdate(
                        bytes = transferredBytes,
                        totalBytes = totalBytes,
                        itemsProcessed = itemsDone,
                        totalItems = totalItems,
                        currentItem = currentItem,
                        bytesPerSecond = if (activeTorrentTransfer) torrentStats.get()?.downloadBytesPerSecond ?: currentRate else currentRate,
                        torrentStats = torrentStats.get().takeIf { activeTorrentTransfer },
                        message = statusMessage ?: "${if (move) "Moving" else "Copying"} $currentItem",
                        indeterminate = false,
                        currentItemBytes = activeFileBytes.takeIf { currentItem == activeFileName },
                        currentItemTotalBytes = activeFileTotalBytes.takeIf { currentItem == activeFileName },
                        resumable = activeTorrentTransfer, resumeDestination = activeResumeDestination,
                        remainingBytes = torrentRemainingBytes?.let { remaining ->
                            val other = ((totalBytes ?: 0L) - transferredBytes -
                                ((activeFileTotalBytes ?: 0L) - (activeFileBytes ?: 0L))).coerceAtLeast(0L)
                            remaining + other
                        },
                        torrentPiecesChecked = torrentPiecesChecked, torrentPiecesTotal = torrentPiecesTotal
                    )
                )
            }

            fun copyFileWithProgress(
                source: ResourceEntry,
                destParentRef: ResourceRef
            ): ResourceEntry {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                val transferMetadata = captureMetadata(source)
                torrentStats.set(null)
                activeResumeDestination = null
                activeTorrentTransfer = !useStagedRandomAccessFallback && !move && !createLinks && !partialRecovery && source.ref.providerId == "torrent"
                val requestedName = requestedDestinationName(source)
                val shortcutPayload = shortcutPayloads[source.ref]
                val resumeRouter = if (!useStagedRandomAccessFallback && !move && source.ref.providerId == "torrent")
                    activeProvider as? com.infinifolder.storage.RandomAccessTransferRouter else null
                val resumeCandidate = resumeRouter?.findResumeDestination(source.ref, destParentRef, requestedName)
                var resumedCreate = resumeCandidate?.let { candidate ->
                    when (val stat = activeProvider.stat(candidate.ref)) {
                        is ProviderResult.Success -> if (!stat.value.metadata.isDirectory) candidate to stat else null
                        else -> null
                    }
                }
                if (resumeCandidate != null && resumedCreate == null) {
                    // The saved destination disappeared outside the app. Its old piece
                    // bitmap must not be applied if a new file is created at that path.
                    resumeRouter?.discardResumeState(source.ref, resumeCandidate.ref)
                    resumeRouter?.forgetResumeDestination(source.ref, destParentRef, requestedName)
                }
                var torrentDownload = resumeRouter != null
                activeTorrentTransfer = torrentDownload
                var finalName = resumedCreate?.first?.name?.removeSuffix(".incomplete")
                    ?: availableName(destParentRef, requestedName)
                var targetName = resumedCreate?.first?.name ?: if (torrentDownload) "$finalName.incomplete" else finalName
                if (torrentDownload && resumedCreate != null && !targetName.endsWith(".incomplete", ignoreCase = true)) {
                    val normalizedPartialName = fileSystem?.compatibleName("$targetName.incomplete") ?: "$targetName.incomplete"
                    fileSystem?.requireName(normalizedPartialName)
                    val oldRef = resumedCreate!!.first.ref
                    val renamed = activeProvider.rename(oldRef, normalizedPartialName)
                    if (renamed is ProviderResult.Success) {
                        resumeRouter?.migrateResumeState(source.ref, oldRef, renamed.value.ref)
                        resumedCreate = com.infinifolder.storage.ResumeDestination(renamed.value.ref, normalizedPartialName) to ProviderResult.Success(renamed.value)
                        targetName = normalizedPartialName
                        resumeRouter?.rememberResumeDestination(source.ref, destParentRef, requestedName, renamed.value.ref, normalizedPartialName)
                    } else {
                        throw IOException("Cannot mark the existing torrent partial as incomplete: " +
                            ((renamed as? ProviderResult.Failure)?.message ?: "Rename unsupported"),
                            (renamed as? ProviderResult.Failure)?.cause)
                    }
                }
                if (torrentDownload) {
                    finalName = fileSystem?.compatibleName(finalName) ?: finalName
                    fileSystem?.requireName(finalName)
                    if (resumedCreate == null) {
                        targetName = fileSystem?.compatibleName("$finalName.incomplete") ?: "$finalName.incomplete"
                        fileSystem?.requireName(targetName)
                        val names = destinationNames.getValue(destParentRef)
                        if (targetName.lowercase(java.util.Locale.ROOT) in names) {
                            // An orphaned partial with no resume map is never overwritten.
                            finalName = availableName(destParentRef, requestedName)
                            targetName = fileSystem?.compatibleName("$finalName.incomplete") ?: "$finalName.incomplete"
                            fileSystem?.requireName(targetName)
                            if (targetName.lowercase(java.util.Locale.ROOT) in names) {
                                throw IOException("Cannot choose a unique .incomplete name for '$requestedName'")
                            }
                        }
                    }
                }
                fileSystem?.requireName(targetName)
                if (torrentDownload) destinationNames.getValue(destParentRef).add(finalName.lowercase(java.util.Locale.ROOT))
                activeFileName = targetName
                activeFileBytes = 0L
                torrentRemainingBytes = null
                activeFileTotalBytes = transferSize(source)
                reportProgress(targetName, 0L)

                val createResult = resumedCreate?.second ?: timed("Creating destination: $targetName", targetName) {
                    activeProvider.createFile(destParentRef, targetName,
                        if (shortcutPayload != null) ShortcutInfo.SHORTCUT_MIME else source.metadata.mimeType)
                }
                if (createResult !is ProviderResult.Success) {
                    throw IllegalStateException((createResult as? ProviderResult.Failure)?.message ?: "Failed to create $targetName")
                }
                var createdEntry = createResult.value
                destinationNames.getValue(destParentRef).add(createdEntry.metadata.name.lowercase(java.util.Locale.ROOT))
                resumeRouter?.rememberResumeDestination(source.ref, destParentRef, requestedName, createdEntry.ref, targetName)
                if (torrentDownload) activeResumeDestination = createdEntry.ref

                var recoveryReport: RecoveryReport? = null
                var companionRef: ResourceRef? = null
                var directTorrentBytes: Long? = null
                val directRouter = activeProvider as? com.infinifolder.storage.RandomAccessTransferRouter
                if (!useStagedRandomAccessFallback && !move && !createLinks && !partialRecovery && shortcutPayload == null &&
                    transferRouter?.requiresRandomAccess(source.ref) == true && directRouter != null) {
                    val directProgress = java.util.concurrent.atomic.AtomicLong()
                    val lastDirectReport = java.util.concurrent.atomic.AtomicLong(0L)
                    val directRateTime = java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.uptimeMillis())
                    val directBytesSinceRate = java.util.concurrent.atomic.AtomicLong(0L)
                    val directProgressLock = Any()
                    val lastPieceStatus = java.util.concurrent.atomic.AtomicLong(0L)
                    val startupStatus = java.util.concurrent.atomic.AtomicReference("Preparing torrent download…")
                    val directResult = timed("Receiving torrent directly: $targetName", targetName) {
                        directRouter.transferRandomAccess(source.ref, createdEntry.ref, isCancelled, onBytesWritten = { delta ->
                            val expectedBytes = transferSize(source) ?: Long.MAX_VALUE
                            directProgress.updateAndGet { current -> (current + delta).coerceAtMost(expectedBytes) }
                            directBytesSinceRate.addAndGet(delta)
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastDirectReport.get() >= 300L && lastDirectReport.getAndSet(now) <= now - 300L) {
                                synchronized(directProgressLock) {
                                    val latest = directProgress.get()
                                    val increment = (latest - (activeFileBytes ?: 0L)).coerceAtLeast(0L)
                                    transferredBytes += increment
                                    activeFileBytes = latest
                                    val elapsed = (now - directRateTime.getAndSet(now)).coerceAtLeast(1L)
                                    val rateBytes = directBytesSinceRate.getAndSet(0L)
                                    val rate = rateBytes * 1000L / elapsed
                                    reportProgress(targetName, rate)
                                }
                            }
                        }, onPieceVerification = { verified, total ->
                            val now = android.os.SystemClock.uptimeMillis()
                            if (torrentRemainingBytes == null && (now - lastPieceStatus.get() >= 400L || verified >= total)) {
                                synchronized(directProgressLock) {
                                    if (torrentRemainingBytes == null) {
                                        lastPieceStatus.set(now)
                                        reportProgress(targetName, 0L, startupStatus.get(), verified, total)
                                    }
                                }
                            }
                        }, onBytesRemaining = { remaining ->
                            synchronized(directProgressLock) {
                                val firstReady = torrentRemainingBytes == null
                                torrentRemainingBytes = remaining
                                if (firstReady) {
                                    val existing = ((transferSize(source) ?: 0L) - remaining).coerceAtLeast(0L)
                                    directProgress.set(existing)
                                    activeFileBytes = existing
                                    transferredBytes += existing
                                    reportProgress(targetName, 0L, "Connecting to peers…")
                                }
                            }
                        }, onResumeStatus = { message ->
                            startupStatus.set(message)
                            synchronized(directProgressLock) {
                                if (torrentRemainingBytes == null) reportProgress(targetName, 0L, message)
                            }
                        }, onTorrentStats = { stats ->
                            synchronized(directProgressLock) {
                                torrentStats.set(stats)
                                if (torrentRemainingBytes != null) reportProgress(targetName, stats.downloadBytesPerSecond,
                                    stats.statusMessage())
                            }
                        })
                    }
                    when (directResult) {
                        is ProviderResult.Success -> {
                            directTorrentBytes = directResult.value
                            synchronized(directProgressLock) {
                                val increment = (directResult.value - (activeFileBytes ?: 0L)).coerceAtLeast(0L)
                                transferredBytes += increment
                                activeFileBytes = directResult.value
                            }
                            reportProgress(targetName, 0L)
                            com.infinifolder.storage.DiagnosticLog.record("transfer.torrent.direct-complete",
                                "item=$targetName bytes=${directResult.value} destination=${createdEntry.ref.providerId}")
                        }
                        is ProviderResult.Failure -> {
                            throw IOException("Torrent transfer paused or failed for '$targetName': ${directResult.message}. " +
                                "The partial destination is retained and can be resumed by retrying this copy. Source retained.", directResult.cause)
                        }
                        is ProviderResult.Unsupported -> {
                            // Unknown per-document support (notably SAF) is decided by
                            // opening the actual handle, before any payload is written.
                            try { approveStagedFallback(source, "The created file cannot provide a readable, writable, seekable, resizable handle.") }
                            catch (error: Exception) {
                                if (resumedCreate == null) {
                                    val cleaned = activeProvider.delete(createdEntry.ref)
                                    if (cleaned is ProviderResult.Success) resumeRouter?.forgetResumeDestination(source.ref, destParentRef, requestedName)
                                }
                                throw error
                            }
                            if (resumedCreate == null) {
                                val cleaned = activeProvider.delete(createdEntry.ref)
                                if (cleaned !is ProviderResult.Success) throw IOException("Cannot remove unused transfer placeholder; source retained")
                                destinationNames.getValue(destParentRef).remove(createdEntry.metadata.name.lowercase(java.util.Locale.ROOT))
                                destinationNames.getValue(destParentRef).remove(finalName.lowercase(java.util.Locale.ROOT))
                                resumeRouter?.forgetResumeDestination(source.ref, destParentRef, requestedName)
                            }
                            // Keep an existing partial intact; stream to a fresh final-name file.
                            finalName = availableName(destParentRef, finalName)
                            val stagedTarget = activeProvider.createFile(destParentRef, finalName, source.metadata.mimeType)
                            if (stagedTarget !is ProviderResult.Success) throw IOException((stagedTarget as? ProviderResult.Failure)?.message ?: "Cannot create staged-copy target")
                            createdEntry = stagedTarget.value
                            targetName = createdEntry.metadata.name
                            destinationNames.getValue(destParentRef).add(targetName.lowercase(java.util.Locale.ROOT))
                            torrentDownload = false
                            activeTorrentTransfer = false
                            activeResumeDestination = null
                            activeFileName = targetName
                        }
                    }
                }
                val readResult = if (directTorrentBytes != null) ProviderResult.Success<InputStream>(java.io.ByteArrayInputStream(ByteArray(0))) else try { timed(
                    if (useStagedRandomAccessFallback && transferRouter?.requiresRandomAccess(source.ref) == true)
                        "Downloading to temporary storage before copying: $targetName" else "Opening source: $targetName", targetName) {
                    if (partialRecovery && source.metadata.partialRecoveryAvailable) {
                        when (val recovered = activeProvider.openPartialRecovery(source.ref)) {
                            is ProviderResult.Success -> { recoveryReport = recovered.value.report; ProviderResult.Success(recovered.value.input) }
                            is ProviderResult.Failure -> recovered
                            is ProviderResult.Unsupported -> recovered
                        }
                    } else if (shortcutPayload != null) ProviderResult.Success<InputStream>(shortcutPayload.inputStream())
                    else try { activeProvider.openRead(source.ref) }
                    catch (e: Exception) {
                        if (e is InterruptedException || isCancelled()) throw e
                        ProviderResult.Failure(e.message ?: "Cannot open source", e)
                    }
                } } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot prepare recovery source", e) }
                if (readResult !is ProviderResult.Success) {
                    val originalError = (readResult as? ProviderResult.Failure)?.cause
                    val cleaned = try { activeProvider.delete(createdEntry.ref) } catch (e: Exception) {
                        throw IOException("Source open failed and partial destination cleanup failed: ${e.message}", originalError ?: e)
                    }
                    val detail = (readResult as? ProviderResult.Failure)?.message ?: "Failed to open $targetName"
                    if (cleaned !is ProviderResult.Success) throw IOException("$detail; partial destination cleanup failed", originalError)
                    if (isCancelled()) throw InterruptedException("Transfer cancelled")
                    throw SourceReadException(detail, (readResult as? ProviderResult.Failure)?.cause)
                }

                val writeResult = if (directTorrentBytes != null) ProviderResult.Success<OutputStream>(object : OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                }) else try { timed("Opening destination: $targetName", targetName) { activeProvider.openWrite(createdEntry.ref) } }
                    catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open destination", e) }
                if (writeResult !is ProviderResult.Success) {
                    val closeError = try { readResult.value.close(); null } catch (e: Exception) { e }
                    val cleaned = try { activeProvider.delete(createdEntry.ref) }
                        catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cleanup failed", e) }
                    if (cleaned !is ProviderResult.Success) throw IOException("Destination open failed; partial destination cleanup failed. Source retained.")
                    if (isCancelled()) throw InterruptedException("Transfer cancelled")
                    if (closeError != null) throw IOException("Source close failed; partial destination removed", closeError)
                    throw IllegalStateException((writeResult as? ProviderResult.Failure)?.message ?: "Failed to open destination for $targetName")
                }

                val buffer = ByteArray(128 * 1024)
                val hash = MessageDigest.getInstance("SHA-256")
                var copiedBytes = directTorrentBytes ?: 0L
                var sourceBytesRead = directTorrentBytes ?: 0L
                var sourceReadComplete = directTorrentBytes != null
                var lastTransferLog = System.nanoTime()
                com.infinifolder.storage.DiagnosticLog.record("transfer.start",
                    "name=${source.metadata.name} sourceProvider=${source.ref.providerId} destinationProvider=${createdEntry.ref.providerId} " +
                        "destination=${createdEntry.ref.documentId} expectedBytes=${transferSize(source)}")
                var stage = "reading source"
                val ioTimings = linkedMapOf<String, Long>()
                var failedIoStage: String? = null
                fun <T> timeIo(label: String, work: () -> T): T {
                    val started = System.nanoTime()
                    try { return work() }
                    catch (e: Exception) { if (failedIoStage == null) failedIoStage = label; throw e }
                    finally { ioTimings[label] = (ioTimings[label] ?: 0L) + System.nanoTime() - started }
                }
                val inputStream = object : java.io.FilterInputStream(readResult.value) {
                    override fun close() {
                        stage = "closing source"
                        phase("Finishing source: $targetName", targetName)
                        timeIo("source.close") { super.close() }
                    }
                }
                val outputStream = object : OutputStream() {
                    override fun write(value: Int) = timeIo("destination.write") { writeResult.value.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) =
                        timeIo("destination.write") { writeResult.value.write(bytes, offset, length) }
                    override fun flush() = timeIo("destination.flush") { writeResult.value.flush() }
                    override fun close() {
                        stage = "closing destination"
                        phase("Finishing destination: $targetName", targetName)
                        timeIo("destination.close") { writeResult.value.close() }
                    }
                }
                try {
                    phase("Writing destination: $targetName", targetName)
                    inputStream.use { input ->
                        outputStream.use { output ->
                            try {
                            var r = 0
                            while (directTorrentBytes == null && timeIo("source.read") { input.read(buffer) }.also { r = it } >= 0) {
                                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                                sourceBytesRead += r
                                stage = "checking destination file-size limit"
                                fileSystem?.requireFileFits(source.metadata.name, copiedBytes + r.toLong())
                                stage = "writing destination"
                                output.write(buffer, 0, r)
                                stage = "reading source"
                                hash.update(buffer, 0, r)
                                copiedBytes += r
                                val logNow = System.nanoTime()
                                if (logNow - lastTransferLog >= 5_000_000_000L) {
                                    com.infinifolder.storage.DiagnosticLog.record("transfer.progress", "item=$targetName writtenBytes=$copiedBytes expectedBytes=${transferSize(source)} stage=$stage")
                                    lastTransferLog = logNow
                                }
                                activeFileBytes = copiedBytes
                                transferredBytes += r
                                bytesSinceLast += r
                                val now = System.currentTimeMillis()
                                if (copiedBytes == r.toLong() || now - lastRateTime >= 300) {
                                    val speed = (bytesSinceLast * 1000) / (now - lastRateTime).coerceAtLeast(1)
                                    lastRateTime = now
                                    bytesSinceLast = 0L
                                    reportProgress(targetName, speed)
                                }
                            }
                            sourceReadComplete = transferSize(source)?.let { it == copiedBytes } ?: true
                            com.infinifolder.storage.DiagnosticLog.record("transfer.data-written", "item=$targetName bytes=$copiedBytes expectedBytes=${transferSize(source)}")
                            stage = "flushing destination"
                            phase("Flushing destination: $targetName", targetName)
                            output.flush()
                            stage = "closing destination"
                            } catch (e: Exception) {
                                (writeResult.value as? com.infinifolder.storage.PendingWrite)?.abort()
                                throw e
                            }
                        }
                        stage = "closing source"
                    }
                    stage = "verifying copy"
                    if (transferSize(source) != null && copiedBytes != transferSize(source)) {
                        throw SourceReadException("Source size changed or read was incomplete; original retained")
                    }
                    val expected = if (directTorrentBytes != null) ByteArray(0) else hash.digest()
                    val requiredVerification = move || recoveryReport != null ||
                        source.metadata.recoveryNote != null || activeProvider.recoverySource(source.ref) != null
                    val actual = if (requiredVerification || verificationWanted()) {
                        timed("Verifying copy: $targetName", targetName) {
                            try { digest(createdEntry.ref, targetName, copiedBytes, allowSkip = !requiredVerification) }
                            catch (_: VerificationSkipped) { null }
                        }
                    } else null
                    if (actual == null) com.infinifolder.storage.DiagnosticLog.record("transfer.verification.skipped", "item=$targetName bytes=$copiedBytes policy=copy-setting")
                    else if (directTorrentBytes == null && !actual.contentEquals(expected)) throw IOException("Copy verification failed; original retained")
                    recoveryReport?.let { report ->
                        if (isCancelled()) throw InterruptedException("Recovery cancelled")
                        if (report.declaredSize != copiedBytes || report.sha256 != expected.joinToString("") { "%02x".format(it) })
                            throw IOException("Recovery report does not match the staged output")
                        val reportName = availableName(destParentRef, createdEntry.metadata.name + ".recovery.json")
                        val bytes = report.toJson(createdEntry.metadata.name).toByteArray(Charsets.UTF_8)
                        fileSystem?.requireFileFits(reportName, bytes.size.toLong())
                        phase("Saving recovery report: $reportName", reportName)
                        val created = activeProvider.createFile(destParentRef, reportName, "application/json")
                        if (created !is ProviderResult.Success) throw IOException((created as? ProviderResult.Failure)?.message ?: "Cannot create recovery report")
                        companionRef = created.value.ref
                        val opened = activeProvider.openWrite(created.value.ref)
                        if (opened !is ProviderResult.Success) throw IOException((opened as? ProviderResult.Failure)?.message ?: "Cannot write recovery report")
                        opened.value.use { output ->
                            try {
                                if (isCancelled()) throw InterruptedException("Recovery cancelled")
                                output.write(bytes); output.flush()
                            } catch (e: Exception) { (output as? com.infinifolder.storage.PendingWrite)?.abort(); throw e }
                        }
                        if (!digest(created.value.ref).contentEquals(MessageDigest.getInstance("SHA-256").digest(bytes)))
                            throw IOException("Recovery report verification failed")
                        com.infinifolder.storage.DiagnosticLog.record("recovery.partial-export",
                            "name=${createdEntry.metadata.name} bytes=$copiedBytes report=${created.value.metadata.name} " +
                                "zeroFilled=${report.ranges.filter { it.disposition == RecoveryDisposition.ZERO_FILLED }.sumOf { it.length }} " +
                                "mime=${report.magicMime} mimeMismatch=${report.magicMismatch}")
                        onWarning("Partial recovery '${createdEntry.metadata.name}': " +
                            "${report.ranges.filter { it.disposition == RecoveryDisposition.ZERO_FILLED }.sumOf { it.length }} bytes zero-filled. " +
                            "Byte-range report: ${created.value.metadata.name}. Original contents remain unverified.")
                    }
                    sourceDigests[source.ref] = expected
                    if (recoveryReport == null && (activeProvider.recoverySource(source.ref) != null || source.metadata.recoveryNote != null)) {
                        val warning = "Recovery data is unverified for '${source.metadata.name}'. " +
                            (source.metadata.recoveryNote ?: "Original deleted contents may be fragmented or overwritten.")
                        com.infinifolder.storage.DiagnosticLog.record("recovery.copy-unverified", warning)
                        onWarning(warning)
                    }
                } catch (e: Exception) {
                    val companionCleanup = companionRef?.let { ref -> try { activeProvider.delete(ref) }
                        catch (cleanup: Exception) { ProviderResult.Failure(cleanup.message ?: "Recovery report cleanup failed", cleanup) } }
                    val timeout = generateSequence<Throwable>(e) { it.cause }.take(16).any { it is java.net.SocketTimeoutException }
                    val preserveCompletedWrite = (sourceReadComplete || transferSize(source)?.let { it == copiedBytes } == true) && timeout && !isCancelled() &&
                        (stage == "flushing destination" || stage == "closing destination" || stage == "closing source" || stage == "verifying copy")
                    val cleaned = if (torrentDownload) ProviderResult.Failure("Torrent partial retained for resume")
                        else if (preserveCompletedWrite) ProviderResult.Failure("Completed write retained for inspection; finalization or verification did not succeed")
                        else try { activeProvider.delete(createdEntry.ref) } catch (cleanup: Exception) {
                            ProviderResult.Failure(cleanup.message ?: "Cleanup failed", cleanup)
                        }
                    val cleanupNote = (if (torrentDownload) "Torrent destination retained as .incomplete so verified pieces can resume" else if (preserveCompletedWrite) "Destination retained for inspection after timeout; completion is unconfirmed" else if (cleaned is ProviderResult.Success) "Partial destination removed" else
                        "Partial destination may remain: ${(cleaned as? ProviderResult.Failure)?.message ?: "cleanup unsupported"}") +
                        (if (companionCleanup != null && companionCleanup !is ProviderResult.Success) " Recovery report cleanup failed; report may remain." else "")
                    val largeUnknown = fileSystem?.type == null &&
                        maxOf(transferSize(source) ?: 0L, sourceBytesRead) > com.infinifolder.storage.FileSystemInfo.FAT_MAX_FILE_SIZE
                    val destinationFailure = failedIoStage?.startsWith("destination.") == true
                    val sizeHint = if (largeUnknown && destinationFailure) " Destination filesystem type was not reported. " +
                        "If it uses FAT/FAT32, its single-file limit is 4 GiB minus 1 byte (4,294,967,295 bytes)." else ""
                    val detail = "${failedIoStage ?: stage} for '${source.metadata.name}' after $copiedBytes bytes; " +
                        "${source.ref.providerId} → ${createdEntry.ref.providerId}. $cleanupNote. Source retained.$sizeHint"
                    com.infinifolder.storage.DiagnosticLog.record("transfer.failed",
                        "$detail sourceBytesRead=$sourceBytesRead destination=${createdEntry.ref.documentId}", e)
                    if (e is InterruptedException || isCancelled()) throw e
                    if (cleaned is ProviderResult.Success && (companionCleanup == null || companionCleanup is ProviderResult.Success) && (e is SourceReadException || failedIoStage?.startsWith("source.") == true))
                        throw SourceReadException("$detail ${e.message}", e)
                    throw IOException("$detail ${e.message}", e)
                } finally {
                    com.infinifolder.storage.DiagnosticLog.record("transfer.io",
                        "name=$targetName sourceBytesRead=$sourceBytesRead destinationBytesWritten=$copiedBytes " +
                            ioTimings.entries.joinToString(" ") { "${it.key}Ms=${it.value / 1_000_000}" })
                }
                if (torrentDownload && createdEntry.metadata.name != finalName) {
                    if (isCancelled()) throw java.io.InterruptedIOException("Torrent paused before final rename; completed data retained")
                    phase("Finalizing completed download…", finalName)
                    val partialRef = createdEntry.ref
                    com.infinifolder.storage.DiagnosticLog.record("torrent.publication.start",
                        "destination=${partialRef.providerId} interrupted=${Thread.currentThread().isInterrupted}")
                    val renamed = activeProvider.rename(partialRef, finalName)
                    if (renamed !is ProviderResult.Success) {
                        val failure = renamed as? ProviderResult.Failure
                        com.infinifolder.storage.DiagnosticLog.record("torrent.publication.failed", failure?.message.orEmpty(), failure?.cause)
                        throw IOException("Torrent data is complete but could not rename '${createdEntry.metadata.name}' to '$finalName'; " +
                            "the verified file remains marked .incomplete. ${failure?.message ?: "Rename unsupported"}", failure?.cause)
                    }
                    resumeRouter?.migrateResumeState(source.ref, partialRef, renamed.value.ref)
                    createdEntry = renamed.value
                    resumeRouter?.rememberResumeDestination(source.ref, destParentRef, requestedName, createdEntry.ref, finalName)
                    com.infinifolder.storage.DiagnosticLog.record("torrent.publication.complete", "destination=${createdEntry.ref.providerId}")
                }
                deferTorrentResumeCleanup(
                    resumeRouter, source.ref, destParentRef, requestedName,
                    destination = createdEntry.ref,
                    clearPieceState = directTorrentBytes != null
                )
                preserveMetadata(source, createdEntry.ref, transferMetadata)
                return createdEntry
            }

            fun createDestinationDirectory(source: ResourceEntry, parent: ResourceRef): ResourceEntry {
                val resumeRouter = if (!useStagedRandomAccessFallback && !move && source.ref.providerId == "torrent")
                    activeProvider as? com.infinifolder.storage.RandomAccessTransferRouter else null
                val resumeCandidate = resumeRouter?.findResumeDestination(source.ref, parent, source.metadata.name)
                val resumed = resumeCandidate?.let { candidate ->
                    when (val stat = activeProvider.stat(candidate.ref)) {
                        is ProviderResult.Success -> if (stat.value.metadata.isDirectory) candidate to stat else null
                        else -> null
                    }
                }
                if (resumeCandidate != null && resumed == null) {
                    resumeRouter?.forgetResumeDestination(source.ref, parent, source.metadata.name)
                }
                val name = resumed?.first?.name ?: availableName(parent, source.metadata.name)
                fileSystem?.requireName(name)
                val result = resumed?.second ?: timed("Creating folder: $name", name) { activeProvider.createDirectory(parent, name) }
                if (result is ProviderResult.Success) {
                    resumeRouter?.rememberResumeDestination(source.ref, parent, source.metadata.name, result.value.ref, name)
                    deferTorrentResumeCleanup(resumeRouter, source.ref, parent, source.metadata.name)
                    destinationNames.getValue(parent).add(result.value.metadata.name.lowercase(java.util.Locale.ROOT))
                    destinationNames[result.value.ref] = if (resumed != null) timed("Checking resumed folder", name) {
                        StorageSafety.complete(activeProvider.list(result.value.ref))
                            .map { it.metadata.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
                    } else mutableSetOf()
                    return result.value
                }
                val failure = result as? ProviderResult.Failure
                val message = "Cannot create destination folder '$name' (${parent.providerId}): " +
                    (failure?.message ?: "Folder creation is unsupported") +
                    ". File copying into this folder has not started; this source folder was retained."
                com.infinifolder.storage.DiagnosticLog.record("transfer.directory.failed",
                    "$message destination=${parent.documentId} filesystem=${fileSystem?.type} " +
                        "availableBytes=${fileSystem?.availableBytes} readOnly=${fileSystem?.readOnly}", failure?.cause)
                // A provider may fail after partially creating a directory. Never delete/retry by guessed name.
                throw IOException(message, failure?.cause)
            }

            fun copyAppCategoryAsLinks(
                sourceDir: ResourceEntry,
                destParentRef: ResourceRef
            ): ResourceEntry {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                val createdDir = createDestinationDirectory(sourceDir, destParentRef)
                val createdFresh = true
                try {
                    val categoryName = AppsUri.getCategoryName(sourceDir.uri) ?: sourceDir.metadata.name
                    // 1. Write the category icon to .icon.png inside the created directory
                    if (AppsUri.isCategoryUri(sourceDir.uri)) {
                        val iconBytes = AppCategories.renderCategoryIconPng(categoryName)
                        val iconCreate = activeProvider.createFile(createdDir.ref, ".icon.png", "image/png")
                        if (iconCreate is ProviderResult.Success) {
                            val iconWrite = activeProvider.openWrite(iconCreate.value.ref)
                            if (iconWrite is ProviderResult.Success) {
                                iconWrite.value.use { output ->
                                    output.write(iconBytes)
                                    output.flush()
                                }
                                transferredBytes += iconBytes.size
                            }
                        }
                    }

                    // 2. Populate directory with application shortcuts for every app in the category
                    val children = sourceChildren.getValue(sourceDir.ref)
                    run {
                        val usedNames = mutableSetOf<String>()
                        for (child in children) {
                            if (isCancelled()) throw InterruptedException("Transfer cancelled")
                            if (child.metadata.isDirectory && !child.metadata.isSymbolicLink) {
                                copyAppCategoryAsLinks(child, createdDir.ref)
                            } else {
                                val baseName = child.metadata.name.removeSuffix(".apk").removeSuffix(".lnk").removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION).trim()
                                    .ifEmpty { AppsUri.getPackageName(child.uri) ?: "Application" }
                                var linkFileName = "$baseName${ShortcutInfo.SHORTCUT_EXTENSION}"
                                var suffix = 2
                                while (!usedNames.add(linkFileName.lowercase())) {
                                    linkFileName = "$baseName ($suffix)${ShortcutInfo.SHORTCUT_EXTENSION}"
                                    suffix++
                                }
                                reportProgress(linkFileName, 0L)
                                val shortcutInfo = ShortcutInfo(
                                    targetUri = child.uri.toString(),
                                    targetPath = "Installed Applications / $categoryName / $baseName",
                                    displayNameOverride = baseName,
                                    targetMimeType = child.metadata.mimeType,
                                    targetIsDirectory = false
                                )
                                val jsonBytes = shortcutInfo.toJson().toByteArray(Charsets.UTF_8)
                                val createLink = activeProvider.createFile(createdDir.ref, linkFileName, ShortcutInfo.SHORTCUT_MIME)
                                if (createLink !is ProviderResult.Success) {
                                    throw IllegalStateException((createLink as? ProviderResult.Failure)?.message ?: "Failed to create $linkFileName")
                                }
                                val writeLink = activeProvider.openWrite(createLink.value.ref)
                                if (writeLink !is ProviderResult.Success) {
                                    try { activeProvider.delete(createLink.value.ref) } catch (_: Exception) {}
                                    throw IllegalStateException((writeLink as? ProviderResult.Failure)?.message ?: "Failed to write $linkFileName")
                                }
                                writeLink.value.use { output ->
                                    output.write(jsonBytes)
                                    output.flush()
                                }
                                transferredBytes += jsonBytes.size
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (createdFresh) {
                        try { activeProvider.delete(createdDir.ref) } catch (_: Exception) {}
                    }
                    throw e
                }
                return createdDir
            }

            fun copyReadableFile(source: ResourceEntry, parent: ResourceRef): ResourceEntry? = try {
                copyFileWithProgress(source, parent)
            } catch (e: SourceReadException) {
                sourceChange(e)?.let {
                    com.infinifolder.storage.DiagnosticLog.record("transfer.source-changed", "stopped=true completedFilesRetained=true", it)
                    throw it
                }
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                skipped(source, e.message ?: "source read failed")
                null
            }

            fun copyTreeRecursively(
                sourceDir: ResourceEntry,
                destParentRef: ResourceRef
            ): ResourceEntry {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                if (sourceDir.ref.providerId == "apps" || AppsUri.isCategoryUri(sourceDir.uri)) {
                    return copyAppCategoryAsLinks(sourceDir, destParentRef)
                }
                val transferMetadata = captureMetadata(sourceDir)
                val createdDir = createDestinationDirectory(sourceDir, destParentRef)
                try {
                    val children = sourceChildren.getValue(sourceDir.ref)
                    run {
                        for (child in children) {
                            if (isCancelled()) throw InterruptedException("Transfer cancelled")
                            if (child.metadata.isDirectory && !child.metadata.isSymbolicLink) {
                                copyTreeRecursively(child, createdDir.ref)
                            } else {
                                copyReadableFile(child, createdDir.ref)
                            }
                        }
                    }
                    // Apply directory restrictions/default ACLs only after children
                    // have their contents and their own attributes in place.
                    preserveMetadata(sourceDir, createdDir.ref, transferMetadata)
                } catch (e: Exception) {
                    sourceChange(e)?.let { throw it }
                    // Torrent children may contain verified pieces and a persistent
                    // resume map. Keep that subtree so retrying can continue in place.
                    if (sourceDir.ref.providerId != "torrent") {
                        try { activeProvider.delete(createdDir.ref) } catch (_: Exception) {}
                    }
                    throw e
                }
                return createdDir
            }

            val destinations = mutableListOf<ResourceRef>()
            for (entry in transferEntries) {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                currentSourceRoot = entry.ref
                reportProgress(entry.metadata.name, 0L)
                val copied = if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                    copyTreeRecursively(entry, destRef)
                } else {
                    copyReadableFile(entry, destRef)
                }
                if (copied == null) { itemsDone++; continue }
                destinations += copied.ref
                if (move && entry.ref in incompleteRoots) {
                    onWarning("Source '${entry.metadata.name}' retained because contents or metadata could not be preserved completely.")
                }
                if (move && entry.ref !in incompleteRoots) {
                    timed("Rechecking source: ${entry.metadata.name}", entry.metadata.name) { verifySource(entry) }
                    if (isCancelled()) throw InterruptedException("Transfer cancelled")
                    val removed = timed("Removing verified original: ${entry.metadata.name}", entry.metadata.name) { activeProvider.delete(entry.ref) }
                    if (removed !is ProviderResult.Success) {
                        throw IOException("Copy completed, but original removal failed: " +
                            ((removed as? ProviderResult.Failure)?.message ?: "Delete unsupported"))
                    }
                }
                itemsDone++
                reportProgress(entry.metadata.name, 0L)
            }
            if (isCancelled()) throw InterruptedException("Transfer cancelled before container publication")
            if (batch != null) timed("Publishing container batch") { batch.commit() }
            pendingTorrentResumeCleanup.values.forEach { cleanup ->
                cleanup.router.forgetResumeDestination(cleanup.source, cleanup.parent, cleanup.requestedName)
                if (cleanup.clearPieceState && cleanup.destination != null) {
                    cleanup.router.finishResume(cleanup.source, cleanup.destination)
                }
            }
            return destinations
        } finally { batch?.close() }
    }

    fun batchDelete(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        update: (OperationUpdate) -> Unit,
        isCancelled: () -> Boolean
    ) {
        if (entries.isEmpty()) return
        val batches = linkedMapOf<ResourceRef, com.infinifolder.storage.StorageWriteBatch?>()
        data class Scope(val storage: StorageProvider, val staged: Boolean)
        fun checkCancelled() { if (isCancelled()) throw InterruptedException("Delete cancelled") }
        fun phase(message: String, done: Int = 0, total: Int = entries.size) {
            update(OperationUpdate(itemsProcessed = done, totalItems = total, message = message, indeterminate = true))
        }
        fun scope(entry: ResourceEntry): Scope {
            val backing = provider.backingResource(entry.ref) ?: return Scope(provider, false)
            if (!batches.containsKey(backing)) {
                checkCancelled()
                val parent = when (val result = provider.parent(entry.ref)) {
                    is ProviderResult.Success -> result.value ?: throw IOException("Cannot delete a container root")
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> {
                        // An optional batch hook must not remove normal delete
                        // support from a backend without parent lookup.
                        batches[backing] = null
                        return Scope(provider, false)
                    }
                }
                phase("Preparing container changes…")
                batches[backing] = when (val result = provider.beginWriteBatch(parent, isCancelled)) {
                    is ProviderResult.Success -> result.value
                    is ProviderResult.Unsupported -> null
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                }
            }
            val batch = batches[backing]
            return Scope(batch?.provider ?: provider, batch != null)
        }
        try {
            // Snapshot each selected container once, then resolve every selected
            // tree before deleting anything. Failed/partial listings abort all
            // unpublished sessions; links are never traversed.
            val scopes = entries.map { it to scope(it) }
            phase("Checking folders before deletion…")
            val ordered = mutableListOf<Pair<ResourceEntry, Scope>>()
            val visited = mutableSetOf<ResourceRef>()
            fun collect(entry: ResourceEntry, scope: Scope) {
                checkCancelled()
                if (!visited.add(entry.ref)) return
                if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                    StorageSafety.complete(scope.storage.list(entry.ref)).forEach { collect(it, scope) }
                }
                ordered += entry to scope
            }
            scopes.forEach { (entry, scope) -> collect(entry, scope) }

            val total = ordered.size
            com.infinifolder.storage.DiagnosticLog.record("delete.plan",
                "items=$total containers=${batches.values.count { it != null }}")
            val startTime = System.nanoTime()
            var directDeletes = 0
            for ((idx, item) in ordered.withIndex()) {
                checkCancelled()
                val (entry, scope) = item
                val started = System.nanoTime()
                val result = scope.storage.delete(entry.ref)
                val alreadyMissing = (result as? ProviderResult.Failure)?.cause
                    ?.let { failure -> generateSequence(failure) { it.cause }.any { it is java.nio.file.NoSuchFileException } }
                    ?: false
                com.infinifolder.storage.DiagnosticLog.record("delete.entry",
                    "name=${entry.metadata.name} provider=${entry.ref.providerId} staged=${scope.staged} " +
                        "elapsedMs=${(System.nanoTime() - started) / 1_000_000} success=${result is ProviderResult.Success || alreadyMissing}" +
                        if (alreadyMissing) " alreadyMissing=true" else "")
                if (result !is ProviderResult.Success && !alreadyMissing) {
                    val note = if (scope.staged) {
                        "Unpublished changes to this container will be discarded." +
                            if (directDeletes > 0) " Earlier items in other locations have already been deleted." else ""
                    } else "Some earlier items may already have been deleted."
                    throw IOException("Failed to delete ${entry.metadata.name}: " +
                        ((result as? ProviderResult.Failure)?.message ?: "Delete unsupported") + ". $note")
                }
                if (!scope.staged) directDeletes++
                val elapsedMs = ((System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)
                val speed = ((idx + 1) * 1000L) / elapsedMs
                update(OperationUpdate(itemsProcessed = idx + 1, totalItems = total, currentItem = entry.metadata.name,
                    message = "Removing ${idx + 1} of $total ($speed items/s)", indeterminate = false))
            }
            checkCancelled()
            var committedContainers = 0
            batches.values.filterNotNull().forEach { batch ->
                try {
                    checkCancelled()
                    phase("Saving container changes…", total, total)
                    batch.commit()
                }
                catch (e: Exception) {
                    if (committedContainers > 0 || directDeletes > 0) throw IOException(
                        "${e.message ?: "Cannot save container changes"}. Earlier deletions in other locations are already committed.", e)
                    throw e
                }
                committedContainers++
            }
            update(OperationUpdate(itemsProcessed = total, totalItems = total,
                message = "Deleted $total item${if (total == 1) "" else "s"}", indeterminate = false))
        } finally {
            batches.values.filterNotNull().asReversed().forEach { batch ->
                runCatching { batch.close() }.onFailure {
                    com.infinifolder.storage.DiagnosticLog.record("delete.batch.cleanup-failed", error = it)
                }
            }
        }
    }
}
