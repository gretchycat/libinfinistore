package com.infinifolder.smb

import org.junit.Assert.*
import org.junit.Test

class SmbClientPoolTest {

    @Test
    fun testGetSmbFileUrlFormatting() {
        val pool = SmbClientPool()
        val config = SmbConfig(
            id = "test-conn",
            displayName = "Test NAS",
            host = "192.168.1.50",
            port = 445
        )
        val creds = SmbCredentials()

        // Server root
        val rootFile = pool.getSmbFile(config, creds, "/")
        assertEquals("smb://192.168.1.50:445/", rootFile.path)

        // Single segment = share: must always end with trailing slash
        val shareFile = pool.getSmbFile(config, creds, "/Archive")
        assertEquals("smb://192.168.1.50:445/Archive/", shareFile.path)
        assertEquals("Archive/", shareFile.name)

        // Explicit directory: must end with trailing slash
        val dirFile = pool.getSmbFile(config, creds, "/Archive/8TB", isDirectory = true)
        assertEquals("smb://192.168.1.50:445/Archive/8TB/", dirFile.path)
        assertEquals("8TB/", dirFile.name)

        // Path ending with slash
        val dirWithSlash = pool.getSmbFile(config, creds, "/Archive/8TB/")
        assertEquals("smb://192.168.1.50:445/Archive/8TB/", dirWithSlash.path)

        // Explicit file: no trailing slash
        val regularFile = pool.getSmbFile(config, creds, "/Archive/8TB/document.pdf", isDirectory = false)
        assertEquals("smb://192.168.1.50:445/Archive/8TB/document.pdf", regularFile.path)
        assertEquals("document.pdf", regularFile.name)
    }
}
