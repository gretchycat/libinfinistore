package com.infinifolder.storage

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.infinifolder.archive.FileBlockDevice
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceRef
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Explicit publication boundary: flush only syncs the working copy; close discards it. */
interface StagedBlockCommit {
    fun commit()
}

/** Full-file fallback for providers with sequential replacement but no native offset writes. */
class StagedRandomWriteDevice private constructor(
    private val provider: StorageProvider,
    private val ref: ResourceRef,
    private val working: File,
    private val device: FileBlockDevice,
    private var revision: ContentRevision,
    private val recoveryRoot: File
) : BlockDevice, StagedBlockCommit {
    private var dirty = false
    private var closed = false
    override val isReadOnly = false
    override val capabilities = setOf(DeviceCapability.READ, DeviceCapability.WRITE, DeviceCapability.FLUSH, DeviceCapability.SEEK)
    override val size: Long get() = device.size

    @Synchronized override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        check(!closed) { "Staged image is closed" }
        return device.readAt(offset, buffer, dstOffset, length)
    }

    @Synchronized override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) {
        check(!closed) { "Staged image is closed" }
        if (offset < 0 || length < 0 || offset > size || length.toLong() > size - offset)
            throw IOException("Image writes must stay within the existing image size")
        device.writeAt(offset, buffer, srcOffset, length)
        if (length > 0) dirty = true
    }

    @Synchronized override fun flush() {
        check(!closed) { "Staged image is closed" }
        // FileBlockDevice's generic force is best-effort; require a successful sync here.
        RandomAccessFile(working, "rw").use { it.fd.sync() }
    }

    @Synchronized override fun commit() {
        check(!closed) { "Staged image is closed" }
        if (!dirty) return
        flush()
        DiagnosticLog.record("image.publish", "provider=${ref.providerId} bytes=$size")
        when (val result = ContentReplacement.replaceFromFile(provider, ref, working, recoveryRoot, revision)) {
            is ProviderResult.Success -> {
                revision = working.inputStream().use(ContentRevision::read)
                dirty = false
            }
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> throw IOException("This location cannot save the updated image")
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { device.close() } finally { working.delete() }
    }

    companion object {
        fun open(provider: StorageProvider, ref: ResourceRef, cacheRoot: File, recoveryRoot: File): StagedRandomWriteDevice {
            if (!cacheRoot.isDirectory && !cacheRoot.mkdirs()) throw IOException("Cannot create image working directory")
            val working = File.createTempFile("image-", ".working", cacheRoot)
            try {
                DiagnosticLog.record("image.stage", "provider=${ref.providerId} preparing local working copy")
                when (val result = provider.openRead(ref)) {
                    is ProviderResult.Success -> result.value.use { input ->
                        working.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                    }
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> throw IOException("Cannot read the original image for safe editing")
                }
                val revision = working.inputStream().use(ContentRevision::read)
                return StagedRandomWriteDevice(provider, ref, working,
                    FileBlockDevice(RandomAccessFile(working, "rw"), ownsFile = true), revision, recoveryRoot)
            } catch (e: Exception) { working.delete(); throw e }
        }
    }
}
