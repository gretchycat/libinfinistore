package com.infinifolder.torrent

import java.nio.ByteBuffer
import java.util.TreeMap

/** Bounded write-through memory for Bt's piece-hash reads; never a durable checkpoint. */
internal class TorrentWriteCache(private val limitBytes: Long = 32L * 1024 * 1024) {
    private val ranges = TreeMap<Long, ByteArray>()
    private val access = LinkedHashMap<Long, Unit>(16, 0.75f, true)
    private var sizeBytes = 0L
    var hitsBytes = 0L
        private set

    @Synchronized fun put(offset: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val end = offset + bytes.size
        // Retransmitted or overlapping writes must never expose older cached bytes.
        val overlapping = mutableListOf<Long>()
        ranges.floorEntry(offset)?.let { if (it.key + it.value.size > offset) overlapping.add(it.key) }
        overlapping.addAll(ranges.subMap(offset, true, end, false).keys)
        overlapping.distinct().forEach(::remove)
        if (bytes.size.toLong() > limitBytes) return
        while (sizeBytes + bytes.size > limitBytes && access.isNotEmpty()) remove(access.keys.first())
        ranges[offset] = bytes.copyOf()
        access[offset] = Unit
        sizeBytes += bytes.size
    }

    /** Returns a contiguous cached prefix; a miss falls back to the destination. */
    @Synchronized fun read(buffer: ByteBuffer, offset: Long, length: Int): Int {
        var copied = 0
        while (copied < length) {
            val position = offset + copied
            val entry = ranges.floorEntry(position) ?: break
            val local = position - entry.key
            if (local < 0 || local >= entry.value.size) break
            val count = minOf(length - copied, entry.value.size - local.toInt())
            buffer.put(entry.value, local.toInt(), count)
            access[entry.key]
            copied += count
        }
        hitsBytes += copied
        return copied
    }

    private fun remove(offset: Long) {
        ranges.remove(offset)?.let { sizeBytes -= it.size }
        access.remove(offset)
    }
}
