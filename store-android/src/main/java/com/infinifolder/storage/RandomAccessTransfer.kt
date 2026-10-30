package com.infinifolder.storage

import com.infinifolder.archive.BlockDevice
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceCapability
import com.infinifolder.model.ResourceRef
import java.io.IOException

/** A provider that can receive random-offset content directly from a producer. */
interface RandomAccessTransferSource {
    fun pendingResumeTransfers(): List<PendingResumeTransfer> = emptyList()
    fun claimAutoResume(source: ResourceRef, destination: ResourceRef): Boolean = true
    /** User-stopped sessions remain resumable, but must not restart automatically. */
    fun setAutoResume(destination: ResourceRef, enabled: Boolean) = Unit
    fun findResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String): ResumeDestination? = null
    fun rememberResumeDestination(
        source: ResourceRef,
        destinationParent: ResourceRef,
        requestedName: String,
        destination: ResourceRef,
        destinationName: String
    ) = Unit
    fun forgetResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String) = Unit
    fun discardResumeState(source: ResourceRef, destination: ResourceRef) = Unit
    fun migrateResumeState(source: ResourceRef, oldDestination: ResourceRef, newDestination: ResourceRef) = Unit
    fun finishResume(source: ResourceRef, destination: ResourceRef) = Unit
    fun transferTo(
        source: ResourceRef,
        destination: ResourceRef,
        target: BlockDevice,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        onPieceVerification: (verifiedPieces: Int, totalPieces: Int) -> Unit = { _, _ -> },
        checkpointStore: TransferCheckpointStore? = null,
        onBytesRemaining: (Long) -> Unit = {},
        onResumeStatus: (String) -> Unit = {},
        onTorrentStats: (TorrentTransferStats) -> Unit = {}
    ): ProviderResult<Long>
}

data class PendingResumeTransfer(
    val source: ResourceRef,
    val destinationParent: ResourceRef,
    val requestedName: String,
    val destination: ResourceRef,
    val incompleteName: String,
    val finalName: String
)

/** Optional fast path; Unsupported means callers can fall back to streamed openRead/openWrite. */
interface RandomAccessTransferRouter {
    fun requiresRandomAccess(source: ResourceRef): Boolean = false
    fun findResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String): ResumeDestination? = null
    fun rememberResumeDestination(
        source: ResourceRef,
        destinationParent: ResourceRef,
        requestedName: String,
        destination: ResourceRef,
        destinationName: String
    ) = Unit
    fun forgetResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String) = Unit
    fun discardResumeState(source: ResourceRef, destination: ResourceRef) = Unit
    fun migrateResumeState(source: ResourceRef, oldDestination: ResourceRef, newDestination: ResourceRef) = Unit
    fun finishResume(source: ResourceRef, destination: ResourceRef) = Unit
    fun transferRandomAccess(
        source: ResourceRef,
        destination: ResourceRef,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        onPieceVerification: (verifiedPieces: Int, totalPieces: Int) -> Unit = { _, _ -> },
        onBytesRemaining: (Long) -> Unit = {},
        onResumeStatus: (String) -> Unit = {},
        onTorrentStats: (TorrentTransferStats) -> Unit = {}
    ): ProviderResult<Long>
}

data class ResumeDestination(val ref: ResourceRef, val name: String)

/** Explicit user choice before replacing direct block delivery with full-file staging. */
data class RandomAccessFallbackRequest(val source: ResourceRef, val sourceName: String,
    val destination: ResourceRef, val temporaryBytes: Long?, val reason: String) {
    val message: String get() = "This transfer needs random-access writes, but the target does not support them. " +
        "Continuing with this target is highly discouraged. Choose a different target, preferably writable local storage, SFTP, or SMB.\n\n" +
        "Continue anyway downloads the complete file to temporary local storage before copying it to the target. " +
        (temporaryBytes?.let { "It needs at least ${java.text.NumberFormat.getIntegerInstance().format(it)} bytes of temporary space. " } ?: "It needs space for the complete file. ") +
        "Archives or images may require additional rewriting and free space. Destination streaming cannot resume from a saved piece bitmap; " +
        "an interrupted final copy may need to be repeated. Existing partial files will be retained.\n\n" +
        "File: $sourceName\n$reason"
}

/** Live torrent payload statistics, separate from destination write progress. */
data class TorrentTransferStats(
    val connectedPeers: Int = 0,
    val servingPeers: Int = 0,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val uploadedBytes: Long = 0L,
    val waitForPeersIndefinitely: Boolean = false
) {
    fun statusMessage(): String = when {
        downloadBytesPerSecond > 0 -> "Downloading torrent"
        connectedPeers > 0 -> if (waitForPeersIndefinitely) "Waiting for peer data (no timeout)…" else "Waiting for peer data…"
        waitForPeersIndefinitely -> "Waiting for peers (no timeout)…"
        else -> "Waiting for peers…"
    }
}
