package com.infinifolder.storage

import com.infinifolder.model.*
import java.io.IOException
import java.util.Properties

internal class InvalidTransferCheckpointException(message: String) : IOException(message)
/** Failure before any checkpoint payload bytes were submitted to storage. */
internal class CheckpointWriteRejectedException(message: String, cause: Exception) : IOException(message, cause)

/** Small companion state, separate from the data file and ordinary file caches. */
interface TransferCheckpointStore : java.io.Closeable {
    override fun close() {}
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun fingerprint(): String?
    fun delete()
}

internal class SiblingTransferCheckpointStore(
    private val provider: StorageProvider,
    private val destination: ResourceRef
) : TransferCheckpointStore {
    private var companion: ResourceRef? = null
    private var located = false
    private var checkpointDevice: com.infinifolder.archive.BlockDevice? = null
    private var seekableAttempted = false
    override fun close() { checkpointDevice?.close(); checkpointDevice = null }

    private fun seekable(ref: ResourceRef): com.infinifolder.archive.BlockDevice? {
        if (!seekableAttempted) {
            seekableAttempted = true
            val device = (provider.openSeekable(ref, writable = true) as? ProviderResult.Success)?.value
            if (device != null && !device.isReadOnly &&
                device.capabilities.containsAll(setOf(com.infinifolder.archive.DeviceCapability.READ,
                    com.infinifolder.archive.DeviceCapability.WRITE, com.infinifolder.archive.DeviceCapability.TRUNCATE)))
                checkpointDevice = device
            else device?.close()
        }
        return checkpointDevice
    }
    private fun locate(create: Boolean): ResourceRef? {
        if (companion != null) return companion
        val entry = when (val result = provider.stat(destination)) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw IOException("Cannot inspect torrent destination", result.cause)
            else -> return null
        }
        val parent = when (val result = provider.parent(destination)) {
            is ProviderResult.Success -> result.value ?: return null
            is ProviderResult.Failure -> throw IOException("Cannot inspect torrent destination parent", result.cause)
            else -> return null
        }
        if (!located) {
            val listing = when (val result = provider.list(parent)) {
                is ProviderResult.Success -> result.value
                is ProviderResult.Failure -> throw IOException("Cannot find torrent checkpoint", result.cause)
                else -> return null
            }
            companion = listing.firstOrNull { it.metadata.name == entry.metadata.name + ".cache" }?.ref
            located = true
        }
        if (companion == null && create) {
            companion = (provider.createFile(parent, entry.metadata.name + ".cache", "application/vnd.infinifolder.torrent-checkpoint") as? ProviderResult.Success)?.value?.ref
        }
        return companion
    }
    override fun read(): ByteArray? {
        val ref = locate(false) ?: return null
        checkpointDevice?.let { device ->
            if (device.size > 4 * 1024 * 1024) throw InvalidTransferCheckpointException("Torrent checkpoint is too large")
            val data = ByteArray(device.size.toInt())
            var offset = 0
            while (offset < data.size) {
                val count = device.readAt(offset.toLong(), data, offset, data.size - offset)
                if (count <= 0) throw IOException("Unexpected end of torrent checkpoint")
                offset += count
            }
            return data
        }
        val input = when (val result = provider.openRead(ref)) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw IOException("Cannot read torrent checkpoint: ${result.message}", result.cause)
            is ProviderResult.Unsupported -> throw IOException("Reading torrent checkpoint is unsupported")
        }
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > 4 * 1024 * 1024) throw InvalidTransferCheckpointException("Torrent checkpoint is too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    override fun write(bytes: ByteArray) {
        val ref = try {
            // Recheck ownership on every publication, even when using a retained device.
            read()?.let { old ->
                val properties = Properties().apply { old.inputStream().use(::load) }
                if (properties.getProperty("format") != "infinifolder-torrent-checkpoint-1" ||
                    properties.getProperty("destination") != "${destination.providerId}:${destination.documentId}")
                    throw IOException("An unrelated torrent cache file already exists")
            }
            locate(true) ?: throw IOException("Cannot create torrent checkpoint")
        } catch (error: Exception) {
            throw CheckpointWriteRejectedException("Cannot prepare torrent checkpoint: ${error.message}", error)
        }
        val device = seekable(ref)
        if (device != null) {
            // Properties accepts trailing whitespace. Fixed allocation avoids remote
            // truncation for every added bitmap bit, while persisting every piece.
            val capacity = ((maxOf(device.size, bytes.size.toLong()) + 4095) / 4096 * 4096)
            if (capacity > 4 * 1024 * 1024) throw InvalidTransferCheckpointException("Torrent checkpoint is too large")
            val padded = ByteArray(capacity.toInt()) { 32 }.also { bytes.copyInto(it) }
            if (device.size != capacity) device.truncate(capacity)
            device.writeAt(0, padded, 0, padded.size)
            device.flush()
        } else {
            val output = try {
                when (val result = provider.openWrite(ref)) {
                    is ProviderResult.Success -> result.value
                    is ProviderResult.Failure -> throw IOException("Cannot write torrent checkpoint: ${result.message}", result.cause)
                    is ProviderResult.Unsupported -> throw IOException("Writing torrent checkpoint is unsupported")
                }
            } catch (error: Exception) {
                throw CheckpointWriteRejectedException("Cannot prepare torrent checkpoint: ${error.message}", error)
            }
            output.use { it.write(bytes); it.flush() }
        }
    }
    override fun fingerprint(): String? {
        val metadata = when (val result = provider.stat(destination)) {
            is ProviderResult.Success -> result.value.metadata
            is ProviderResult.Failure -> throw IOException("Cannot inspect checkpoint destination metadata: ${result.message}", result.cause)
            is ProviderResult.Unsupported -> return null
        }
        val size = metadata.sizeBytes ?: return null
        val modified = metadata.modifiedAtMillis?.takeIf { it > 0 } ?: return null
        return "${destination.providerId}:${destination.documentId}:$size:$modified"
    }
    override fun delete() {
        val ref = locate(false) ?: return
        val data = read() ?: return
        val properties = Properties().apply { data.inputStream().use(::load) }
        if (properties.getProperty("format") == "infinifolder-torrent-checkpoint-1" &&
            properties.getProperty("destination") == "${destination.providerId}:${destination.documentId}") {
            if (provider.delete(ref) !is ProviderResult.Success) throw IOException("Cannot remove torrent checkpoint")
            companion = null
        }
    }
}

/** Host routing uses the contract, without exposing the companion implementation. */
fun siblingTransferCheckpointStore(provider: StorageProvider, destination: ResourceRef): TransferCheckpointStore =
    SiblingTransferCheckpointStore(provider, destination)
