package com.infinifolder.torrent

import bt.data.*
import bt.data.digest.JavaSecurityDigester
import bt.net.buffer.ByteBufferView
import bt.runtime.Config
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.BitSet

class ResumeVerifierModuleTest {
    private class Unit(private val bytes: ByteArray) : StorageUnit {
        var reads = 0
        override fun readBlock(buffer: ByteBuffer, offset: Long): Int {
            reads++
            if (offset >= bytes.size) return -1
            val count = minOf(buffer.remaining(), bytes.size - offset.toInt())
            buffer.put(bytes, offset.toInt(), count)
            return count
        }
        override fun writeBlock(buffer: ByteBuffer, offset: Long) = error("Unexpected write")
        override fun writeBlock(buffer: ByteBufferView, offset: Long) = error("Unexpected write")
        override fun capacity() = bytes.size.toLong()
        override fun size() = bytes.size.toLong()
        override fun close() {}
    }
    private fun chunk(unit: Unit, expected: ByteArray): ChunkDescriptor {
        val range = Proxy.newProxyInstance(DataRange::class.java.classLoader, arrayOf(DataRange::class.java)) { _, method, args ->
            when (method.name) {
                "visitUnits" -> { (args!![0] as DataRangeVisitor).visitUnit(unit, 0, unit.size()); null }
                "length" -> unit.size()
                else -> error("Unexpected range call: ${method.name}")
            }
        } as DataRange
        val checksum = MessageDigest.getInstance("SHA-1").digest(expected)
        return Proxy.newProxyInstance(ChunkDescriptor::class.java.classLoader, arrayOf(ChunkDescriptor::class.java)) { _, method, _ ->
            when (method.name) {
                "getData" -> range
                "getChecksum" -> checksum
                else -> error("Unexpected chunk call: ${method.name}")
            }
        } as ChunkDescriptor
    }
    private fun bitfield(total: Int) = object : LocalBitfield(total, List(total) { emptyList<CompletableTorrentFile>() }) {
        override fun fileFinishedCallback(file: bt.metainfo.TorrentFile) {}
    }
    private fun verifier(trusted: BitSet = BitSet(), checked: (Int, Boolean) -> kotlin.Unit = { _, _ -> }) =
        ResumeVerifierModule(trusted, checked).verifier(Config(), JavaSecurityDigester("SHA-1", 16 * 1024))

    @Test fun trustedStartupPieceSkipsDestinationReads() {
        val unit = Unit("good".toByteArray())
        val field = bitfield(1)
        assertTrue(verifier(BitSet().apply { set(0) }).verify(listOf(chunk(unit, "good".toByteArray())), field))
        assertEquals(0, unit.reads)
        assertTrue(field.isVerified(0))
    }
    @Test fun missingCheckpointReadsAndHashesGoodData() {
        val unit = Unit("good".toByteArray())
        assertTrue(verifier().verify(listOf(chunk(unit, "good".toByteArray())), bitfield(1)))
        assertTrue(unit.reads > 0)
    }
    @Test fun corruptedDestinationPieceFailsHashAndRemainsMissing() {
        val unit = Unit("bad!".toByteArray())
        val field = bitfield(1)
        val results = mutableListOf<Boolean>()
        assertFalse(verifier(checked = { _, good -> results.add(good) }).verify(listOf(chunk(unit, "good".toByteArray())), field))
        assertEquals(listOf(false), results)
        assertFalse(field.isVerified(0))
        assertTrue(unit.reads > 0)
    }
    @Test fun mixedGoodAndCorruptPiecesRetainOnlyGoodProgress() {
        val first = Unit("good".toByteArray())
        val second = Unit("bad!".toByteArray())
        val field = bitfield(2)
        assertFalse(verifier().verify(listOf(chunk(first, "good".toByteArray()), chunk(second, "next".toByteArray())), field))
        assertTrue(field.isVerified(0))
        assertFalse(field.isVerified(1))
    }
    @Test fun newlyDownloadedPieceNeverBypassesHashBecauseOfOldTrustedIndex() {
        val unit = Unit("bad!".toByteArray())
        val verifier = verifier(BitSet().apply { set(0) })
        assertFalse(verifier.verify(chunk(unit, "good".toByteArray())))
        assertFalse(verifier.verifyIfPresent(chunk(unit, "good".toByteArray())))
        assertTrue(unit.reads >= 2)
    }
    @Test fun cancelledStartupVerificationStopsBeforeReading() {
        val unit = Unit("good".toByteArray())
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.io.InterruptedIOException::class.java) {
                verifier().verify(listOf(chunk(unit, "good".toByteArray())), bitfield(1))
            }
        } finally { Thread.interrupted() }
        assertEquals(0, unit.reads)
    }
}
