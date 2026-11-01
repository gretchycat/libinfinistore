package com.infinifolder.storage

import io.github.gretchycat.infinistream.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class ContentRevisionStreamTest {
    @Test fun streamedFingerprintMatchesLegacyBytesAndBorrowsInput() {
        var closes = 0
        val bytes = "encoded source bytes".toByteArray()
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closes++ } }
        val source = InputStreamSource(input)
        val progress = mutableListOf<Long>()
        assertEquals(ContentRevision.of(bytes), ContentRevision.read(source, progress = ProgressObserver { progress += it.completed }))
        assertEquals(listOf(bytes.size.toLong()), progress)
        assertEquals(0, closes)
        source.close()
        assertEquals(1, closes)
    }
    @Test fun unknownSizeZeroProgressAndCancellationStayExplicit() {
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        assertThrows(IOException::class.java) { ContentRevision.read(InputStreamSource(input)) }
        assertThrows(InterruptedIOException::class.java) {
            ContentRevision.read(InputStreamSource(ByteArrayInputStream(byteArrayOf(1))), CancellationToken { true })
        }
    }
}
