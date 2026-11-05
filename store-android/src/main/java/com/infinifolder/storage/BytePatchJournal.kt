package com.infinifolder.storage

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.infinifolder.archive.RecoveryCancellation
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable, bounded byte differences. No source snapshot or whole-file array. */
object BytePatchJournal {
    data class Change(val offset: Long, val before: Byte, val after: Byte)
    private const val MAGIC = 0x49464832
    private const val MAX_CHANGES = 1024 * 1024
    private const val CHUNK = 65536

    fun write(file: File, length: Long, changes: Sequence<Change>) {
        require(length >= 0)
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            var count = 0; var previous = -1L
            temporary.outputStream().use { raw ->
                val output = java.io.DataOutputStream(raw.buffered())
                output.writeInt(MAGIC); output.writeLong(length); output.writeInt(0)
                for (change in changes) {
                    require(change.offset > previous && change.offset < length && ++count <= MAX_CHANGES && change.before != change.after) { "Invalid byte differences" }
                    output.writeLong(change.offset); output.writeByte(change.before.toInt()); output.writeByte(change.after.toInt())
                    previous = change.offset
                }
                output.flush(); raw.fd.sync()
            }
            RandomAccessFile(temporary, "rw").use { output -> output.seek(12); output.writeInt(count); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun length(file: File): Long = DataInputStream(file.inputStream().buffered()).use {
        require(it.readInt() == MAGIC) { "Unsupported byte-diff journal" }
        it.readLong().also { size -> require(size >= 0) }
    }

    fun read(file: File, accept: (Change) -> Unit) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Unsupported byte-diff journal" }
            val length = input.readLong(); val count = input.readInt()
            require(length >= 0 && count in 0..MAX_CHANGES && file.length() == 16L + count * 10L) { "Invalid byte-diff journal" }
            var previous = -1L
            repeat(count) {
                val change = Change(input.readLong(), input.readByte(), input.readByte())
                require(change.offset > previous && change.offset < length && change.before != change.after) { "Invalid byte-diff range" }
                accept(change); previous = change.offset
            }
        }
    }

    private fun chunks(file: File, accept: (Long, ByteArray, ByteArray) -> Unit) {
        val before = ByteArray(CHUNK); val after = ByteArray(CHUNK)
        var start = 0L; var count = 0
        fun deliver() { if (count > 0) accept(start, before.copyOf(count), after.copyOf(count)); count = 0 }
        read(file) { change ->
            if (count > 0 && (count == CHUNK || change.offset != start + count)) deliver()
            if (count == 0) start = change.offset
            before[count] = change.before; after[count] = change.after; count++
        }
        deliver()
    }

    private fun readExact(device: BlockDevice, offset: Long, count: Int): ByteArray {
        val bytes = ByteArray(count); var at = 0
        while (at < count) {
            val n = device.readAt(offset + at, bytes, at, count - at)
            if (n <= 0 || n > count - at) throw IOException("Cannot read changed range at ${offset + at}")
            at += n
        }
        return bytes
    }

