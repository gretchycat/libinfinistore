package com.infinifolder.archive

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * Fundamental I/O capabilities for a seekable data source.
 */
enum class DeviceCapability {
    READ,
    WRITE,
    TRUNCATE,
    FLUSH,
    SEEK
}

/** Validate before touching storage or caches, including zero-length operations. */
fun requireDeviceRange(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int) {
    if (offset < 0 || length < 0 || bufferOffset < 0 || bufferOffset > buffer.size - length ||
        offset > Long.MAX_VALUE - length) throw IOException("Invalid block-device byte range")
}

/**
 * Low-level random-access block abstraction allowing transparent read and write
 * across raw files, partition slices, virtual machine disk containers
 * (VDI, QCOW2, VMDK, VHD, VHDX), and remote protocols (SMB, SFTP, FTP).
 */
interface BlockDevice : Closeable {
    val size: Long

    val capabilities: Set<DeviceCapability>
        get() = if (isReadOnly) {
            setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        } else {
            setOf(DeviceCapability.READ, DeviceCapability.WRITE, DeviceCapability.FLUSH, DeviceCapability.SEEK)
        }

    val isReadOnly: Boolean
        get() = !capabilities.contains(DeviceCapability.WRITE)

    /** Returns zero at EOF; stream/channel adapters translate this to their own EOF convention. */
    @Throws(IOException::class)
    fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int = 0, length: Int = buffer.size): Int

    @Throws(IOException::class)
    fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int = 0, length: Int = buffer.size)

    /** Resize the file, including zero-filled growth; fixed-size devices must reject this. */
    @Throws(IOException::class)
    fun truncate(newSize: Long) {
        throw IOException("Resize not implemented on this BlockDevice")
    }

    fun flush() {}
}

fun BlockDevice.readBytes(offset: Long, length: Int): ByteArray {
    if (length <= 0 || offset >= size) return ByteArray(0)
    val toRead = length.toLong().coerceAtMost(size - offset).toInt()
    val buf = ByteArray(toRead)
    var total = 0
    while (total < toRead) {
        val read = readAt(offset + total, buf, total, toRead - total)
        if (read <= 0) break
        total += read
    }
    return if (total == toRead) buf else buf.copyOf(total)
}

fun BlockDevice.writeBytes(offset: Long, data: ByteArray) {
    if (data.isNotEmpty()) {
        writeAt(offset, data, 0, data.size)
    }
}

fun BlockDevice.slice(startOffset: Long, size: Long, isReadOnly: Boolean = this.isReadOnly): BlockDevice =
    PartitionSliceBlockDevice(this, startOffset, size, isReadOnly)

/**
 * Standard BlockDevice backed directly by a RandomAccessFile.
 */
class FileBlockDevice(
    private val raf: RandomAccessFile,
    override val isReadOnly: Boolean = false,
    size: Long? = null,
    private val ownsFile: Boolean = false,
    private val flushOnRequest: Boolean = true
) : BlockDevice {

    @Volatile private var currentSize = size ?: raf.length()
    override val size: Long get() = currentSize

    override val capabilities: Set<DeviceCapability>
        get() = if (isReadOnly) {
            setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        } else {
            setOf(
                DeviceCapability.READ,
                DeviceCapability.WRITE,
                DeviceCapability.TRUNCATE,
                DeviceCapability.FLUSH,
                DeviceCapability.SEEK
            )
        }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        synchronized(raf) {
            if (offset >= currentSize || length == 0) return 0
            val toRead = length.toLong().coerceAtMost(currentSize - offset).toInt()
            raf.seek(offset)
            val bytesRead = raf.read(buffer, dstOffset, toRead)
            return if (bytesRead < 0) 0 else bytesRead
        }
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("BlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        if (length == 0) return
        synchronized(raf) {
            raf.seek(offset)
            raf.write(buffer, srcOffset, length)
            currentSize = maxOf(currentSize, offset + length)
        }
    }

    override fun truncate(newSize: Long) {
        if (isReadOnly) throw IOException("BlockDevice is read-only")
        if (newSize < 0) throw IOException("Negative truncate size: $newSize")
        synchronized(raf) {
            raf.setLength(newSize)
            currentSize = newSize
        }
    }

    override fun flush() {
        if (!flushOnRequest) return
        synchronized(raf) {
            raf.channel.force(true)
        }
    }

    override fun close() {
        if (ownsFile) raf.close()
    }
}

