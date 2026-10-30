package com.infinifolder.storage

import com.infinifolder.model.*

data class StorageRelocation(val source: ResourceRef, val name: String)

/** Private mutation view. Only commit publishes; close without commit discards all staged changes. */
interface StorageWriteBatch : java.io.Closeable {
    val provider: StorageProvider
    /** Native relocation keeps payload allocations; destination metadata can still need space. */
    val supportsRelocation: Boolean get() = false
    /** No overwrite. Unsupported must leave the private view unchanged. Failures are
     * discarded by closing without commit; callers must never publish a failed batch.
     */
    fun relocate(entries: List<StorageRelocation>, destination: ResourceRef): ProviderResult<List<ResourceEntry>> =
        ProviderResult.Unsupported(ResourceCapability.MOVE)
    fun commit()
}
