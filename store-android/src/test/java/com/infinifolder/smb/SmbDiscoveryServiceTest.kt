package com.infinifolder.smb

import org.junit.Assert.*
import org.junit.Test

class SmbDiscoveryServiceTest {

    @Test
    fun testDiscoveredSmbServerDataClass() {
        val server = DiscoveredSmbServer(
            host = "192.168.1.100",
            name = "QNAP-TS251",
            port = 445,
            workgroup = "WORKGROUP",
            source = "WS-Discovery"
        )
        assertEquals("192.168.1.100", server.host)
        assertEquals("QNAP-TS251", server.name)
        assertEquals(445, server.port)
        assertEquals("WORKGROUP", server.workgroup)
        assertEquals("WS-Discovery", server.source)
    }

    @Test
    fun testDiscoveryCancellation() {
        val discovery = SmbDiscoveryService(null)
        discovery.cancel()
        // Cancellation should prevent prolonged scans
    }
}