/**
 * BlockDevice backed by an in-memory byte buffer (ideal for synthetic files, tests, and extracted fragments).
 */
class MemoryBlockDevice(
    initialData: ByteArray = ByteArray(0),
    override val isReadOnly: Boolean = false
) : BlockDevice {

    private var data: ByteArray = initialData.clone()
    private var currentSize: Long = initialData.size.toLong()

    override val size: Long get() = currentSize

    override val capabilities: Set<DeviceCapability>
        get() = if (isReadOnly) {
            setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        } else {
            setOf(
                DeviceCapability.READ,
                DeviceCapability.WRITE,
                DeviceCapability.TRUNCATE,
                DeviceCapability.FLUSH,
                DeviceCapability.SEEK
            )
        }

    @Synchronized
    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= currentSize || length == 0) return 0
        val toRead = length.toLong().coerceAtMost(currentSize - offset).toInt()
        System.arraycopy(data, offset.toInt(), buffer, dstOffset, toRead)
        return toRead
    }

    @Synchronized
    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("MemoryBlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        if (length == 0) return
        val requiredSize = offset + length
        if (requiredSize > Int.MAX_VALUE) throw IOException("Memory block device exceeds maximum array size")
        if (requiredSize > data.size) {
            var newCap = (data.size.toLong() * 2).coerceIn(1024, Int.MAX_VALUE.toLong()).toInt()
            while (newCap < requiredSize) {
                newCap = (newCap.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            data = data.copyOf(newCap)
        }
        System.arraycopy(buffer, srcOffset, data, offset.toInt(), length)
        if (requiredSize > currentSize) {
            currentSize = requiredSize
        }
    }

    @Synchronized
    override fun truncate(newSize: Long) {
        if (isReadOnly) throw IOException("MemoryBlockDevice is read-only")
        if (newSize < 0 || newSize > Int.MAX_VALUE) throw IOException("Invalid memory block-device size: $newSize")
        if (newSize > data.size) {
            data = data.copyOf(newSize.toInt())
        }
        if (newSize < currentSize) data.fill(0, newSize.toInt(), currentSize.toInt())
        currentSize = newSize
    }

    @Synchronized
    fun toByteArray(): ByteArray = data.copyOf(currentSize.toInt())

    override fun close() {}
}

/**
 * BlockDevice backed by an Android ParcelFileDescriptor (e.g. from ContentResolver for SAF).
 */
class ContentUriBlockDevice(
    private val pfd: ParcelFileDescriptor,
    override val isReadOnly: Boolean = false
) : BlockDevice {

    private val inStream = FileInputStream(pfd.fileDescriptor)
    private val outStream = if (!isReadOnly) FileOutputStream(pfd.fileDescriptor) else null
    private val channel: FileChannel = inStream.channel
    private val writeChannel: FileChannel? = outStream?.channel

    init {
        // Pipe-backed SAF documents are streams, not seekable files.
        channel.position(0L)
        channel.size()
    }

    override val size: Long get() = channel.size()

    override val capabilities: Set<DeviceCapability>
        get() = if (isReadOnly) {
            setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        } else {
            setOf(
                DeviceCapability.READ,
                DeviceCapability.WRITE,
                DeviceCapability.TRUNCATE,
                DeviceCapability.FLUSH,
                DeviceCapability.SEEK
            )
        }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= size || length == 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        val byteBuf = ByteBuffer.wrap(buffer, dstOffset, toRead)
        synchronized(channel) {
            channel.position(offset)
            val read = channel.read(byteBuf)
            return if (read < 0) 0 else read
        }
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("ContentUriBlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        val byteBuf = ByteBuffer.wrap(buffer, srcOffset, length)
        synchronized(channel) {
            val writer = writeChannel ?: throw NonWritableChannelException()
            writer.position(offset)
            while (byteBuf.hasRemaining()) {
                writer.write(byteBuf)
            }
        }
    }

    override fun truncate(newSize: Long) {
        if (isReadOnly) throw IOException("ContentUriBlockDevice is read-only")
        if (newSize < 0) throw IOException("Negative size: $newSize")
        synchronized(channel) {
            // FileChannel.truncate cannot grow a file; reserve targets need both directions.
            try { android.system.Os.ftruncate(pfd.fileDescriptor, newSize) }
            catch (error: android.system.ErrnoException) { throw IOException("Cannot resize document to $newSize bytes", error) }
        }
    }

    override fun flush() {
        synchronized(channel) {
            writeChannel?.force(true)
        }
    }

    override fun close() {
        try { channel.close() } catch (_: Exception) {}
        try { inStream.close() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}

/**
 * Sub-slice of a parent BlockDevice (e.g. an MBR/GPT partition, or an uncompressed archive payload).
 */
class PartitionSliceBlockDevice(
    val parent: BlockDevice,
    val startOffset: Long,
    override val size: Long,
    private val readOnlyOverride: Boolean? = null
) : BlockDevice {

    override val capabilities: Set<DeviceCapability>
        get() {
            val base = parent.capabilities.toMutableSet()
            base.remove(DeviceCapability.TRUNCATE) // A sub-slice cannot be resized independently
            if (readOnlyOverride == true || parent.isReadOnly) {
                base.remove(DeviceCapability.WRITE)
            }
            return base
        }

    override val isReadOnly: Boolean
        get() = readOnlyOverride == true || parent.isReadOnly

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= size || length == 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        return parent.readAt(startOffset + offset, buffer, dstOffset, toRead)
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("SliceBlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        if (offset + length > size) {
            throw IOException("Write out of slice bounds (offset=$offset, len=$length, sliceSize=$size)")
        }
        parent.writeAt(startOffset + offset, buffer, srcOffset, length)
    }

    override fun flush() = parent.flush()

    override fun close() {
        // Slices do not close the parent device
    }
}

/**
 * Maps a series of logical segments across one or more underlying BlockDevices.
 * Useful for LVM Logical Volumes, concatenated disks, or multi-extent storage.
 */
class CompositeBlockDevice(
    val segments: List<SegmentMapping>,
    private val readOnlyOverride: Boolean? = null
) : BlockDevice {

    data class SegmentMapping(
        val logicalOffset: Long,
        val physicalOffset: Long,
        val length: Long,
        val device: BlockDevice
    )

    override val size: Long = segments.sumOf { it.length }

    override val capabilities: Set<DeviceCapability>
        get() {
            val caps = mutableSetOf(DeviceCapability.READ, DeviceCapability.SEEK)
            if (!isReadOnly && segments.all { DeviceCapability.WRITE in it.device.capabilities }) {
                caps += DeviceCapability.WRITE
                caps += DeviceCapability.FLUSH
            }
            return caps
        }

    override val isReadOnly: Boolean
        get() = readOnlyOverride == true || segments.any { it.device.isReadOnly }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= size || length == 0) return 0
        var totalRead = 0
        var curLogical = offset
        var curDst = dstOffset
        var remaining = length.toLong().coerceAtMost(size - offset)

        while (remaining > 0L) {
            val seg = segments.firstOrNull { curLogical >= it.logicalOffset && curLogical < it.logicalOffset + it.length }
                ?: break
            val segOffset = curLogical - seg.logicalOffset
            val toReadInSeg = (seg.length - segOffset).coerceAtMost(remaining).toInt()
            val read = seg.device.readAt(seg.physicalOffset + segOffset, buffer, curDst, toReadInSeg)
            if (read <= 0) break
            totalRead += read
            curLogical += read
            curDst += read
            remaining -= read
        }
        return totalRead
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("CompositeBlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        if (offset + length > size) throw IOException("Write out of bounds")
        var curLogical = offset
        var curSrc = srcOffset
        var remaining = length.toLong()

        while (remaining > 0L) {
            val seg = segments.firstOrNull { curLogical >= it.logicalOffset && curLogical < it.logicalOffset + it.length }
                ?: throw IOException("No segment mapped for offset $curLogical")
            val segOffset = curLogical - seg.logicalOffset
            val toWriteInSeg = (seg.length - segOffset).coerceAtMost(remaining).toInt()
            seg.device.writeAt(seg.physicalOffset + segOffset, buffer, curSrc, toWriteInSeg)
            curLogical += toWriteInSeg
            curSrc += toWriteInSeg
            remaining -= toWriteInSeg
        }
    }

    override fun flush() {
        segments.map { it.device }.distinct().forEach { it.flush() }
    }

    override fun close() {
        // Do not close parent devices
    }
}

/**
 * Implements standard Java NIO SeekableByteChannel on top of any BlockDevice,
 * allowing full integration with Apache Commons Compress ZipFile and SevenZFile.
 */
class BlockDeviceSeekableByteChannel(private val dev: BlockDevice) : SeekableByteChannel {
    private var pos: Long = 0L
    private var open: Boolean = true

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    override fun position(): Long = pos

    override fun position(newPosition: Long): SeekableByteChannel {
        if (!open) throw ClosedChannelException()
        require(newPosition >= 0) { "Negative position: $newPosition" }
        pos = newPosition
        return this
    }

    override fun size(): Long {
        if (!open) throw ClosedChannelException()
        return dev.size
    }

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (pos >= dev.size) return -1
        val remaining = dst.remaining()
        if (remaining == 0) return 0
        val toRead = remaining.toLong().coerceAtMost(dev.size - pos).toInt()
        val buf = ByteArray(toRead)
        val read = dev.readAt(pos, buf, 0, toRead)
        if (read <= 0) return -1
        dst.put(buf, 0, read)
        pos += read
        return read
    }

    override fun write(src: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (dev.isReadOnly) throw NonWritableChannelException()
        val remaining = src.remaining()
        if (remaining == 0) return 0
        val buf = ByteArray(remaining)
        src.get(buf)
        dev.writeAt(pos, buf, 0, remaining)
        pos += remaining
        return remaining
    }

    override fun truncate(size: Long): SeekableByteChannel {
        if (!open) throw ClosedChannelException()
        if (dev.isReadOnly) throw NonWritableChannelException()
        require(size >= 0) { "Negative channel size" }
        // NIO truncate only shrinks, unlike the block-device resize contract.
        if (size < dev.size) {
            dev.truncate(size)
            if (pos > size) pos = size
        }
        return this
    }
}

