package com.infinifolder.storage

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeStoreCapabilitiesTest {
    @Test fun registeredIdentificationServiceReadsActualPngBytesWithoutTheApp() {
        val bytes=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS1sAAAAASUVORK5CYII=")
        var read=0
        val input=object:PositionalReadable {
            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                requireRange(position,buffer,offset,length)
                if(length==0)return 0
                if(position>=bytes.size)return -1
                val count=minOf(length,bytes.size-position.toInt())
                bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+count);read+=count;return count
            }
        }
        val entry=StoreCapabilities.registry.query("identify",includeUnavailable=true).single {it.descriptor.id=="store.magic"}
        assertEquals(AvailabilityState.UNAVAILABLE,entry.availability.state) // No Android runtime on the test JVM.
        val probe=entry.handler.service(FormatProbe::class.java)!!
        val result=probe.detect(ProbeReader(input,StreamMetadata(length=bytes.size.toLong()),ProbeBudget(maxNanos=1_000_000_000))).single()
        assertEquals("image/png",result.content.mimeType)
        assertEquals(.5,result.confidence,0.0) // Identification does not claim full image validation.
        assertEquals(bytes.size,read)
    }
}
