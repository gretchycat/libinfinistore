package com.infinifolder.storage

import java.io.InputStream
import java.security.MessageDigest

/** Fingerprint of source bytes, never of decoded/reformatted editor text. */
data class ContentRevision(val size: Long, val sha256: String) {
    companion object {
        fun of(bytes: ByteArray): ContentRevision = bytes.inputStream().use(::read)
        fun read(input: InputStream): ContentRevision = StorageActivity.track(StorageActivity.Kind.VERIFICATION) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                size += n
            }
            ContentRevision(size, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}

class RevisionConflictException : java.io.IOException("The original changed since it was opened. Reload it or save a copy; your edits have been retained.")
