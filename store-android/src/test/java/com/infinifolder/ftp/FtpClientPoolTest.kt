package com.infinifolder.ftp

import org.apache.commons.net.ftp.FTPClient
import org.junit.Assert.*
import org.junit.Test

class FtpClientPoolTest {

    @Test
    fun testSafeReplyStringWhenNull() {
        val client = FTPClient()
        // Unconnected client has null replyString
        val reply = client.safeReplyString()
        assertNotNull(reply)
        assertTrue(reply.isNotEmpty())
        assertEquals("No reply from server", reply)
    }

    @Test
    fun testTestConnectionUnreachableHost() {
        val pool = FtpClientPool(connectTimeoutMs = 500)
        val config = FtpConfig(
            id = "test-ftp",
            displayName = "Unreachable FTP",
            host = "192.0.2.1", // TEST-NET-1 (non-routable)
            port = 21,
            isAnonymous = true
        )
        val creds = FtpCredentials()
        val result = pool.testConnection(config, creds)
        assertTrue(result.isFailure)
        val errorMsg = result.exceptionOrNull()?.message ?: ""
        assertFalse(errorMsg.contains("getReplyString(...) must not be null"))
    }
}
