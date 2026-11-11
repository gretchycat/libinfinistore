package com.infinifolder.torrent

import com.infinifolder.storage.TransferCheckpointStore
import com.infinifolder.ops.OperationSnapshot
import com.infinifolder.ops.OperationState
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TorrentTransferLifecycleTest {
    @Test fun interruptedWorkerCanSaveAndRestoresItsInterruptFlag() {
        val lifecycle = TorrentTransferLifecycle(Any()) { false }
        var saved = false
        Thread.currentThread().interrupt()
        try {
            lifecycle.finish(stop = { assertFalse(Thread.currentThread().isInterrupted) }, persist = {
                assertFalse(Thread.currentThread().isInterrupted)
                saved = true
            })
            assertTrue(saved)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun shutdownInterruptCannotPreventCheckpointSave() {
        val lifecycle = TorrentTransferLifecycle(Any()) { true }
        var saved = false
        try {
            lifecycle.finish(stop = { Thread.currentThread().interrupt() }, persist = {
                assertFalse(Thread.currentThread().isInterrupted)
                saved = true
            })
            assertTrue(saved)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun checkpointFailureStillRestoresInterruptState() {
        val lifecycle = TorrentTransferLifecycle(Any()) { false }
        Thread.currentThread().interrupt()
        try {
            assertThrows(IOException::class.java) { lifecycle.finish({}, { throw IOException("offline") }) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun shutdownFailureStillAttemptsCheckpoint() {
        var saved = false
        val lifecycle = TorrentTransferLifecycle(Any()) { false }
        assertThrows(IOException::class.java) {
            lifecycle.finish({ throw IOException("stop failed") }, { saved = true })
        }
        assertTrue(saved)
    }
    @Test fun queuedWriterCannotWriteAfterCloseFence() {
        val ioLock = Any()
        val lifecycle = TorrentTransferLifecycle(ioLock) { false }
        val started = CountDownLatch(1)
        val refused = AtomicBoolean(false)
        val mutated = AtomicBoolean(false)
        val writer = Thread {
            started.countDown()
            try { lifecycle.write { mutated.set(true) } }
            catch (_: InterruptedIOException) { refused.set(true) }
        }
        synchronized(ioLock) {
            writer.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            lifecycle.closeWrites()
        }
        writer.join(2000)
        assertFalse(writer.isAlive)
        assertTrue(refused.get())
        assertFalse(mutated.get())
    }
    @Test fun cancellationPreservesDurableVerifiedPiecesForNextSession() {
        val identity = TorrentCheckpointIdentity("mock:download.incomplete", "hash", "file", 4, 2, 8, 0, 8)
        var data: ByteArray? = null
        val store = object : TransferCheckpointStore {
            override fun read() = data
            override fun fingerprint() = "stable"
            override fun write(bytes: ByteArray) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                data = bytes.copyOf()
            }
            override fun delete() { data = null }
        }
        val checkpoint = TorrentVerificationCheckpoint(identity, store, {}, true)
        checkpoint.checked(0, true)
        checkpoint.beforeWrite(4, 4) // Another piece started, leaving the companion dirty.
        val lifecycle = TorrentTransferLifecycle(Any()) { true }
        try {
            lifecycle.finish({ Thread.currentThread().interrupt() }, { checkpoint.save() })
            assertTrue(Thread.interrupted())
            val resumed = TorrentVerificationCheckpoint(identity, store, {}, true)
            assertTrue(resumed.trustedPieces[0])
            assertFalse(resumed.trustedPieces[1])
        } finally { Thread.interrupted() }
    }
    @Test fun finishLaterLabelUsesResumabilityRatherThanChangingStatusWords() {
        val torrent = OperationSnapshot("id", "Copy ubuntu.iso", OperationState.RUNNING, resumable = true)
        assertEquals("Pause", torrent.copy(message = "Restoring saved progress").stopActionLabel)
        assertEquals("Pause", torrent.copy(message = "Connecting to peers…").stopActionLabel)
        assertEquals("Cancel", torrent.copy(resumable = false, label = "Copy notes.txt").stopActionLabel)
    }
}
