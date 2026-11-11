package com.infinifolder.torrent

import com.infinifolder.storage.TransferCheckpointStore
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.BitSet
import java.util.Properties

class TorrentVerificationCheckpointTest {
    private val identity = TorrentCheckpointIdentity("sftp:/test.incomplete", "torrent-hash", "test.iso", 4, 3, 10, 0, 10)
    private val bits = BitSet().apply { set(0); set(2) }
    private fun encoded(clean: Boolean = true, fingerprint: String? = "file:10:123") =
        TorrentCheckpointCodec.encode(identity, bits, clean, fingerprint)
    private fun decode(bytes: ByteArray?, fingerprint: String? = "file:10:123", sized: Boolean = true,
                       expected: TorrentCheckpointIdentity = identity) = TorrentCheckpointCodec.decode(bytes, expected, fingerprint, sized)
    private fun mutated(field: String, value: String): ByteArray {
        val state = TorrentCheckpointCodec.properties(encoded())!!
        state.setProperty(field, value)
        return java.io.ByteArrayOutputStream().apply { state.store(this, null) }.toByteArray()
    }

    @Test fun validCleanCheckpointRestoresExactlyVerifiedPieces() { assertEquals(bits, decode(encoded())) }
    @Test fun missingCheckpointNeverSkipsHashing() { assertTrue(decode(null).isEmpty) }
    @Test fun emptyCheckpointNeverSkipsHashing() { assertTrue(decode(byteArrayOf()).isEmpty) }
    @Test fun randomCheckpointNeverSkipsHashing() { assertTrue(decode("garbage".toByteArray()).isEmpty) }
    @Test fun malformedPropertiesNeverEscapeDecoder() { assertTrue(decode("\\uZZZZ".toByteArray()).isEmpty) }
    @Test fun everyTruncatedCheckpointIsRejectedOrRetainsIdenticalValidPayload() {
        val bytes = encoded()
        for (length in 0 until bytes.size) {
            val result = decode(bytes.copyOf(length))
            assertTrue("Truncation at $length must not invent verified pieces", result.isEmpty || result == bits)
        }
    }
    @Test fun flippedBytesCannotInventVerifiedPieces() {
        val bytes = encoded()
        for (index in bytes.indices) {
            val corrupted = bytes.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            val result = decode(corrupted)
            assertTrue("Corruption at $index must not invent verified pieces", result.isEmpty || result == bits)
        }
    }
    @Test fun corruptedBitmapInvalidatesChecksum() { assertTrue(decode(mutated("pieces", "////")).isEmpty) }
    @Test fun invalidBase64IsRejected() { assertTrue(decode(mutated("pieces", "%%%")).isEmpty) }
    @Test fun missingChecksumIsRejected() { assertTrue(decode(mutated("checksum", "")).isEmpty) }
    @Test fun dirtyCheckpointIsRejected() { assertTrue(decode(encoded(clean = false)).isEmpty) }
    @Test fun unavailableFingerprintIsRejected() { assertTrue(decode(encoded(), fingerprint = null).isEmpty) }
    @Test fun changedModificationTimeIsRejected() { assertTrue(decode(encoded(), "file:10:124").isEmpty) }
    @Test fun changedDestinationSizeIsRejected() { assertTrue(decode(encoded(), sized = false).isEmpty) }
    @Test fun changedTorrentIsRejected() { assertTrue(decode(encoded(), expected = identity.copy(hash = "other")).isEmpty) }
    @Test fun changedDestinationIsRejected() { assertTrue(decode(encoded(), expected = identity.copy(destination = "other")).isEmpty) }
    @Test fun changedFilePathIsRejected() { assertTrue(decode(encoded(), expected = identity.copy(path = "other")).isEmpty) }
    @Test fun changedPieceSizeIsRejected() { assertTrue(decode(encoded(), expected = identity.copy(pieceSize = 8)).isEmpty) }
    @Test fun outOfRangePieceIndexIsRejected() {
        val invalid = BitSet().apply { set(500) }
        assertTrue(decode(TorrentCheckpointCodec.encode(identity, invalid, true, "file:10:123")).isEmpty)
    }
    @Test fun partialFileBoundaryPiecesAreNotTrusted() {
        val partial = identity.copy(fileStart = 2, fileSize = 6)
        val all = BitSet().apply { set(0, 3) }
        val bytes = TorrentCheckpointCodec.encode(partial, all, true, "file:10:123")
        assertEquals(BitSet().apply { set(1) }, decode(bytes, expected = partial))
    }
    @Test fun oversizedCheckpointIsRejected() { assertTrue(decode(ByteArray(4 * 1024 * 1024 + 1)).isEmpty) }

