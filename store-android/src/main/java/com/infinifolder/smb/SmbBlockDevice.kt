package com.infinifolder.smb

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.infinifolder.archive.requireDeviceRange
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Random-access BlockDevice backed directly by an SMB/CIFS file via JCIFS-NG.
 * Supports native offset-based reads and writes.
 */
class SmbBlockDevice(
    private val smbFile: SmbFile,
    override val isReadOnly: Boolean = false
) : BlockDevice {

    private val mode = if (isReadOnly) "r" else "rw"
    private var smbRaf = SmbRandomAccessFile(smbFile, mode)

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

    override val size: Long
        get() = synchronized(smbRaf) { smbRaf.length() }

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= size || length == 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()
        synchronized(smbRaf) {
            smbRaf.seek(offset)
            val read = smbRaf.read(buffer, dstOffset, toRead)
            return if (read < 0) 0 else read
        }
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("SmbBlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        if (length == 0) return
        synchronized(smbRaf) {
            smbRaf.seek(offset)
            smbRaf.write(buffer, srcOffset, length)
        }
    }

    override fun truncate(newSize: Long) {
        if (isReadOnly) throw IOException("SmbBlockDevice is read-only")
        if (newSize < 0) throw IOException("Negative size: $newSize")
        synchronized(smbRaf) {
            smbRaf.setLength(newSize)
        }
    }

    override fun flush() {
        // SMB2/3 writes are flushed on the file handle
    }

    override fun close() {
        synchronized(smbRaf) {
            smbRaf.close()
        }
    }
}
