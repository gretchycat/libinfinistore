package com.infinifolder.storage

import com.infinifolder.model.*
import com.infinifolder.archive.*
import io.github.gretchycat.infinistream.*
import java.io.IOException

/** Adapts demonstrated handles; no stat call, full download or silent writable fallback. */
object ProviderStreams {
    fun openRead(provider: StorageProvider, ref: ResourceRef, metadata: ResourceMetadata? = null, sourceIdentity: String? = null): ByteSource {
        val input = when (val result = provider.openRead(ref)) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> throw IOException("Reading this resource is unsupported")
        }
        val info = snapshot(ref, metadata)
        return InputStreamSource(input, info.copy(capabilities = setOf(Capability.READ),
            provenance = info.provenance.copy(source = sourceIdentity ?: info.provenance.source)))
    }
    fun openPositional(provider: StorageProvider, ref: ResourceRef, metadata: ResourceMetadata? = null): ByteSource {
        val result = provider.openSeekable(ref, false)
        val device = when (result) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> throw IOException("Positional reading is unsupported; explicit staging is needed")
        }
        try {
            if (DeviceCapability.READ !in device.capabilities || DeviceCapability.SEEK !in device.capabilities)
                throw IOException("Provider returned an unsuitable positional handle")
            return BlockDeviceSource(device, snapshot(ref, metadata))
        } catch (error: Throwable) { try { device.close() } catch (close: Throwable) { error.addSuppressed(close) }; throw error }
    }
    fun resource(provider: StorageProvider, ref: ResourceRef, resourceMetadata: ResourceMetadata? = null,
        positional: Boolean = false): SourceResource = object : SourceResource {
        override val metadata = snapshot(ref, resourceMetadata)
        override fun open(): ByteSource = if (positional) openPositional(provider, ref, resourceMetadata) else openRead(provider, ref, resourceMetadata)
    }
    private fun snapshot(ref: ResourceRef, metadata: ResourceMetadata?): StreamMetadata = StreamMetadata(
        content = ContentType(kind = if (metadata?.isDirectory == true) DataKind.DIRECTORY else DataKind.BYTES, mimeType = metadata?.mimeType), name = metadata?.name, length = metadata?.sizeBytes,
        finiteness = Finiteness.UNKNOWN, warnings = listOfNotNull(metadata?.readError, metadata?.recoveryNote),
        provenance = Provenance(source = "${ref.providerId}:${ref.documentId}"))
}

/** Translates legacy EOF=0 to Stream EOF=-1; cursor and ownership remain local to this wrapper. */
class BlockDeviceSource(private val device: BlockDevice, metadata: StreamMetadata = StreamMetadata(),
    private val ownsDevice: Boolean = true) : ByteSource, PositionalReadable {
    override val metadata = metadata.copy(length = device.size, capabilities = setOf(
        Capability.READ, Capability.POSITIONAL_READ, Capability.SEEK, Capability.KNOWN_LENGTH),
        ownership = if (ownsDevice) Ownership.OWNED else Ownership.BORROWED, concurrency = Concurrency.SERIAL)
    private var position = 0L
    private var closed = false
    @Synchronized override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = readAt(position, buffer, offset, length); if (n > 0) position += n; return n
    }
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Source is closed")
        requireRange(position, buffer, offset, length); RecoveryCancellation.check()
        if (length == 0) return 0
        val n = device.readAt(position, buffer, offset, length)
        return if (n == 0) -1 else n
    }
    @Synchronized override fun close() { if (!closed) { closed = true; if (ownsDevice) device.close() } }
}

/** Read-only legacy parser view over a common source; no buffering and no shared cursor mutation. */
class StreamBlockDevice(private val source: ByteSource, private val ownsSource: Boolean = true) : BlockDevice {
    private val positional = source as? PositionalReadable ?: throw IOException("Positional source required")
    override val size = source.metadata.length ?: throw IOException("Known source length required")
    override val isReadOnly = true
    override val capabilities = setOf(DeviceCapability.READ, DeviceCapability.SEEK)
    private var closed = false
    override fun readAt(offset: Long, buffer: ByteArray, dstOffset: Int, length: Int): Int {
        if (closed) throw IOException("Device is closed")
        requireRange(offset, buffer, dstOffset, length)
        val n = positional.readAt(offset, buffer, dstOffset, length)
        return if (n < 0) 0 else n
    }
    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) { throw IOException("Stream parser view is read-only") }
    override fun close() { if (!closed) { closed = true; if (ownsSource) source.close() } }
}
