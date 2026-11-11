package com.infinifolder.smb

import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmbUriTest {

    @Before
    fun setUp() {
        SmbUri.uriFactory = { str -> android.net.TestUri(str) }
    }

    @Test
    fun testBuildAndParse() {
        val uri = SmbUri.build("nas_conn", "/shared/movies/video.mp4")
        assertTrue(SmbUri.isSmbUri(uri))
        assertEquals("nas_conn", SmbUri.getConnectionId(uri))
        assertEquals("/shared/movies/video.mp4", SmbUri.getRemotePath(uri))
        assertEquals("shared", SmbUri.getShareName(uri))
        assertEquals("movies/video.mp4", SmbUri.getShareRelativePath(uri))
    }

    @Test
    fun testRootAndShare() {
        val root = SmbUri.build("nas_conn", "/")
        assertEquals("/", SmbUri.getRemotePath(root))
        assertNull(SmbUri.getShareName(root))
        assertEquals("", SmbUri.getShareRelativePath(root))

        val shareRoot = SmbUri.build("nas_conn", "/public")
        assertEquals("public", SmbUri.getShareName(shareRoot))
        assertEquals("", SmbUri.getShareRelativePath(shareRoot))
    }

    @Test
    fun testParentUri() {
        val child = SmbUri.build("nas_conn", "/shared/docs/notes.txt")
        val parent = SmbUri.getParentUri(child)
        assertNotNull(parent)
        assertEquals("/shared/docs", SmbUri.getRemotePath(parent!!))
    }
}
