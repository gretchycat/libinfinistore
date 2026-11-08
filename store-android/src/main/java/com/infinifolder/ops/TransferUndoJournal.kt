package com.infinifolder.ops

import com.infinifolder.model.*
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageSafety
import java.io.IOException
import java.security.MessageDigest

class TransferCancelledException(message: String, cause: Throwable) : InterruptedException(message) {
    init { initCause(cause) }
}
class TransferRollbackException(message: String, cause: Throwable) : IOException(message, cause)

/** One live copy/move only. Records exact created identities; never guesses paths or deletes a merged tree. */
internal class TransferUndoJournal(
    private val sourceProvider: StorageProvider,
    private val destinationProvider: () -> StorageProvider,
    private val move: Boolean,
    eligible: List<ResourceEntry>
) {
    private class Root(val source: ResourceEntry) {
        val nodes = linkedMapOf<ResourceRef, Node>()
        var originalParent: ResourceRef? = null
        var removalStarted = false
        var started = false
    }
    private class Node(val source: ResourceEntry, val destination: ResourceEntry, val parent: ResourceRef,
        val metadata: TransferMetadata?) {
        var hash: ByteArray? = if (source.metadata.isDirectory) null else MessageDigest.getInstance("SHA-256").digest()
        var size = 0L
        var modified: Long? = null
        var serverSideUnhashed = false
    }
    private val roots = eligible.map { Root(it) }
    private var current: Root? = null
    fun begin(source: ResourceEntry) {
        current = roots.firstOrNull { !it.started && it.source.ref == source.ref && it.source.metadata.name == source.metadata.name }
            ?.also { it.started = true }
    }
    fun ownsCurrent() = current != null
    fun created(source: ResourceEntry, destination: ResourceEntry, parent: ResourceRef, metadata: TransferMetadata?) {
        current?.nodes?.put(destination.ref, Node(source, destination, parent, metadata))
    }
    fun written(source: ResourceRef, hash: ByteArray, size: Long) {
        current?.nodes?.values?.lastOrNull { it.source.ref == source }?.let { it.hash = hash.copyOf(); it.size = size }
    }
    fun serverSideWritten(source: ResourceRef, size: Long) {
        current?.nodes?.values?.lastOrNull { it.source.ref == source }?.let {
            it.hash = null
            it.size = size
            it.serverSideUnhashed = true
        }
    }
    fun seal(source: ResourceRef) {
        current?.nodes?.values?.lastOrNull { it.source.ref == source }?.let { node ->
            node.modified = (destinationProvider().stat(node.destination.ref) as? ProviderResult.Success)?.value?.metadata?.modifiedAtMillis
        }
    }
    private fun originalParent(source: ResourceEntry): ResourceRef = when (val parent = sourceProvider.parent(source.ref)) {
        is ProviderResult.Success -> parent.value ?: throw IOException("Cannot undo a move of a storage root; original retained")
        is ProviderResult.Failure -> throw IOException("Cannot establish the original folder for undo: ${parent.message}", parent.cause)
        else -> throw IOException("This source cannot report its parent for move undo; original retained")
    }
    fun prepareMoves() {
        if (move) roots.forEach { it.originalParent = originalParent(it.source) }
    }
    fun beforeRemoval(source: ResourceEntry) {
        val root = roots.firstOrNull { it.source.ref == source.ref } ?: return
        if (originalParent(source) != root.originalParent) throw IOException("Source parent changed during move; original retained")
        root.removalStarted = true
    }
    private fun <T> required(result: ProviderResult<T>, action: String): T = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Failure -> throw IOException("$action: ${result.message}", result.cause)
        else -> throw IOException("$action is unsupported")
    }
    private fun children(provider: StorageProvider, parent: ResourceRef) = StorageSafety.complete(provider.list(parent))
    private fun find(provider: StorageProvider, parent: ResourceRef, name: String): ResourceEntry? {
        val list = children(provider, parent)
        list.firstOrNull { it.metadata.name == name }?.let { return it }
        if (list.any { it.metadata.name.equals(name, true) }) throw IOException("Name conflict at '$name'; retained for review")
        return null
    }
    private fun digest(provider: StorageProvider, ref: ResourceRef): Pair<ByteArray, Long> {
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        required(provider.openRead(ref), "Read for undo verification").use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                hash.update(buffer, 0, count); size += count
            }
        }
        return hash.digest() to size
    }
    private fun matches(provider: StorageProvider, entry: ResourceEntry, node: Node): Boolean {
        if (entry.metadata.isDirectory || entry.metadata.isSymbolicLink) return false
        val expected = node.hash ?: return false
        val (actual, size) = digest(provider, entry.ref)
        return size == node.size && actual.contentEquals(expected)
    }
    private fun destination(node: Node): ResourceEntry? {
        val entry = find(destinationProvider(), node.parent, node.destination.metadata.name) ?: return null
        if (entry.ref != node.destination.ref || entry.metadata.isSymbolicLink)
            throw IOException("Destination identity changed: ${entry.uri}; retained")
        return entry
    }
    private fun restore(root: Root, node: Node, parent: ResourceRef) {
        val old = find(sourceProvider, parent, node.source.metadata.name)
        if (old?.metadata?.isSymbolicLink == true) throw IOException("Original path is now a link: ${old.uri}; copy retained")
        if (node.source.metadata.isDirectory) {
            if (old != null && !old.metadata.isDirectory) throw IOException("Original folder path is occupied: ${old.uri}")
            val folder = old ?: required(sourceProvider.createDirectory(parent, node.source.metadata.name), "Restore original folder")
            if (folder.metadata.name != node.source.metadata.name || !folder.metadata.isDirectory || folder.metadata.isSymbolicLink)
                throw IOException("Original folder was not restored at the requested name: ${folder.uri}; copy retained")
            // Parent identities are backend-owned and can change on SAF recreation.
            root.nodes.values.filter { it.parent == node.destination.ref }.forEach { restore(root, it, folder.ref) }
            if (old == null) restoreMetadata(folder.ref, node.metadata)
        } else {
            if (old != null) {
                if (!matches(sourceProvider, old, node)) throw IOException("Original path has different contents: ${old.uri}; copy retained")
                return // Never overwrite an existing original, including its current attributes.
            }
            val copied = destination(node) ?: throw IOException("The only copy disappeared: ${node.destination.uri}")
            if (!matches(destinationProvider(), copied, node)) throw IOException("Copied data changed: ${copied.uri}; retained")
            val restored = required(sourceProvider.createFile(parent, node.source.metadata.name, node.source.metadata.mimeType), "Restore original file")
            if (restored.metadata.name != node.source.metadata.name || restored.metadata.isDirectory || restored.metadata.isSymbolicLink)
                throw IOException("Original file was not restored at the requested name: ${restored.uri}; copy retained")
            required(destinationProvider().openRead(copied.ref), "Read moved copy").use { input ->
                required(sourceProvider.openWrite(restored.ref), "Restore original contents").use { output ->
                    io.github.gretchycat.infinistream.copy(input, output)
                    output.flush()
                }
            }
            if (!matches(sourceProvider, restored, node)) throw IOException("Restored contents could not be verified: ${restored.uri}; copy retained")
            restoreMetadata(restored.ref, node.metadata)
        }
    }
    private fun restoreMetadata(ref: ResourceRef, metadata: TransferMetadata?) {
        if (metadata == null) return
        required(sourceProvider.applyTransferMetadata(ref, metadata), "Restore original attributes")
        if (!metadata.matches(required(sourceProvider.readTransferMetadata(ref), "Verify restored attributes")))
            throw IOException("Restored attributes could not be verified; copy retained")
    }
    /** Caller masks its transfer cancellation/interrupt while restoring and cleaning up. */
    fun rollback(report: (String) -> Unit): List<String> {
        val failures = mutableListOf<String>()
        com.infinifolder.storage.DiagnosticLog.record("transfer.undo.start", "move=$move roots=${roots.size}")
        for (root in roots.asReversed()) {
            if (root.nodes.isEmpty()) continue
            report("Undoing ${if (move) "move" else "copy"}: ${root.source.metadata.name}")
            if (root.removalStarted) {
                try { restore(root, root.nodes.values.first(), root.originalParent!!) }
                catch (e: Exception) {
                    failures += "${root.source.metadata.name}: ${e.message}. Destination copies retained at ${root.nodes.values.first().destination.uri}"
                    continue // Never remove the only verified copy after a restore conflict/failure.
                }
            }
            try {
                if (destination(root.nodes.values.first()) == null) continue
            } catch (e: Exception) {
                failures += e.message ?: "Cannot inspect undo destination"
                continue
            }
            for (node in root.nodes.values.toList().asReversed()) {
                try {
                    val entry = destination(node) ?: continue
                    if (entry.metadata.isDirectory) {
                        if (!node.source.metadata.isDirectory || children(destinationProvider(), entry.ref).isNotEmpty())
                            throw IOException("Folder has remaining or newly added contents: ${entry.uri}; retained")
                    } else {
                        if (node.modified != null && entry.metadata.modifiedAtMillis != null && node.modified != entry.metadata.modifiedAtMillis)
                            throw IOException("File modification state changed: ${entry.uri}; retained")
                        if (node.serverSideUnhashed)
                            throw IOException("Server-side copy has no client-side content digest; retained for safety at ${entry.uri}")
                        if (!matches(destinationProvider(), entry, node)) throw IOException("File contents changed: ${entry.uri}; retained")
                    }
                    required(destinationProvider().delete(entry.ref), "Remove copied destination ${entry.uri}")
                } catch (e: Exception) { failures += e.message ?: "Undo cleanup failed for ${node.destination.uri}" }
            }
        }
        com.infinifolder.storage.DiagnosticLog.record("transfer.undo.finished", "move=$move failures=${failures.size}")
        return failures
    }
}
