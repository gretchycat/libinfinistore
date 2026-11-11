package com.infinifolder.sftp

import android.net.TestUri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SftpUriTest {

    @Before
    fun setup() {
        SftpUri.uriFactory = { TestUri(it) }
    }

    @After
    fun teardown() {
        SftpUri.uriFactory = { TestUri(it) }
    }

    @Test
    fun testBuildAndParse() {
        val uri = SftpUri.build("conn-123", "/var/log/syslog")
        assertEquals("sftp://conn-123/var/log/syslog", uri.toString())
        assertTrue(SftpUri.isSftpUri(uri))
        assertEquals("conn-123", SftpUri.getConnectionId(uri))
        assertEquals("/var/log/syslog", SftpUri.getRemotePath(uri))
    }

    @Test
    fun testSshfsSchemeSupported() {
        val uri = TestUri("sshfs://my-server/home/user/doc.txt")
        assertTrue(SftpUri.isSftpUri(uri))
        assertEquals("my-server", SftpUri.getConnectionId(uri))
        assertEquals("/home/user/doc.txt", SftpUri.getRemotePath(uri))
    }

    @Test
    fun testNormalizeRemotePath() {
        assertEquals("/", SftpUri.normalizeRemotePath(""))
        assertEquals("/", SftpUri.normalizeRemotePath("/"))
        assertEquals("/", SftpUri.normalizeRemotePath("///"))
        assertEquals("/home/user", SftpUri.normalizeRemotePath("home/user"))
        assertEquals("/home/user", SftpUri.normalizeRemotePath("/home/user/"))
        assertEquals("/home/user", SftpUri.normalizeRemotePath("/home/./user"))
        assertEquals("/home", SftpUri.normalizeRemotePath("/home/user/.."))
        assertEquals("/a/c", SftpUri.normalizeRemotePath("/a/b/../c"))
        assertEquals("/", SftpUri.normalizeRemotePath("/.."))
    }

    @Test
    fun testGetParentUri() {
        val rootUri = SftpUri.build("conn-1", "/")
        assertNull(SftpUri.getParentUri(rootUri))

        val subUri = SftpUri.build("conn-1", "/etc/nginx/sites-available")
        val parent1 = SftpUri.getParentUri(subUri)
        assertEquals("sftp://conn-1/etc/nginx", parent1?.toString())

        val parent2 = SftpUri.getParentUri(parent1!!)
        assertEquals("sftp://conn-1/etc", parent2?.toString())

        val parent3 = SftpUri.getParentUri(parent2!!)
        assertEquals("sftp://conn-1/", parent3?.toString())
    }

    @Test
    fun testNotSftpUri() {
        assertFalse(SftpUri.isSftpUri(TestUri("file:///data/local/tmp")))
        assertFalse(SftpUri.isSftpUri(TestUri("content://com.android.providers/doc")))
        assertFalse(SftpUri.isSftpUri(TestUri("apps://installed")))
    }
}
