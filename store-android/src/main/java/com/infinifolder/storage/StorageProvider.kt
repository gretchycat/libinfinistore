package com.infinifolder.storage

import android.net.Uri
import com.infinifolder.model.*

interface StorageProvider : java.io.Closeable {
    override fun close() {}
    val id: String
    val features: BackendFeatures get() = BackendFeatures()
    fun capabilities(ref: ResourceRef): ProviderResult<ResourceCapabilities> = when (val result = stat(ref)) {
        is ProviderResult.Success -> ProviderResult.Success(ResourceCapabilities.from(result.value.metadata, features))
        is ProviderResult.Failure -> result
        is ProviderResult.Unsupported -> result
    }
    /** Optional isolated mutation batch rooted at a container folder; supports transfers and deletion. */
    fun beginWriteBatch(destination: ResourceRef, isCancelled: () -> Boolean = { false }): ProviderResult<StorageWriteBatch> =
        ProviderResult.Unsupported(ResourceCapability.BATCH_WRITE)
    /** Physical filesystem and its confirmed limits, when this backend can identify them. */
    fun fileSystemInfo(ref: ResourceRef): ProviderResult<FileSystemInfo> = ProviderResult.Unsupported(ResourceCapability.FILESYSTEM_INFO)
    fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = ProviderResult.Unsupported(ResourceCapability.PARENT)
    fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = ProviderResult.Unsupported(ResourceCapability.RESOLVE_LINK)


    /** URI ownership belongs to the backend; document IDs remain opaque to callers. */
    fun refForUri(uri: Uri): ResourceRef? = null
    /** Container dependency, used to reject copying a backing file into its own mounted contents. */
    fun backingResource(ref: ResourceRef): ResourceRef? = null
    /** Image whose deleted bytes back this resource, including containers opened inside recovered files. */
    fun recoverySource(ref: ResourceRef): ResourceRef? = null
    /** Stable transfer identity. Unknown identities must be treated as different devices. */
    fun deviceIdentity(ref: ResourceRef): String? = null
    fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.STAT)
    /** Export portable attributes; a backend may supply richer ACL/xattr data. */
    fun readTransferMetadata(ref: ResourceRef): ProviderResult<TransferMetadata> = when (val result = stat(ref)) {
        is ProviderResult.Success -> ProviderResult.Success(TransferMetadata.from(result.value.metadata))
        is ProviderResult.Failure -> result
        is ProviderResult.Unsupported -> result
    }
    /** Retain all requested fields. Unsupported must make no changes. */
    fun applyTransferMetadata(ref: ResourceRef, metadata: TransferMetadata): ProviderResult<Unit> =
        ProviderResult.Unsupported(ResourceCapability.METADATA)

    /** Caller owns the device. Success requires READ/SEEK, plus WRITE when requested.
     * Writable access must never silently downgrade. Unsupported uses SEEK/SEEK_WRITE;
     * permission, connection and I/O failures are Failure, not a fallback signal. */
    fun openSeekable(ref: ResourceRef, writable: Boolean = false): ProviderResult<com.infinifolder.archive.BlockDevice> =
        ProviderResult.Unsupported(if (writable) ResourceCapability.SEEK_WRITE else ResourceCapability.SEEK)
    /** Target must also support TRUNCATE (shrink and grow) for reservation/resume. */
    fun openTransferTarget(ref: ResourceRef): ProviderResult<com.infinifolder.archive.BlockDevice> =
        validateSeekableResult(openSeekable(ref, writable = true), writable = true, resizable = true)
    /** Optional atomic replacement. Unsupported callers must preserve a recovery copy before streaming. */
    fun replaceContent(ref: ResourceRef, data: ByteArray): ProviderResult<Unit> = ProviderResult.Unsupported(ResourceCapability.WRITE)

    /** File-backed publication avoids loading a whole container into memory. */
    fun replaceContentFromFile(ref: ResourceRef, file: java.io.File): ProviderResult<Unit> = ProviderResult.Unsupported(ResourceCapability.WRITE)

    fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>>
    /** Explicit partial recovery, including a byte-range report; ordinary reads never fill holes silently. */
    fun openPartialRecovery(ref: ResourceRef): ProviderResult<RecoveryExport> = ProviderResult.Unsupported(ResourceCapability.READ)
    fun openRead(ref: ResourceRef): ProviderResult<java.io.InputStream>
    fun openWrite(ref: ResourceRef): ProviderResult<java.io.OutputStream>
    fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry>
    /** Create a new entry; never truncate or return an existing file. */
    fun createFile(parent: ResourceRef, name: String, mimeType: String = "application/octet-stream"): ProviderResult<ResourceEntry>
    fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry>
    fun delete(ref: ResourceRef): ProviderResult<Unit>
    fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef>
    fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef>
}

/** Optional direct-I/O contract; adding it does not change StorageProvider's JVM interface.
 * Providers with staged seekable views must return Unsupported instead of staging. */
interface NativeRandomAccessProvider {
    fun openNativeSeekable(ref: ResourceRef, writable: Boolean = false): ProviderResult<com.infinifolder.archive.BlockDevice>
}

fun StorageProvider.openNativeSeekable(ref: ResourceRef, writable: Boolean = false): ProviderResult<com.infinifolder.archive.BlockDevice> {
    val result = if (this is NativeRandomAccessProvider) this.openNativeSeekable(ref, writable) else openSeekable(ref, writable)
    if (result is ProviderResult.Success && result.value is StagedBlockCommit) {
        return try {
            result.value.close()
            ProviderResult.Unsupported(if (writable) ResourceCapability.SEEK_WRITE else ResourceCapability.SEEK)
        } catch (error: Exception) { ProviderResult.Failure("Cannot close a staged random-access fallback", error) }
    }
    return result
}

/** One validation rule for direct backend callers and routed operations. */
fun validateSeekableResult(result: ProviderResult<com.infinifolder.archive.BlockDevice>,
                                    writable: Boolean, resizable: Boolean = false): ProviderResult<com.infinifolder.archive.BlockDevice> {
    val unsupported = ProviderResult.Unsupported(if (writable) ResourceCapability.SEEK_WRITE else ResourceCapability.SEEK)
    if (result is ProviderResult.Unsupported) return unsupported
    if (result !is ProviderResult.Success) return result
    val device = result.value
    val required = setOf(com.infinifolder.archive.DeviceCapability.READ, com.infinifolder.archive.DeviceCapability.SEEK) +
        (if (writable) setOf(com.infinifolder.archive.DeviceCapability.WRITE) else emptySet()) +
        (if (resizable) setOf(com.infinifolder.archive.DeviceCapability.TRUNCATE) else emptySet())
    if (device.capabilities.containsAll(required) && (!writable || !device.isReadOnly)) return result
    return try { device.close(); unsupported }
    catch (error: Exception) { ProviderResult.Failure("Cannot close an unsuitable random-access handle", error) }
}

/** SAF tree grants are persisted by Android; URI/document IDs remain opaque provider identities. */
data class SafRoot(val treeUri: Uri, val displayName: String)
