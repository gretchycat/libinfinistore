package com.infinifolder.storage

import android.net.TestUri
import com.infinifolder.model.*
import com.infinifolder.torrent.TorrentCheckpointCodec
import com.infinifolder.torrent.TorrentCheckpointIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.BitSet

class TransferCheckpointStoreTest {
    private class Backend : StorageProvider {
        override val id = "mock"
        val files = mutableMapOf("dir/file.incomplete" to "payload".toByteArray())
        var failList = false
        var failCreate = false
        var modified: Long? = 100L
        val destination = ResourceRef(id, "dir/file.incomplete")
        fun entry(path: String) = ResourceEntry(ResourceRef(id, path), ResourceMetadata(path.substringAfterLast('/'),
            "application/octet-stream", files[path]?.size?.toLong(), modified, false), TestUri("mock://$path"))
        override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = if (files.containsKey(ref.documentId))
            ProviderResult.Success(entry(ref.documentId)) else ProviderResult.Failure("missing")
        override fun parent(ref: ResourceRef) = ProviderResult.Success(ResourceRef(id, "dir"))
        override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = if (failList) ProviderResult.Failure("offline")
            else ProviderResult.Success(files.keys.map(::entry))
        override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> {
            val path = "${parent.documentId}/$name"
            if (failCreate || files.containsKey(path)) return ProviderResult.Failure("denied")
            files[path] = byteArrayOf()
            return ProviderResult.Success(entry(path))
        }
        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = files[ref.documentId]?.let {
            ProviderResult.Success(ByteArrayInputStream(it)) } ?: ProviderResult.Failure("missing")
        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = ProviderResult.Success(object : ByteArrayOutputStream() {
            override fun close() { files[ref.documentId] = toByteArray() }
        })
        override fun delete(ref: ResourceRef): ProviderResult<kotlin.Unit> { files.remove(ref.documentId); return ProviderResult.Success(kotlin.Unit) }
        override fun createDirectory(parent: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }
    private val cachePath = "dir/file.incomplete.cache"
    private fun bytes() = TorrentCheckpointCodec.encode(TorrentCheckpointIdentity("mock:dir/file.incomplete", "hash", "file", 4, 2, 7, 0, 7),
        BitSet().apply { set(0) }, true, "fingerprint")
    @Test fun createsSiblingAndRoundTripsCheckpoint() {
        val backend = Backend()
        val store = SiblingTransferCheckpointStore(backend, backend.destination)
        assertNull(store.read())
        val bytes = bytes()
        store.write(bytes)
        assertArrayEquals(bytes, store.read())
        assertTrue(backend.files.containsKey(cachePath))
    }
    @Test fun unrelatedCacheFileIsNeitherOverwrittenNorDeleted() {
        val backend = Backend().apply { files[cachePath] = "personal data".toByteArray() }
        val store = SiblingTransferCheckpointStore(backend, backend.destination)
        assertThrows(IOException::class.java) { store.write(bytes()) }
        store.delete()
        assertEquals("personal data", backend.files.getValue(cachePath).toString(Charsets.UTF_8))
    }
    @Test fun completionCleanupPreservesDownloadBytes() {
        val backend = Backend()
        val store = SiblingTransferCheckpointStore(backend, backend.destination)
        store.write(bytes())
        store.delete()
        assertFalse(backend.files.containsKey(cachePath))
        assertEquals("payload", backend.files.getValue(backend.destination.documentId).toString(Charsets.UTF_8))
    }
    @Test fun corruptOwnedCheckpointCanBeRebuilt() {
        val backend = Backend().apply { files[cachePath] = bytes().toString(Charsets.ISO_8859_1)
            .replace("pieces=", "pieces=CORRUPT").toByteArray(Charsets.ISO_8859_1) }
        val store = SiblingTransferCheckpointStore(backend, backend.destination)
        val repaired = bytes()
        store.write(repaired)
        assertArrayEquals(repaired, store.read())
    }
    @Test fun listingFailureIsNotMistakenForAbsentSidecar() {
        val backend = Backend().apply { failList = true }
        assertThrows(IOException::class.java) { SiblingTransferCheckpointStore(backend, backend.destination).read() }
    }
    @Test fun missingModificationMetadataCannotProduceTrustedFingerprint() {
        val backend = Backend().apply { modified = null }
        assertNull(SiblingTransferCheckpointStore(backend, backend.destination).fingerprint())
    }
    @Test fun oversizedSidecarIsRejectedWithoutChangingDownload() {
        val backend = Backend().apply { files[cachePath] = ByteArray(4 * 1024 * 1024 + 1) }
        assertThrows(IOException::class.java) { SiblingTransferCheckpointStore(backend, backend.destination).read() }
        assertEquals("payload", backend.files.getValue(backend.destination.documentId).toString(Charsets.UTF_8))
    }
    @Test fun createFailureLeavesDownloadUnchanged() {
        val backend = Backend().apply { failCreate = true }
        assertThrows(IOException::class.java) { SiblingTransferCheckpointStore(backend, backend.destination).write(bytes()) }
        assertEquals(setOf(backend.destination.documentId), backend.files.keys)
    }
}
