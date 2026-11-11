package com.infinifolder.ftp

import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FtpUriTest {

    @Before
    fun setUp() {
        FtpUri.uriFactory = { str -> android.net.TestUri(str) }
    }

    @Test
    fun testBuildAndParse() {
        val uri = FtpUri.build("conn123", "/documents/work/report.pdf")
        assertTrue(FtpUri.isFtpUri(uri))
        assertEquals("conn123", FtpUri.getConnectionId(uri))
        assertEquals("/documents/work/report.pdf", FtpUri.getRemotePath(uri))
    }

    @Test
    fun testPathNormalization() {
        assertEquals("/documents/report.pdf", FtpUri.normalizeRemotePath("/documents/./sub/../report.pdf"))
        assertEquals("/", FtpUri.normalizeRemotePath(""))
        assertEquals("/", FtpUri.normalizeRemotePath("/"))
    }

    @Test
    fun testParentUri() {
        val child = FtpUri.build("conn1", "/photos/vacation/beach.jpg")
        val parent = FtpUri.getParentUri(child)
        assertNotNull(parent)
        assertEquals("/photos/vacation", FtpUri.getRemotePath(parent!!))

        val root = FtpUri.build("conn1", "/")
        assertNull(FtpUri.getParentUri(root))
    }
}
