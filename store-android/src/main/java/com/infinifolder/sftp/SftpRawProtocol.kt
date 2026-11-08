package com.infinifolder.sftp

import com.jcraft.jsch.Buffer
import com.jcraft.jsch.ChannelSftp
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Small SFTP v3 operations missing from JSch's public API. JSch owns the channel
 * and connection; this adapter only serializes one request at a time while the
 * caller holds SftpClientPool's channel lock.
 */
internal object SftpRawProtocol {
    private const val SSH_FXP_OPEN = 3
    private const val SSH_FXP_CLOSE = 4
    private const val SSH_FXP_RENAME = 18
    private const val SSH_FXP_EXTENDED = 200
    private const val SSH_FXP_STATUS = 101
    private const val SSH_FXP_HANDLE = 102
    private const val SSH_FX_OK = 0
    private const val SSH_FX_OP_UNSUPPORTED = 8

    class StatusFailure(val status: Int, val operation: String, detail: String) : IOException(
        "SFTP $operation failed (status $status)${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
    )

    fun renameNoReplace(channel: ChannelSftp, sourcePath: String, destinationPath: String, isCancelled: () -> Boolean) {
        val source = sourcePath.toByteArray(StandardCharsets.UTF_8)
        val destination = destinationPath.toByteArray(StandardCharsets.UTF_8)
        expectStatus(channel, SSH_FXP_RENAME, "rename", isCancelled) { buffer ->
            buffer.putString(source)
            buffer.putString(destination)
        }
    }

