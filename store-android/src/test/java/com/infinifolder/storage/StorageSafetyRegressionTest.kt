package com.infinifolder.storage

import android.net.TestUri
import android.net.Uri
import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.MemoryBlockDevice
import com.infinifolder.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.file.Files

class StorageSafetyRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Backend(override val id: String = "memory") : StorageProvider {
        var bytes = "original".toByteArray()
        var failFirstWrite = false
        var writes = 0
        var deviceClosed = false
        override fun refForUri(uri: Uri) = if (uri.scheme == id) ResourceRef(id, uri.toString()) else null
        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = ProviderResult.Success(ByteArrayInputStream(bytes))
        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> {
            val fail = failFirstWrite && writes++ == 0
            val output = object : ByteArrayOutputStream() {
                override fun write(data: ByteArray, offset: Int, length: Int) {
                    if (fail) { bytes = "damaged".toByteArray(); throw IOException("Connection lost") }
                    super.write(data, offset, length)
                }
                override fun close() { if (!fail) bytes = toByteArray() }
            }
            return ProviderResult.Success(output)
        }
        override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<BlockDevice> {
            val memory = MemoryBlockDevice(bytes, isReadOnly = true)
            return ProviderResult.Success(object : BlockDevice by memory {
                override fun close() { deviceClosed = true }
            })
        }
        override fun list(parent: ResourceRef) = ProviderResult.Success(emptyList<ResourceEntry>())
        override fun createDirectory(parent: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun createFile(parent: ResourceRef, name: String, mimeType: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun delete(ref: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.DELETE)
        override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }

    @Test fun failedStreamingSaveRestoresOriginalAndRetainsRecovery() {
        val backend = Backend().apply { failFirstWrite = true }
        val recovery = temporary.newFolder("recovery")
        val result = ContentReplacement.replace(backend, ResourceRef("memory", "memory://file"), "edited".toByteArray(), recovery)
        assertTrue(result is ProviderResult.Failure)
        assertEquals("original", String(backend.bytes))
        val backup = recovery.walkTopDown().first { it.name == "original.bin" }
        assertEquals("original", backup.readText())
    }

    @Test fun verifiedStreamingSaveRemovesRecoveryCopy() {
        val backend = Backend()
        val recovery = temporary.newFolder("recovery")
        assertTrue(ContentReplacement.replace(backend, ResourceRef("memory", "memory://file"), "edited".toByteArray(), recovery) is ProviderResult.Success)
        assertEquals("edited", String(backend.bytes))
        assertTrue(recovery.listFiles()!!.isEmpty())
    }

    @Test fun routerUsesBackendOwnershipAndNeverDowngradesWritableOpen() {
        val first = Backend("first")
        val second = Backend("second")
        val router = RoutingStorageProvider(first, second)
        val ref = router.refForUri(TestUri("second://file"))!!
        assertEquals("second", ref.providerId)
        assertTrue(router.openSeekable(ref, true) is ProviderResult.Unsupported)
        assertTrue(second.deviceClosed)
        assertFalse(first.deviceClosed)
        assertTrue(router.openSeekable(ResourceRef("absent", "file")) is ProviderResult.Failure)
    }

    @Test fun localCreateCollisionDoesNotTruncateExistingFile() {
        val root = temporary.newFolder("local")
        val original = File(root, "file.txt").apply { writeText("original") }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { LocalFileStore(root).createFile(root, "file.txt") }
        assertEquals("original", original.readText())
    }

    @Test fun deletingDirectoryLinkLeavesTargetContentsIntact() {
        val root = temporary.newFolder("links")
        val target = File(root, "target").apply { mkdir() }
        val contents = File(target, "file.txt").apply { writeText("original") }
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        LocalFileStore(root).delete(link)
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("original", contents.readText())
    }

    @Test fun atomicReplacementPreservesSymbolicLinkAndUpdatesItsTarget() {
        val root = temporary.newFolder("atomic")
        val target = File(root, "target").apply { writeText("original") }
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        AtomicFileReplacement.replace(link, "edited".toByteArray())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("edited", target.readText())
        assertEquals(setOf("target", "link"), root.list()!!.toSet())
    }

}
