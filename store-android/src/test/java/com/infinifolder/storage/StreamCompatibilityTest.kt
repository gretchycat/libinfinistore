package com.infinifolder.storage

import com.infinifolder.archive.*
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamCompatibilityTest {
    @Test fun legacyEofAndIndependentPositionsTranslateWithoutCopying() {
        val device = MemoryBlockDevice("abcdef".toByteArray(), true)
        val source = BlockDeviceSource(device)
        val cursor = SourceCursor(source, source.metadata)
        cursor.seek(4)
        assertEquals("ef", cursor.asInputStream().readBytes().decodeToString())
        assertEquals("abcdef", source.asInputStream(false).readBytes().decodeToString())
        assertEquals(-1, source.readAt(6, ByteArray(1))); assertEquals(0, device.readAt(6, ByteArray(1)))
        assertFalse(Capability.WRITE in source.metadata.capabilities)
    }
    @Test fun sourceBackToLegacyDevicePreservesBoundsAndBorrowedOwnership() {
        var closes = 0
        val original = MemoryBlockDevice(byteArrayOf(1, 2, 3), true)
        val device = object : BlockDevice by original { override fun close() { closes++ } }
        val source = BlockDeviceSource(device, ownsDevice = false)
        val bridge = StreamBlockDevice(source)
        assertArrayEquals(byteArrayOf(2, 3), bridge.readBytes(1, 2))
        assertEquals(0, bridge.readAt(3, ByteArray(1)))
        assertThrows(IOException::class.java) { bridge.writeAt(0, byteArrayOf(2)) }
        bridge.close(); bridge.close(); assertEquals(0, closes)
    }
    @Test fun longPositionalReadsRemainLazy() {
        var fetched = 0
        val device = object : BlockDevice {
            override val size = 9L shl 30
            override val isReadOnly = true
            override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
                requireDeviceRange(offset, buffer, dstOffset, length)
                if (offset >= size) return 0
                fetched += length; buffer.fill(7, dstOffset, dstOffset + length); return length
            }
            override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) = throw IOException()
            override fun close() {}
        }
        val source = BlockDeviceSource(device)
        assertEquals(4, source.readAt(8L shl 30, ByteArray(4))); assertEquals(4, fetched)
    }
}