    fun copyDataFile(
        channel: ChannelSftp,
        sourcePath: String,
        destinationPath: String,
        length: Long,
        isCancelled: () -> Boolean
    ) {
        require(length >= 0L) { "Negative SFTP copy length" }
        var sourceHandle: ByteArray? = null
        var destinationHandle: ByteArray? = null
        var failure: Exception? = null
        try {
            sourceHandle = open(channel, sourcePath, flags = 1, "open source", isCancelled)
            // WRITE | CREATE | EXCL. The random sibling path is never truncated.
            destinationHandle = open(channel, destinationPath, flags = 2 or 8 or 32, "create temporary destination", isCancelled)
            expectStatus(channel, SSH_FXP_EXTENDED, "copy-data", isCancelled) { buffer ->
                buffer.putString("copy-data".toByteArray(StandardCharsets.UTF_8))
                buffer.putString(sourceHandle)
                buffer.putLong(0L)
                buffer.putLong(length)
                buffer.putString(destinationHandle)
                buffer.putLong(0L)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            destinationHandle?.let { handle ->
                try { close(channel, handle, isCancelled = { false }) }
                catch (closeError: Exception) { if (failure == null) failure = closeError else failure?.addSuppressed(closeError) }
            }
            sourceHandle?.let { handle ->
                try { close(channel, handle, isCancelled = { false }) }
                catch (closeError: Exception) { if (failure == null) failure = closeError else failure?.addSuppressed(closeError) }
            }
        }
        failure?.let { throw it }
    }

    private fun open(channel: ChannelSftp, path: String, flags: Int, operation: String, isCancelled: () -> Boolean): ByteArray {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val response = request(channel, SSH_FXP_OPEN, operation, isCancelled) { buffer ->
            buffer.putString(pathBytes)
            buffer.putInt(flags)
            buffer.putInt(0) // SFTP v3 ATTRS: no optional fields.
        }
        if (response.type == SSH_FXP_STATUS) throw statusFailure(response, operation)
        if (response.type != SSH_FXP_HANDLE) throw IOException("Unexpected SFTP response to $operation: ${response.type}")
        val handle = response.buffer.getString()
        if (handle.isEmpty() || handle.size > 4096) throw IOException("Invalid SFTP file handle")
        return handle
    }

    private fun close(channel: ChannelSftp, handle: ByteArray, isCancelled: () -> Boolean) {
        expectStatus(channel, SSH_FXP_CLOSE, "close handle", isCancelled) { it.putString(handle) }
    }

    private fun expectStatus(
        channel: ChannelSftp,
        type: Int,
        operation: String,
        isCancelled: () -> Boolean,
        writeBody: (Buffer) -> Unit
    ) {
        val response = request(channel, type, operation, isCancelled, writeBody)
        if (response.type != SSH_FXP_STATUS) throw IOException("Unexpected SFTP response to $operation: ${response.type}")
        val error = statusFailureOrNull(response, operation)
        if (error != null) throw error
    }

    private data class Response(val type: Int, val requestId: Int, val buffer: Buffer)

    private fun request(
        channel: ChannelSftp,
        type: Int,
        operation: String,
        isCancelled: () -> Boolean,
        writeBody: (Buffer) -> Unit
    ): Response {
        checkCancelled(isCancelled)
        val output = field(channel, "obuf").get(channel) as Buffer
        val packet = field(channel, "opacket").get(channel) ?: throw IOException("SFTP output packet is unavailable")
        invoke(packet, "reset")
        val body = Buffer()
        writeBody(body)
        val requestId = field(channel, "seq").getInt(channel)
        val sftpLength = 1 + 4 + body.getLength()
        invoke(channel, "putHEAD", arrayOf(Buffer::class.java, Byte::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(output, type.toByte(), sftpLength))
        output.putInt(requestId)
        val bodyBytes = bufferBytes(body)
        output.putByte(bodyBytes)
        field(channel, "seq").setInt(channel, requestId + 1)
        val session = channel.getSession()
        invoke(session, "write", arrayOf(Class.forName("com.jcraft.jsch.Packet"), com.jcraft.jsch.Channel::class.java, Int::class.javaPrimitiveType!!),
            arrayOf(packet, channel, output.getLength()))

        val responseBuffer = field(channel, "buf").get(channel) as Buffer
        val input = field(channel, "io_in").get(channel) as InputStream
        val headerClass = Class.forName("com.jcraft.jsch.ChannelSftp\$Header")
        val headerConstructor = headerClass.getDeclaredConstructor().apply { isAccessible = true }
        val header = headerConstructor.newInstance()
        awaitAvailable(channel, input, 9, isCancelled)
        invoke(channel, "header", arrayOf(Buffer::class.java, headerClass), arrayOf(responseBuffer, header))
        val responseId = headerClass.getDeclaredField("rid").apply { isAccessible = true }.getInt(header)
        val responseType = headerClass.getDeclaredField("type").apply { isAccessible = true }.getInt(header)
        val remaining = headerClass.getDeclaredField("length").apply { isAccessible = true }.getInt(header)
        if (responseId != requestId) throw IOException("Mismatched SFTP response ID for $operation")
        if (remaining !in 0..(1024 * 1024)) throw IOException("Invalid SFTP response length for $operation")
        awaitAvailable(channel, input, remaining, isCancelled)
        invoke(channel, "fill", arrayOf(Buffer::class.java, Int::class.javaPrimitiveType!!), arrayOf(responseBuffer, remaining))
        return Response(responseType, responseId, responseBuffer)
    }

    private fun statusFailure(response: Response, operation: String): StatusFailure {
        val code = response.buffer.getInt()
        val message = runCatching { String(response.buffer.getString(), StandardCharsets.UTF_8) }.getOrDefault("")
        return StatusFailure(code, operation, message)
    }

    private fun statusFailureOrNull(response: Response, operation: String): StatusFailure? {
        val code = response.buffer.getInt()
        if (code == SSH_FX_OK) return null
        val message = runCatching { String(response.buffer.getString(), StandardCharsets.UTF_8) }.getOrDefault("")
        return StatusFailure(code, operation, message)
    }

    private fun awaitAvailable(channel: ChannelSftp, input: InputStream, count: Int, isCancelled: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.DAYS.toNanos(1)
        while (input.available() < count) {
            checkCancelled(isCancelled)
            if (!channel.isConnected) throw IOException("SFTP channel disconnected while waiting for a response")
            if (System.nanoTime() >= deadline) throw SocketTimeoutException("Timed out waiting for SFTP response")
            try { Thread.sleep(10L) }
            catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("SFTP operation cancelled").apply { initCause(error) }
            }
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        com.infinifolder.archive.RecoveryCancellation.check()
        if (isCancelled()) throw InterruptedIOException("SFTP operation cancelled")
    }

    private fun bufferBytes(buffer: Buffer): ByteArray {
        val length = buffer.getLength()
        val start = buffer.getOffSet()
        val raw = Buffer::class.java.getDeclaredField("buffer").apply { isAccessible = true }.get(buffer) as ByteArray
        return raw.copyOfRange(start, length)
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun invoke(target: Any, name: String, parameterTypes: Array<Class<*>> = emptyArray(), args: Array<Any> = emptyArray()): Any? = try {
        target.javaClass.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }.invoke(target, *args)
    } catch (error: java.lang.reflect.InvocationTargetException) {
        val cause = error.targetException
        when (cause) {
            is Exception -> throw cause
            else -> throw IOException("SFTP $name failed", cause)
        }
    }
}
