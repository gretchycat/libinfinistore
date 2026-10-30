package com.infinifolder.ftp

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.infinifolder.archive.requireDeviceRange
import java.io.IOException

/**
 * Random-access BlockDevice backed by an FTP / FTPS remote file.
 * Reads are performed using the FTP REST (restart offset) command followed by RETR.
 * Random writes are disabled by default for FTP as standard FTP servers do not
 * universally support safe arbitrary ranged overwrites without truncating.
 */
class FtpBlockDevice(
    private val clientPool: FtpClientPool,
    private val config: FtpConfig,
    private val credentials: FtpCredentials,
    val remotePath: String,
    override val size: Long
) : BlockDevice {

    override val isReadOnly: Boolean = true

    override val capabilities: Set<DeviceCapability>
        get() = setOf(DeviceCapability.READ, DeviceCapability.SEEK)

    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= size || length == 0) return 0
        val toRead = length.toLong().coerceAtMost(size - offset).toInt()

        return clientPool.withClient(config, credentials) { client ->
            client.setRestartOffset(offset)
            val stream = client.retrieveFileStream(remotePath)
                ?: throw IOException("FTP server failed to open stream for '$remotePath' at offset $offset: ${client.safeReplyString()}")

            var total = 0
            try {
                while (total < toRead) {
                    val r = stream.read(buffer, dstOffset + total, toRead - total)
                    if (r <= 0) break
                    total += r
                }
            } finally {
                try { stream.close() } catch (_: Throwable) {}
                try { client.completePendingCommand() } catch (_: Throwable) {}
            }
            total
        }
    }

    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        throw IOException("Random-access writes are unsupported on FTP connections")
    }

    override fun flush() {}

    override fun close() {}
}
