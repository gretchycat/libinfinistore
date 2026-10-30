package com.infinifolder.torrent

import bt.data.StorageUnit
import bt.net.buffer.ByteBufferView
import java.io.IOException
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong

/** Preserve the virtual size of a skipped file, retaining only shared-piece bytes.
 * A hash can span the selected file and its neighbours; missing bytes stay unreadable.
 */
internal class TorrentBoundaryStorageUnit(
    private val length: Long,
    private val keepStart: Long,
    private val keepEnd: Long,
    private val memoryUsed: AtomicLong
) : StorageUnit {
    private class Block(val bytes: ByteArray = ByteArray(BLOCK_BYTES), val present: BitSet = BitSet(BLOCK_BYTES))
    private val blocks = HashMap<Long, Block>()
    private var extent = 0L
    private var closed = false

    @Synchronized override fun readBlock(buffer: ByteBuffer, offset: Long): Int {
        if (closed) throw IOException("Torrent boundary storage is closed")
        if (!buffer.hasRemaining()) return 0
        var at = offset
        var copied = 0
        while (buffer.hasRemaining() && at >= keepStart && at < keepEnd) {
            val block = blocks[at / BLOCK_BYTES] ?: break
            val local = (at % BLOCK_BYTES).toInt()
            if (!block.present.get(local)) break
            val count = minOf(buffer.remaining(), block.present.nextClearBit(local) - local,
                (keepEnd - at).coerceAtMost(BLOCK_BYTES.toLong()).toInt())
            buffer.put(block.bytes, local, count)
            at += count; copied += count
        }
        return if (copied > 0) copied else -1
    }

    @Synchronized override fun writeBlock(buffer: ByteBuffer, offset: Long): Int {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        retain(offset, data)
        return data.size
    }
    @Synchronized override fun writeBlock(buffer: ByteBufferView, offset: Long): Int {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        retain(offset, data)
        return data.size
    }
    private fun retain(offset: Long, data: ByteArray) {
        if (closed) throw IOException("Torrent boundary storage is closed")
        if (offset < 0 || offset > length || data.size.toLong() > length - offset)
            throw IOException("Torrent boundary write is outside the file")
        var at = maxOf(offset, keepStart)
        val end = minOf(offset + data.size, keepEnd)
        while (at < end) {
            val block = blocks.getOrPut(at / BLOCK_BYTES) {
                if (memoryUsed.addAndGet(BLOCK_COST) > MAX_MEMORY) {
                    memoryUsed.addAndGet(-BLOCK_COST)
                    throw IOException("Torrent shared-piece data exceeds the 64 MB boundary buffer limit")
                }
                try { Block() } catch (error: Throwable) { memoryUsed.addAndGet(-BLOCK_COST); throw error }
            }
            val local = (at % BLOCK_BYTES).toInt()
            val count = minOf(BLOCK_BYTES - local, (end - at).coerceAtMost(BLOCK_BYTES.toLong()).toInt())
            data.copyInto(block.bytes, local, (at - offset).toInt(), (at - offset).toInt() + count)
            block.present.set(local, local + count)
            at += count
        }
        if (end > keepStart && end > offset) extent = maxOf(extent, end)
    }
    override fun capacity() = length
    @Synchronized override fun size() = extent
    @Synchronized override fun close() {
        if (!closed) { closed = true; memoryUsed.addAndGet(-blocks.size * BLOCK_COST); blocks.clear() }
    }
    companion object {
        private const val BLOCK_BYTES = 64 * 1024
        private const val BLOCK_COST = BLOCK_BYTES.toLong() + BLOCK_BYTES / 8
        private const val MAX_MEMORY = 64L * 1024 * 1024
    }
}