    /** Preflight every range before writing. An interrupted merge can be resumed.
     * Rollback touches only attempted ranges; the durable journal survives failure. */
    fun merge(device: BlockDevice, file: File, pending: File, cancelled: () -> Boolean = { false }) {
        require(!device.isReadOnly && device.capabilities.containsAll(setOf(DeviceCapability.READ, DeviceCapability.WRITE, DeviceCapability.SEEK)))
        if (device.size != length(file)) throw IOException("Source length changed; byte differences retained")
        val resuming = pending.isFile
        chunks(file) { offset, before, after ->
            if (cancelled()) throw java.io.InterruptedIOException("Save cancelled")
            val actual = readExact(device, offset, before.size)
            if (actual.indices.any { actual[it] != before[it] && (!resuming || actual[it] != after[it]) })
                throw IOException("Source bytes changed at offset $offset; byte differences retained")
        }
        pending.outputStream().use { it.write(1); it.fd.sync() }
        var attempted = -1L
        try {
            chunks(file) { offset, _, after ->
                if (cancelled()) throw java.io.InterruptedIOException("Save cancelled")
                attempted = offset
                device.writeAt(offset, after, 0, after.size)
            }
            device.flush()
            chunks(file) { offset, _, after ->
                if (!readExact(device, offset, after.size).contentEquals(after)) throw IOException("Saved bytes failed verification at $offset")
            }
        } catch (error: Exception) {
            val restored = RecoveryCancellation.withoutCancellation {
                runCatching {
                    chunks(file) { offset, before, after ->
                        if (offset <= attempted) {
                            val actual = readExact(device, offset, before.size)
                            if (actual.indices.any { actual[it] != before[it] && actual[it] != after[it] })
                                throw IOException("Source changed during rollback at $offset")
                            device.writeAt(offset, before, 0, before.size)
                        }
                    }
                    device.flush()
                    chunks(file) { offset, before, _ ->
                        if (offset <= attempted && !readExact(device, offset, before.size).contentEquals(before))
                            throw IOException("Rollback verification failed at $offset")
                    }
                }.isSuccess
            }
            if (restored && !resuming) pending.delete()
            throw IOException(if (restored && !resuming) "Save failed; original changed ranges restored. Differences retained: ${error.message}"
                else "Save interrupted; recovery differences retained at ${file.absolutePath}: ${error.message}", error)
        }
        // Caller clears the journal durably before removing pending: a crash here
        // remains an idempotently resumable merge rather than a stale clean draft.
    }

    /** Stream an explicit copy into a newly created native target without caching it. */
    fun export(device: BlockDevice, file: File, input: InputStream, cancelled: () -> Boolean = { false }) {
        require(device.size == 0L && !device.isReadOnly && DeviceCapability.TRUNCATE in device.capabilities) { "Native copy requires a new resizable target" }
        val size = length(file)
        try {
            device.truncate(size)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var position = 0L
            val output = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (cancelled()) throw java.io.InterruptedIOException("Copy cancelled")
                    device.writeAt(position, bytes, offset, count); digest.update(bytes, offset, count); position += count
                }
            }
            render(file, input, output, cancelled, verifyOriginal = false)
            device.flush()
            val expected = digest.digest()
            val actual = java.security.MessageDigest.getInstance("SHA-256")
            var at = 0L
            while (at < size) {
                if (cancelled()) throw java.io.InterruptedIOException("Copy verification cancelled")
                val bytes = readExact(device, at, minOf(CHUNK.toLong(), size - at).toInt())
                actual.update(bytes); at += bytes.size
            }
            if (!expected.contentEquals(actual.digest())) throw IOException("Exported bytes failed verification")
        } catch (error: Exception) {
            val cleared = RecoveryCancellation.withoutCancellation { runCatching { device.truncate(0); device.flush() }.isSuccess }
            throw IOException("Copy failed. " + (if (cleared) "New target cleared. " else "Partial target could not be cleared. ") + (error.message ?: "I/O error"), error)
        }
    }

    /** Explicit sequential-write fallback, intended for a budgeted DiskCache lease. */
    fun render(file: File, input: InputStream, output: OutputStream, cancelled: () -> Boolean = { false },
        verifyOriginal: Boolean = true) {
        val length = length(file); val buffer = ByteArray(CHUNK); var position = 0L
        fun copyUntil(end: Long) {
            while (position < end) {
                if (cancelled()) throw java.io.InterruptedIOException("Save cancelled")
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), end - position).toInt())
                if (n <= 0) throw IOException("Source ended before offset $position")
                output.write(buffer, 0, n); position += n
            }
        }
        chunks(file) { offset, before, after ->
            copyUntil(offset)
            var at = 0
            while (at < before.size) {
                if (cancelled()) throw java.io.InterruptedIOException("Save cancelled")
                val n = input.read(buffer, at, before.size - at)
                if (n <= 0) throw IOException("Source ended inside an edited range")
                at += n
            }
            if (verifyOriginal && !buffer.copyOf(before.size).contentEquals(before)) throw IOException("Source bytes changed at $offset")
            output.write(after); position += before.size
        }
        copyUntil(length)
        if (input.read() != -1) throw IOException("Source length changed")
    }
}