    private class Store : TransferCheckpointStore {
        var data: ByteArray? = null
        var fingerprint: String? = "file:10:123"
        val events = mutableListOf<String>()
        var invalidRead = false
        var failRead = false
        var failWrite = false
        var rejectWrite = false
        var commitThenFail = false
        override fun read(): ByteArray? { if (invalidRead) throw com.infinifolder.storage.InvalidTransferCheckpointException("oversized"); if (failRead) throw IOException("offline"); return data }
        override fun fingerprint() = fingerprint
        override fun write(bytes: ByteArray) {
            if(rejectWrite)throw com.infinifolder.storage.CheckpointWriteRejectedException("denied before submission",IOException("denied"))
            if (failWrite) throw IOException("denied")
            data = bytes.copyOf()
            events.add("write:${TorrentCheckpointCodec.properties(bytes)?.getProperty("clean")}")
            if (commitThenFail) throw IOException("ack lost")
        }
        override fun delete() { events.add("delete"); data = null }
    }
    private fun session(store: Store, flushFails: () -> Boolean = {false}) = TorrentVerificationCheckpoint(identity, store, {
        store.events.add("flush")
        if (flushFails()) throw IOException("flush failed")
    }, true)

    @Test fun dirtyStatePrecedesDestinationWrites() {
        val store = Store().apply { data = encoded() }
        val session = session(store)
        session.beforeWrite(4, 4)
        assertEquals(listOf("flush", "write:false"), store.events)
        // The checksummed write journal retains untouched pieces, never the piece being changed.
        assertEquals(bits,decode(store.data))
        assertFalse(decode(store.data)[1])
    }
    @Test fun flushPrecedesCleanPublication() {
        val store = Store()
        val session = session(store)
        session.checked(0, true)
        assertEquals(listOf("flush", "write:true"), store.events)
        assertEquals(BitSet().apply { set(0) }, decode(store.data))
    }
    @Test fun rewrittenPieceLosesTrustUntilVerifiedAgain() {
        val store = Store().apply { data = encoded() }
        val session = session(store)
        session.beforeWrite(0, 2)
        session.save()
        assertEquals(BitSet().apply { set(2) }, decode(store.data))
        session.checked(0, true)
        session.save()
        assertEquals(bits, decode(store.data))
    }
    @Test fun failedHashIsNotSavedAsVerified() {
        val store = Store().apply { data = encoded() }
        val session = session(store)
        session.checked(0, false)
        session.save()
        assertFalse(decode(store.data).get(0))
    }
    @Test fun interruptedUncheckpointedWriteForcesRecheck() {
        val store = Store().apply { data = encoded() }
        session(store).beforeWrite(4, 4)
        assertEquals(bits,session(store).trustedPieces)
        assertFalse(session(store).trustedPieces[1])
    }
    @Test fun gracefulStopRetainsVerifiedProgress() {
        val store = Store()
        val session = session(store)
        session.beforeWrite(0, 4)
        session.checked(0, true)
        session.save()
        assertTrue(session(store).trustedPieces.get(0))
    }
    @Test fun failedFlushCannotPublishNewCleanState() {
        val store = Store()
        var flushFails=false
        val session = session(store, flushFails = {flushFails})
        session.beforeWrite(0, 4)
        flushFails=true // Fail the data flush after the pre-write journal was safely stored.
        session.checked(0, true)
        assertThrows(IOException::class.java) { session.save() }
        assertTrue(decode(store.data).isEmpty)
    }
    @Test fun cleanCheckpointThatCannotBeInvalidatedBlocksWrites() {
        val store = Store().apply { data = encoded(); failWrite = true }
        assertThrows(IOException::class.java) { session(store).beforeWrite(4, 4) }
        assertEquals(bits, decode(store.data))
    }
    @Test fun noCheckpointWritePermissionFallsBackWithoutTrust() {
        val store = Store().apply { rejectWrite = true }
        val session = session(store)
        session.beforeWrite(0, 4)
        session.checked(0, true)
        session.save()
        assertNull(store.data)
    }
    @Test fun readFailureIsNotTreatedAsMissingCheckpoint() {
        val store = Store().apply { data = encoded(); failRead = true; failWrite = true }
        val session = session(store)
        assertTrue(session.trustedPieces.isEmpty)
        assertThrows(IOException::class.java) { session.beforeWrite(4, 4) }
    }
    @Test fun lostCleanWriteAcknowledgementStillRequiresInvalidation() {
        val store = Store()
        val session = session(store)
        session.beforeWrite(0, 4)
        session.checked(0, true)
        store.commitThenFail = true
        assertThrows(IOException::class.java) { session.save() }
        assertTrue(decode(store.data).get(0)) // Remote write committed despite its reported error.
        store.commitThenFail = false
        store.failWrite = true
        assertThrows(IOException::class.java) { session.beforeWrite(4, 4) }
    }
    @Test fun oversizedJunkSidecarDoesNotBlockDownloadOrBecomeTrusted() {
        val store = Store().apply { invalidRead = true; data = ByteArray(1) }
        val session = session(store)
        assertTrue(session.trustedPieces.isEmpty)
        session.beforeWrite(0, 4)
        session.checked(0, true)
        session.save()
        assertArrayEquals(ByteArray(1), store.data)
    }
    @Test fun startupScanPersistsEveryNewVerifiedPiece() {
        val large = identity.copy(totalPieces = 100, torrentSize = 400, fileSize = 400)
        val store = Store()
        val session = TorrentVerificationCheckpoint(large, store, { store.events.add("flush") }, true)
        repeat(32) { session.checked(it, true) }
        val resumed = TorrentVerificationCheckpoint(large, store, {}, true)
        assertEquals(32, resumed.trustedPieces.cardinality())
        assertEquals("matched", resumed.loadReason)
        assertEquals(32, store.events.count { it == "write:true" })
    }
    @Test fun failedStartupHashesCannotEnterCheckpoints() {
        val large = identity.copy(totalPieces = 100, torrentSize = 400, fileSize = 400)
        val store = Store()
        val session = TorrentVerificationCheckpoint(large, store, {}, true)
        repeat(64) { session.checked(it, it % 2 == 0) }
        val resumed = TorrentVerificationCheckpoint(large, store, {}, true)
        assertEquals(32, resumed.trustedPieces.cardinality())
        repeat(64) { assertEquals(it % 2 == 0, resumed.trustedPieces[it]) }
    }
    @Test fun startupSaveFailureDoesNotDiscardVerifiedMemoryProgress() {
        val large = identity.copy(totalPieces = 100, torrentSize = 400, fileSize = 400)
        val store = Store().apply { failWrite = true }
        val session = TorrentVerificationCheckpoint(large, store, {}, true)
        repeat(32) { session.checked(it, true) }
        store.failWrite = false
        session.save()
        assertEquals(32, TorrentVerificationCheckpoint(large, store, {}, true).trustedPieces.cardinality())
    }
    @Test fun missingAndDirtyCheckpointsHaveDistinctRejectionReasons() {
        val store = Store()
        assertEquals("missing-sidecar", session(store).loadReason)
        store.data = encoded(clean = false)
        assertEquals("interrupted-write-or-incomplete-checkpoint", session(store).loadReason)
    }
    @Test fun firstFileVerificationHashIsPersistedImmediately() {
        val store = Store()
        session(store).checked(0, true)
        assertTrue(session(store).trustedPieces[0])
        assertEquals(listOf("flush", "write:true"), store.events)
    }
    @Test fun firstDownloadedPieceIsPersistedImmediately() {
        val store = Store()
        val session = session(store)
        session.beforeWrite(0, 4)
        session.pieceVerified(0)
        assertTrue(session(store).trustedPieces[0])
        assertEquals(listOf("flush", "write:false", "flush", "write:true"), store.events)
    }
    @Test fun restoringCachedPiecesDoesNotRewriteTheirCheckpoint() {
        val store = Store().apply { data = encoded() }
        val session = session(store)
        session.checked(0, true)
        session.checked(2, true)
        assertTrue(store.events.isEmpty())
    }
    @Test fun rejectedCheckpointPreflightDoesNotInventCleanState() {
        val store = object : TransferCheckpointStore {
            override fun read(): ByteArray? = null
            override fun fingerprint() = "file:10:123"
            override fun write(bytes: ByteArray) {
                throw com.infinifolder.storage.CheckpointWriteRejectedException("unrelated sidecar", IOException("foreign file"))
            }
            override fun delete() {}
        }
        val session = TorrentVerificationCheckpoint(identity, store, {}, true)
        session.checked(0, true)
        session.beforeWrite(4, 4) // No clean payload was ever submitted; fallback remains safe.
        assertEquals(0, session.savedPieceCount)
    }
    @Test fun completionDeletesOnlyTheCheckpoint() {
        val store = Store().apply { data = encoded() }
        session(store).complete()
        assertNull(store.data)
        assertEquals(listOf("delete"), store.events)
    }
}
