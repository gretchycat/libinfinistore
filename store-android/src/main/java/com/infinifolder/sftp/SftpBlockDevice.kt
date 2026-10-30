package com.infinifolder.sftp

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.infinifolder.archive.requireDeviceRange
import com.jcraft.jsch.ChannelSftp
import java.io.IOException

/**
 * Random-access BlockDevice backed by an SFTP remote file using JSch.
 * Reads are performed at specific offsets using ChannelSftp.get(..., skip).
 * Writable instances send offset writes directly to the remote file using SFTP WRITE requests.
 */
class SftpBlockDevice(
    private val clientPool: SftpClientPool,
    private val config: SftpConfig,
    private val credentials: SftpCredentials,
    val remotePath: String,
    override val isReadOnly: Boolean = true,
    initialSize: Long? = null
) : BlockDevice {

    init { require(initialSize == null || initialSize >= 0L) }

    private var channel: ChannelSftp? = null
    private var closed = false
    private var currentSize: Long = initialSize ?: fetchSize()
    private var readCount = 0L
    private var bytesReturned = 0L
    private var lastReportMs = 0L

    private fun fetchSize(): Long = clientPool.withChannel(config, credentials) { channel ->
        channel.stat(remotePath).size
    }

    override val size: Long get() = currentSize

    override val capabilities: Set<DeviceCapability>
        get() = if (isReadOnly) {
            setOf(DeviceCapability.READ, DeviceCapability.SEEK)
        } else {
            setOf(
                DeviceCapability.READ,
                DeviceCapability.WRITE,
                DeviceCapability.FLUSH,
                DeviceCapability.TRUNCATE,
                DeviceCapability.SEEK
            )
        }

    @Synchronized override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        requireDeviceRange(offset, buffer, dstOffset, length)
        if (offset >= currentSize || length == 0) return 0
        val toRead = length.toLong().coerceAtMost(currentSize - offset).toInt()

        check(!closed) { "SFTP range reader is closed" }
        val started = android.os.SystemClock.uptimeMillis()
        var stage = "opening-channel"
        var total = 0
        return try {
            // Each range closes early. Large streaming read-ahead queues must
            // otherwise be drained on every close, even for a tiny metadata read.
            val reader = channel ?: clientPool.openReadChannel(config, credentials, bulkRequests = 1).also { channel = it }
            stage = "opening-range"
            val stream = reader.get(remotePath, null, offset)
            stream.use { inStream ->
                stage = "reading-range"
                while (total < toRead) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("SFTP range read interrupted")
                    val r = inStream.read(buffer, dstOffset + total, toRead - total)
                    if (r <= 0) break
                    total += r
                }
                stage = "closing-range"
                total
            }.also {
                readCount++
                bytesReturned += it
                val now = android.os.SystemClock.uptimeMillis()
                if (now - started >= 1000L || now - lastReportMs >= 5000L) {
                    lastReportMs = now
                    com.infinifolder.storage.DiagnosticLog.record(
                        "sftp.range.progress", "reader=${System.identityHashCode(this)} offset=$offset requested=$toRead returned=$it elapsedMs=${now - started} reads=$readCount bytes=$bytesReturned bulkRequests=1"
                    )
                }
            }
        } catch (e: Exception) {
            val causes = generateSequence(e as Throwable) { it.cause }.take(4)
                .joinToString(" -> ") { "${it.javaClass.simpleName}: ${it.message}" }
            com.infinifolder.storage.DiagnosticLog.record(
                "sftp.range.failed", "reader=${System.identityHashCode(this)} stage=$stage offset=$offset requested=$toRead returned=$total elapsedMs=${android.os.SystemClock.uptimeMillis() - started} reads=$readCount bytes=$bytesReturned causes=$causes", e
            )
            channel?.disconnect()
            channel = null
            throw IOException("SFTP range read failed during $stage at offset $offset ($toRead bytes): ${e.message}", e)
        }
    }

    @Synchronized override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        if (isReadOnly) throw IOException("SFTP BlockDevice is read-only")
        requireDeviceRange(offset, buffer, srcOffset, length)
        if (offset + length > currentSize)
            throw IOException("Invalid SFTP random-write range")
        if (length == 0) return
        check(!closed) { "SFTP random-write device is closed" }
        val started = android.os.SystemClock.uptimeMillis()
        try { clientPool.withChannel(config, credentials) { sftp ->
            // JSch's RESUME stream opens without truncation and initializes its
            // SFTP write offset to (remote size + supplied offset). The negative
            // delta positions writes at an arbitrary byte inside the pre-sized file.
            sftp.put(remotePath, null, ChannelSftp.RESUME, offset - currentSize).use { output ->
                output.write(buffer, srcOffset, length)
                output.flush()
            }
        } } catch (error: Exception) {
            val code = (error as? com.jcraft.jsch.SftpException)?.id?.let { " (SFTP code $it)" }.orEmpty()
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            throw IOException("SFTP random write failed at offset $offset ($length bytes): $detail$code", error)
        }
        currentSize = maxOf(currentSize, offset + length)
        com.infinifolder.storage.DiagnosticLog.record("sftp.range.write",
            "path=$remotePath offset=$offset bytes=$length elapsedMs=${android.os.SystemClock.uptimeMillis() - started}")
    }

    @Synchronized override fun truncate(newSize: Long) {
        if (isReadOnly) throw IOException("SFTP BlockDevice is read-only")
        if (newSize < 0L) throw IOException("Negative SFTP file size")
        check(!closed) { "SFTP random-write device is closed" }
        clientPool.withChannel(config, credentials) { sftp ->
            val attrs = sftp.stat(remotePath)
            attrs.setSIZE(newSize)
            sftp.setStat(remotePath, attrs)
        }
        currentSize = newSize
    }

    override fun flush() { /* each offset write is completed by the server before returning */ }

    @Synchronized override fun close() {
        closed = true
        channel?.disconnect()
        channel = null
    }
}
