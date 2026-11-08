package com.infinifolder.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

/** Opt-in real-server test: set INFINIFOLDER_SFTP_HOST/PORT/USER/KEY to enable it. */
class SftpRawProtocolIntegrationTest {
    @Test fun rawRenameAndCopyDataUseTheAuthenticatedSftpChannel() {
        val host = System.getenv("INFINIFOLDER_SFTP_HOST")
        val user = System.getenv("INFINIFOLDER_SFTP_USER")
        val key = System.getenv("INFINIFOLDER_SFTP_KEY")
        assumeTrue("Set INFINIFOLDER_SFTP_HOST, _USER, and _KEY to run a real-server SFTP test",
            !host.isNullOrBlank() && !user.isNullOrBlank() && !key.isNullOrBlank())
        val port = System.getenv("INFINIFOLDER_SFTP_PORT")?.toIntOrNull() ?: 22
        val jsch = JSch().apply { addIdentity(key) }
        val session = jsch.getSession(user, host, port).apply {
            setConfig("StrictHostKeyChecking", "no")
            connect(10_000)
        }
        val channel = (session.openChannel("sftp") as ChannelSftp).apply { connect(10_000) }
        val created = mutableListOf<String>()
        try {
            val root = channel.realpath(".")
            val token = UUID.randomUUID().toString()
            val source = "$root/if raw $token-å.txt"
            val renamed = "$root/if raw $token-renamed ñ.txt"
            val collision = "$root/if raw $token-existing.txt"
            val copied = "$root/if raw $token-copy.txt"
            val emptySource = "$root/if raw $token-empty-source"
            val emptyCopy = "$root/if raw $token-empty-copy"
            val largeSource = "$root/if raw $token-large-source"
            val largeCopy = "$root/if raw $token-large-copy"
            created += listOf(source, renamed, collision, copied, emptySource, emptyCopy, largeSource, largeCopy)
            val bytes = "SFTP raw packet path: spaces, UTF-8, and data".toByteArray(Charsets.UTF_8)
            channel.put(ByteArrayInputStream(bytes), source)
            SftpRawProtocol.renameNoReplace(channel, source, renamed) { false }
            assertFalse(runCatching { channel.lstat(source) }.isSuccess)
            assertEquals(bytes.size.toLong(), channel.stat(renamed).size)

            channel.put(ByteArrayInputStream("leave this file alone".toByteArray()), collision)
            val collisionFailure = runCatching { SftpRawProtocol.renameNoReplace(channel, renamed, collision) { false } }.exceptionOrNull()
            assertTrue("rename must preserve existing destinations", collisionFailure is SftpRawProtocol.StatusFailure)
            assertEquals(bytes.size.toLong(), channel.stat(renamed).size)
            assertEquals("leave this file alone".length.toLong(), channel.stat(collision).size)

            if (channel.getExtension("copy-data") == "1") {
                SftpRawProtocol.copyDataFile(channel, renamed, copied, bytes.size.toLong()) { false }
                assertEquals(bytes.size.toLong(), channel.stat(copied).size)
                val copyBytes = java.io.ByteArrayOutputStream().also { channel.get(copied, it) }.toByteArray()
                assertArrayEquals(bytes, copyBytes)
                channel.put(ByteArrayInputStream(ByteArray(0)), emptySource)
                SftpRawProtocol.copyDataFile(channel, emptySource, emptyCopy, 0L) { false }
                assertEquals(0L, channel.stat(emptyCopy).size)
                val largeBytes = ByteArray(8 * 1024 * 1024) { index -> (index * 31).toByte() }
                channel.put(ByteArrayInputStream(largeBytes), largeSource)
                SftpRawProtocol.copyDataFile(channel, largeSource, largeCopy, largeBytes.size.toLong()) { false }
                assertEquals(largeBytes.size.toLong(), channel.stat(largeCopy).size)
                val largeCopyBytes = java.io.ByteArrayOutputStream().also { channel.get(largeCopy, it) }.toByteArray()
                assertArrayEquals(largeBytes, largeCopyBytes)
            } else {
                val unsupported = runCatching {
                    SftpRawProtocol.copyDataFile(channel, renamed, copied, bytes.size.toLong()) { false }
                }.exceptionOrNull()
                assertTrue("unadvertised copy-data must return an explicit unsupported status",
                    unsupported is SftpRawProtocol.StatusFailure && unsupported.operation == "copy-data" && unsupported.status == 8)
            }
        } finally {
            created.forEach { runCatching { channel.rm(it) } }
            channel.disconnect()
            session.disconnect()
        }
    }
}
