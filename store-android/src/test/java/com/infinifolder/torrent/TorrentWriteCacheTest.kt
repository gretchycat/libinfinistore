package com.infinifolder.torrent

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class TorrentWriteCacheTest {
    private fun read(cache: TorrentWriteCache, offset: Long, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        cache.read(buffer, offset, count)
        return buffer.array().copyOf(buffer.position())
    }
    @Test fun receivedBytesAreAvailableWithoutDestinationReads() {
        val cache = TorrentWriteCache()
        cache.put(0, byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(2, 3), read(cache, 1, 2))
        assertEquals(2L, cache.hitsBytes)
    }
    @Test fun callerBufferChangesCannotCorruptCachedData() {
        val cache = TorrentWriteCache()
        val bytes = byteArrayOf(1, 2)
        cache.put(0, bytes)
        bytes[0] = 99
        assertArrayEquals(byteArrayOf(1, 2), read(cache, 0, 2))
    }
    @Test fun overlappingRetransmissionNeverReturnsOldBytes() {
        val cache = TorrentWriteCache()
        cache.put(0, byteArrayOf(1, 2, 3, 4))
        cache.put(2, byteArrayOf(8, 9))
        assertTrue(read(cache, 0, 2).isEmpty()) // Falls back to destination for invalidated prefix.
        assertArrayEquals(byteArrayOf(8, 9), read(cache, 2, 2))
    }
    @Test fun writeAcrossSeveralRangesInvalidatesAllOverlaps() {
        val cache = TorrentWriteCache()
        cache.put(0, byteArrayOf(1, 1))
        cache.put(2, byteArrayOf(2, 2))
        cache.put(4, byteArrayOf(3, 3))
        cache.put(1, byteArrayOf(9, 9, 9, 9))
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), read(cache, 1, 4))
        assertTrue(read(cache, 5, 1).isEmpty())
    }
    @Test fun adjacentBlocksReadAsContiguousData() {
        val cache = TorrentWriteCache()
        cache.put(0, byteArrayOf(1, 2))
        cache.put(2, byteArrayOf(3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), read(cache, 0, 4))
    }
    @Test fun missingRangeReturnsOnlyCachedPrefix() {
        val cache = TorrentWriteCache()
        cache.put(0, byteArrayOf(1, 2))
        cache.put(4, byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(1, 2), read(cache, 0, 6))
    }
    @Test fun evictionUsesLeastRecentlyReadRange() {
        val cache = TorrentWriteCache(4)
        cache.put(0, byteArrayOf(1, 2))
        cache.put(2, byteArrayOf(3, 4))
        read(cache, 0, 1)
        cache.put(4, byteArrayOf(5, 6))
        assertTrue(read(cache, 2, 2).isEmpty())
        assertArrayEquals(byteArrayOf(1, 2), read(cache, 0, 2))
        assertArrayEquals(byteArrayOf(5, 6), read(cache, 4, 2))
    }
    @Test fun oversizeReplacementStillInvalidatesOlderCachedBytes() {
        val cache = TorrentWriteCache(2)
        cache.put(0, byteArrayOf(1, 2))
        cache.put(0, byteArrayOf(8, 9, 10))
        assertTrue(read(cache, 0, 2).isEmpty())
    }
}
