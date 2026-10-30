package com.infinifolder.torrent

import com.infinifolder.storage.TransferCheckpointStore
import com.infinifolder.storage.InvalidTransferCheckpointException
import com.infinifolder.storage.CheckpointWriteRejectedException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.BitSet
import java.util.Properties

internal data class TorrentCheckpointIdentity(
    val destination: String,
    val hash: String,
    val path: String,
    val pieceSize: Long,
    val totalPieces: Int,
    val torrentSize: Long,
    val fileStart: Long,
    val fileSize: Long
)

/** Portable checkpoint encoding; malformed state is never authority for skipping hashes. */
internal object TorrentCheckpointCodec {
    const val FORMAT = "infinifolder-torrent-checkpoint-1"
    private const val MAX_BYTES = 4 * 1024 * 1024
    fun properties(bytes: ByteArray): Properties? = runCatching {
        require(bytes.size <= MAX_BYTES)
        Properties().apply { bytes.inputStream().use(::load) }
    }.getOrNull()

    private fun digest(state: Properties): String {
        val canonical = state.stringPropertyNames().filter { it != "checksum" }.sorted()
            .joinToString("\u0000") { "$it=${state.getProperty(it)}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun encode(identity: TorrentCheckpointIdentity, pieces: BitSet, clean: Boolean, fingerprint: String?, writeJournal: Boolean = false): ByteArray {
        val state = Properties().apply {
            setProperty("format", FORMAT)
            setProperty("destination", identity.destination)
            setProperty("hash", identity.hash)
            setProperty("path", identity.path)
            setProperty("pieceSize", identity.pieceSize.toString())
            setProperty("clean", (clean && fingerprint != null).toString())
            setProperty("fingerprint", fingerprint.orEmpty())
            if (writeJournal) setProperty("writeJournal", "unverified-pieces-only-1")
            setProperty("pieces", Base64.getEncoder().encodeToString(pieces.toByteArray()))
        }
        state.setProperty("checksum", digest(state))
        return ByteArrayOutputStream().apply { state.store(this, null) }.toByteArray()
    }

    fun decode(bytes: ByteArray?, identity: TorrentCheckpointIdentity, fingerprint: String?, sized: Boolean): BitSet = runCatching {
        if (bytes == null || fingerprint == null || !sized) return BitSet()
        val state = properties(bytes) ?: return BitSet()
        if (state.getProperty("format") != FORMAT || state.getProperty("destination") != identity.destination ||
            state.getProperty("hash") != identity.hash || state.getProperty("path") != identity.path ||
            state.getProperty("pieceSize") != identity.pieceSize.toString() ||
            state.getProperty("checksum") != digest(state)) return BitSet()
        val journal = state.getProperty("clean") == "false" &&
            state.getProperty("writeJournal") == "unverified-pieces-only-1"
        val recorded = state.getProperty("fingerprint", "")
        // Active writes change whole-file mtime, even though the saved verified
        // pieces are untouched. The journal excludes every piece before mutation.
        // Still require the same destination and allocated size; clean snapshots
        // retain the stricter mtime check for edits made outside the downloader.
        if (journal) {
            if (recorded.isEmpty() || recorded.substringBeforeLast(':') != fingerprint.substringBeforeLast(':')) return BitSet()
        } else if (state.getProperty("clean") != "true" || recorded != fingerprint) return BitSet()
        val pieces = BitSet.valueOf(Base64.getDecoder().decode(state.getProperty("pieces", "")))
        if (pieces.length() > identity.totalPieces) return BitSet()
        var piece = pieces.nextSetBit(0)
        while (piece >= 0) {
            val start = piece.toLong() * identity.pieceSize
            val end = minOf(identity.torrentSize, start + identity.pieceSize)
            if (start < identity.fileStart || end > identity.fileStart + identity.fileSize) pieces.clear(piece)
            piece = pieces.nextSetBit(piece + 1)
        }
        pieces
    }.getOrElse { BitSet() }
}

/** All methods are called under the transfer's destination I/O lock. */
internal class TorrentVerificationCheckpoint(
    private val identity: TorrentCheckpointIdentity,
    private val store: TransferCheckpointStore?,
    private val flush: () -> Unit,
    sized: Boolean,
    private val report: (String, Exception) -> Unit = { _, _ -> }
) {
    val trustedPieces: BitSet
    val loadReason: String
    var savedPieceCount: Int = 0
        private set
    private val pieces: BitSet
    private var usable = store != null
    private var stableFingerprint: String? = null
    private var checkpointSaves = 0L
    private var checkpointSaveNanos = 0L
    val persistenceMillis: Long get() = checkpointSaveNanos / 1_000_000
    val persistenceCount: Long get() = checkpointSaves
    private var dirty = false
    private var mayHaveCleanCheckpoint = false

    init {
        var trusted = BitSet()
        var reason = "unavailable"
        try {
            val bytes = store?.read()
            val state = bytes?.let(TorrentCheckpointCodec::properties)
            mayHaveCleanCheckpoint = state?.getProperty("format") == TorrentCheckpointCodec.FORMAT &&
                state.getProperty("destination") == identity.destination &&
                (state.getProperty("clean") == "true" || state.getProperty("writeJournal") == "unverified-pieces-only-1")
            val fingerprint = store?.fingerprint()
            stableFingerprint = fingerprint
            trusted = TorrentCheckpointCodec.decode(bytes, identity, fingerprint, sized)
            reason = when {
                store == null -> "unsupported-destination"
                bytes == null -> "missing-sidecar"
                state == null -> "malformed-sidecar"
                !sized -> "destination-size-changed"
                fingerprint == null -> "destination-metadata-unavailable"
                !trusted.isEmpty -> if (state.getProperty("writeJournal") == "unverified-pieces-only-1")
                    "interrupted-download-journal" else "matched"
                state.getProperty("clean") != "true" -> "interrupted-write-or-incomplete-checkpoint"
                state.getProperty("fingerprint") != fingerprint -> "destination-metadata-changed"
                else -> "empty-or-invalid-checkpoint"
            }
        } catch (error: Exception) {
            if (error is InvalidTransferCheckpointException) {
                usable = false // Preserve oversized junk; it cannot be a valid checkpoint.
                mayHaveCleanCheckpoint = false
            } else mayHaveCleanCheckpoint = true // Unknown read failure is not absence.
            reason = if (error is InvalidTransferCheckpointException) "oversized-sidecar" else "sidecar-read-failed"
            report("load-failed", error)
        }
        loadReason = reason
        trustedPieces = trusted
        pieces = trusted.clone() as BitSet
        savedPieceCount = trusted.cardinality()
    }

    fun beforeWrite(offset: Long, count: Int) {
        if (count <= 0) return
        val first = ((identity.fileStart + offset) / identity.pieceSize).toInt()
        val last = ((identity.fileStart + offset + count - 1) / identity.pieceSize).toInt()
        val affected = pieces.nextSetBit(first).let { it >= 0 && it <= last }
        pieces.clear(first, last + 1)
        // Persist the reduced bitmap BEFORE touching the data. Untouched verified
        // pieces remain durable even if the process dies mid-block. A subsequent
        // write to another saved piece must publish its invalidation as well.
        if (usable && (!dirty || affected)) try {
            // A previous piece-save may have failed during data flush. Never
            // grant journal authority to those bits until the data is durable.
            flush()
            publish(false)
        }
        catch (error: Exception) {
            // A failed acknowledgement may still have committed the journal.
            // Block this write and force a fresh publication on retry.
            dirty = false
            if (mayHaveCleanCheckpoint) throw IOException("Cannot invalidate torrent checkpoint before writing", error)
            usable = false
            report("disabled", error)
        }
        stableFingerprint = null
    }

    fun checked(index: Int, valid: Boolean) {
        pieces.set(index, valid)
        // Cached startup results need no recalculation or rewrite. Every newly
        // successful file hash is flushed and persisted immediately.
        if (valid && !trustedPieces.get(index)) persistPiece()
    }

    fun pieceVerified(index: Int) {
        pieces.set(index)
        // Incoming data takes the same durable path as a startup file recheck.
        persistPiece()
    }

    private fun persistPiece() {
        if (!usable) return
        val started = System.nanoTime()
        try { save() } catch (error: Exception) { report("piece-save-failed", error) }
        finally { checkpointSaves++; checkpointSaveNanos += System.nanoTime() - started }
    }

    fun save() {
        if (!usable) return
        flush()
        publish(true)
    }

    private fun publish(clean: Boolean) {
        if (!usable) return
        val fingerprint = stableFingerprint ?: store?.fingerprint()?.also { stableFingerprint = it }
        // Both clean snapshots and write journals must identify the destination.
        // Metadata failures must not replace an existing usable checkpoint.
        if (fingerprint == null)
            throw IOException("Destination metadata unavailable; previous torrent checkpoint retained")
        val actualClean = clean
        val bytes = TorrentCheckpointCodec.encode(identity, pieces, actualClean, fingerprint, writeJournal = !clean)
        val previouslyMayHaveClean = mayHaveCleanCheckpoint
        val previouslyDirty = dirty
        // Either payload grants authority to skip hashes. An uncertain commit
        // must force invalidation before any subsequent mutation.
        mayHaveCleanCheckpoint = true
        dirty = false
        try { store?.write(bytes) }
        catch (error: CheckpointWriteRejectedException) {
            // Preflight rejection submitted no new clean payload. Preserve earlier
            // uncertainty, but do not invent a clean cache for foreign/denied files.
            mayHaveCleanCheckpoint = previouslyMayHaveClean
            dirty = previouslyDirty
            if (!previouslyMayHaveClean) usable = false
            throw error
        }
        dirty = !actualClean
        savedPieceCount = pieces.cardinality()
    }

    fun complete() { store?.delete() }
}
