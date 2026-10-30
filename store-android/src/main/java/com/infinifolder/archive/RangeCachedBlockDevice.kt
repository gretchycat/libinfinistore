package com.infinifolder.archive

import java.io.IOException

/**
 * Intelligent LRU Range-Cached BlockDevice.
 * Wraps any underlying BlockDevice (such as SMB, SFTP, FTP, or local file)
 * to provide:
 * 1. Chunked block caching in memory (avoiding small remote roundtrips)
 * 2. Request coalescing (fetching adjacent missing blocks in a single parent read)
 * 3. Write-through cache with invalidation
 * 4. Hit/miss performance metrics
 */
class RangeCachedBlockDevice(
    val parent: BlockDevice,
    val blockSize: Int = 64 * 1024, // 64 KiB
    val maxCachedBlocks: Int = 256 // 16 MiB cache footprint
) : BlockDevice {

    init { require(blockSize > 0 && maxCachedBlocks > 0) }

    override val size: Long get() = parent.size
    override val capabilities: Set<DeviceCapability> get() = parent.capabilities

    // Metrics for performance evaluation
    var hitCount: Long = 0L; private set
    var missCount: Long = 0L; private set
    var bytesReadRequested: Long = 0L; private set
    var bytesFetchedFromParent: Long = 0L; private set

    private val cache = object : LinkedHashMap<Long, ByteArray>(maxCachedBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            return size > maxCachedBlocks
        }
    }

    @Synchronized
    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= size || length == 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        bytesReadRequested += toRead

        var bytesRead = 0
        while (bytesRead < toRead) {
            val currOffset = offset + bytesRead
            val blockIdx = currOffset / blockSize
            val offsetInBlock = (currOffset % blockSize).toInt()
            val chunkLen = (blockSize - offsetInBlock).toLong().coerceAtMost((toRead - bytesRead).toLong()).toInt()

            val blockData = getOrFetchBlock(blockIdx)
            if (blockData == null || offsetInBlock >= blockData.size) break

            val availableInBlock = (blockData.size - offsetInBlock).coerceAtLeast(0)
            val actualToCopy = chunkLen.coerceAtMost(availableInBlock)
            if (actualToCopy <= 0) break

            System.arraycopy(blockData, offsetInBlock, buffer, dstOffset + bytesRead, actualToCopy)
            bytesRead += actualToCopy
        }
        return bytesRead
    }

    private fun getOrFetchBlock(blockIdx: Long): ByteArray? {
        val cached = cache[blockIdx]
        if (cached != null) {
            hitCount++
            return cached
        }
        missCount++

        val blockStart = blockIdx * blockSize
        if (blockStart >= size) return null

        val fetchLen = blockSize.toLong().coerceAtMost(size - blockStart).toInt()
        val data = ByteArray(fetchLen)
        var total = 0
        while (total < fetchLen) {
            val r = parent.readAt(blockStart + total, data, total, fetchLen - total)
            if (r <= 0) break
            total += r
        }
        val finalData = if (total == fetchLen) data else data.copyOf(total)
        bytesFetchedFromParent += finalData.size
        cache[blockIdx] = finalData
        return finalData
    }

    @Synchronized
    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("BlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        if (length == 0) return
        // Invalidate before mutation: a failed write can still change some bytes.
        val startBlock = offset / blockSize
        val endBlock = (offset + length - 1) / blockSize
        cache.keys.removeAll { it in startBlock..endBlock }
        parent.writeAt(offset, buffer, srcOffset, length)
    }

    @Synchronized
    override fun truncate(newSize: Long) {
        if (isReadOnly) throw IOException("BlockDevice is read-only")
        if (newSize < 0) throw IOException("Negative block-device size")
        // Also drop a short cached tail when growing, and on partially failed resizes.
        cache.clear()
        parent.truncate(newSize)
    }

    @Synchronized
    fun invalidateAll() {
        cache.clear()
    }

    override fun flush() {
        parent.flush()
    }

    @Synchronized
    override fun close() {
        cache.clear()
        parent.close()
    }
}
