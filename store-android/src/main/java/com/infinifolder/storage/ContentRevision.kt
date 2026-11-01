package com.infinifolder.storage

import java.io.InputStream
import java.security.MessageDigest

/** Fingerprint of source bytes, never of decoded/reformatted editor text. */
data class ContentRevision(val size: Long, val sha256: String) {
    companion object {
        fun of(bytes: ByteArray): ContentRevision = bytes.inputStream().use(::read)
        fun read(input: InputStream): ContentRevision =
            io.github.gretchycat.infinistream.InputStreamSource(input, ownsInput = false).use { read(it) }

        /** Borrows the source; fingerprint output describes original encoded bytes only. */
        fun read(source: io.github.gretchycat.infinistream.ByteSource,
            cancellation: io.github.gretchycat.infinistream.CancellationToken = io.github.gretchycat.infinistream.CancellationToken.NONE,
            progress: io.github.gretchycat.infinistream.ProgressObserver? = null): ContentRevision =
            StorageActivity.track(StorageActivity.Kind.VERIFICATION) {
                val digest = MessageDigest.getInstance("SHA-256")
                val sink = object : io.github.gretchycat.infinistream.ByteSink {
                    override val metadata = io.github.gretchycat.infinistream.StreamMetadata(
                        capabilities = setOf(io.github.gretchycat.infinistream.Capability.WRITE))
                    override fun write(buffer: ByteArray, offset: Int, length: Int) { digest.update(buffer, offset, length) }
                    override fun close() {}
                }
                val size = io.github.gretchycat.infinistream.copy(source, sink, cancellation, progress)
                ContentRevision(size, digest.digest().joinToString("") { "%02x".format(it) })
            }
    }
}

class RevisionConflictException : java.io.IOException("The original changed since it was opened. Reload it or save a copy; your edits have been retained.")
