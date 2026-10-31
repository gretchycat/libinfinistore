package com.infinifolder.storage

import com.infinifolder.archive.*
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamFormatProbeTest {
    @Test fun headerOverridesWrongNameAndMimeWithoutMovingTheCursor() {
        val device = MemoryBlockDevice(byteArrayOf(80,75,3,4,0,0,0,0), true)
        val source = BlockDeviceSource(device, StreamMetadata(ContentType(mimeType = "image/png"), name = "wrong.tar"))
        source.use {
            val result = StreamFormatProbe.inspect(ProbeReader(source, source.metadata))
            assertEquals("application/zip", result.metadata.content.mimeType)
            assertEquals("wrong.tar", result.metadata.name)
            assertTrue(result.metadata.warnings.isNotEmpty())
            val first = ByteArray(1); assertEquals(1, source.read(first)); assertEquals(80, first[0].toInt())
            assertEquals(0, device.readAt(device.size, ByteArray(1)))
        }
    }
    @Test fun footerProbeOnLargeSourceReadsOnlyBoundedRanges() {
        var fetched = 0
        val source = object : PositionalReadable {
            val size = 9L shl 30
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                requireRange(position, buffer, offset, length)
                if (length == 0) return 0
                if (position >= size) return -1
                val n = minOf(length.toLong(), size - position).toInt(); fetched += n
                buffer.fill(0, offset, offset + n)
                if (position == size - 512) "conectix".toByteArray().copyInto(buffer, offset)
                return n
            }
        }
        val result = StreamFormatProbe.inspect(ProbeReader(source, StreamMetadata(name = "disk.txt", length = source.size),
            ProbeBudget(maxNanos = Long.MAX_VALUE)))
        assertEquals("application/x-vhd", result.metadata.content.mimeType)
        assertTrue("Fetched $fetched bytes", fetched < 4096)
    }
    @Test fun rawAndBudgetExhaustionDoNotInventAFormat() {
        assertTrue(StreamFormatProbe.inspectPrefix(StreamMetadata(name = "missing.flac"), byteArrayOf()).candidates.isEmpty())
        val source = BlockDeviceSource(MemoryBlockDevice(ByteArray(2048), true))
        source.use {
            assertThrows(IOException::class.java) { StreamFormatProbe.inspect(ProbeReader(source, source.metadata, ProbeBudget(maxBytes = 1))) }
        }
    }
}