fun BlockDevice.asSeekableByteChannel(): SeekableByteChannel = BlockDeviceSeekableByteChannel(this)

/**
 * Sequential InputStream adapter for a range within a BlockDevice.
 */
class BlockDeviceInputStream(
    private val dev: BlockDevice,
    private val startOffset: Long = 0L,
    private val length: Long = (dev.size - startOffset).coerceAtLeast(0L)
) : InputStream() {
    private var currentPos: Long = startOffset
    private val endOffset: Long = startOffset + length

    override fun read(): Int {
        if (currentPos >= endOffset) return -1
        val b = ByteArray(1)
        val read = dev.readAt(currentPos, b, 0, 1)
        return if (read > 0) {
            currentPos++
            b[0].toInt() and 0xFF
        } else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        if (currentPos >= endOffset) return -1
        val toRead = len.toLong().coerceAtMost(endOffset - currentPos).toInt()
        if (toRead <= 0) return 0
        val read = dev.readAt(currentPos, b, off, toRead)
        if (read > 0) {
            currentPos += read
        }
        return if (read > 0) read else -1
    }

    override fun available(): Int {
        return (endOffset - currentPos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0L
        val toSkip = n.coerceAtMost(endOffset - currentPos)
        currentPos += toSkip
        return toSkip
    }
}

fun BlockDevice.asInputStream(
    startOffset: Long = 0L,
    length: Long = (size - startOffset).coerceAtLeast(0L)
): InputStream = BlockDeviceInputStream(this, startOffset, length)
