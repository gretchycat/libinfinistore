package com.infinifolder.model

enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class CreationGuarantee { EXCLUSIVE, SERVER_UNIQUE_NAME, UNKNOWN, UNSUPPORTED }
data class BackendFeatures(
    val randomRead: Support = Support.UNKNOWN,
    val randomWrite: Support = Support.UNKNOWN,
    val atomicReplacement: Support = Support.UNSUPPORTED,
    val creation: CreationGuarantee = CreationGuarantee.UNKNOWN,
    val rename: Support = Support.UNKNOWN,
    val delete: Support = Support.UNKNOWN,
    val nativeLinks: Support = Support.UNKNOWN,
    val readOnly: Boolean = false,
    /** New children can provide readable, writable, seekable, resizable transfer handles. */
    val transferTarget: Support = Support.UNKNOWN
)
data class ResourceCapabilities(val operations: Map<ResourceCapability, Support>, val creation: CreationGuarantee) {
    operator fun get(operation: ResourceCapability) = operations[operation] ?: Support.UNKNOWN
    fun mayAttempt(operation: ResourceCapability) = get(operation) != Support.UNSUPPORTED
    companion object {
        /** Resource hints refine backend defaults; invariant restrictions are applied last. */
        fun from(metadata: ResourceMetadata, features: BackendFeatures,
                 overrides: Map<ResourceCapability, Support> = emptyMap(),
                 creation: CreationGuarantee = features.creation): ResourceCapabilities {
            fun yes(value: Boolean) = if (value) Support.SUPPORTED else Support.UNSUPPORTED
            val writable = metadata.canWrite && !features.readOnly
            val readable = metadata.canRead && !metadata.isBrokenLink && metadata.readError == null
            val base = mutableMapOf(
                ResourceCapability.STAT to Support.SUPPORTED,
                ResourceCapability.ACCEPT_INTERNAL_MOVE to Support.UNSUPPORTED,
                ResourceCapability.READ to yes(readable && !metadata.isDirectory),
                ResourceCapability.LIST to yes(readable && metadata.isDirectory),
                ResourceCapability.WRITE to yes(writable && !metadata.isDirectory),
                ResourceCapability.COPY to yes(readable),
                ResourceCapability.MOVE to if (metadata.isSymbolicLink || features.readOnly) Support.UNSUPPORTED else features.delete,
                ResourceCapability.CREATE to if (!metadata.isDirectory || !writable || creation == CreationGuarantee.UNSUPPORTED) Support.UNSUPPORTED
                    else if (creation == CreationGuarantee.UNKNOWN) Support.UNKNOWN else Support.SUPPORTED,
                ResourceCapability.RENAME to if (features.readOnly) Support.UNSUPPORTED else features.rename,
                ResourceCapability.DELETE to if (features.readOnly) Support.UNSUPPORTED else features.delete,
                ResourceCapability.SEEK to if (metadata.isDirectory || !readable) Support.UNSUPPORTED else features.randomRead,
                ResourceCapability.SEEK_WRITE to if (metadata.isDirectory || !writable) Support.UNSUPPORTED else features.randomWrite,
                ResourceCapability.ATOMIC_REPLACE to if (metadata.isDirectory || !writable) Support.UNSUPPORTED else features.atomicReplacement,
                ResourceCapability.TRANSFER_TARGET to if (features.randomWrite == Support.UNSUPPORTED) Support.UNSUPPORTED else features.transferTarget,
                ResourceCapability.RESOLVE_LINK to if (metadata.isSymbolicLink) features.nativeLinks else Support.UNSUPPORTED
            )
            base.putAll(metadata.operationHints)
            base.putAll(overrides)
            fun deny(vararg operations: ResourceCapability) = operations.forEach { base[it] = Support.UNSUPPORTED }
            if (!readable) deny(ResourceCapability.READ, ResourceCapability.LIST, ResourceCapability.COPY, ResourceCapability.SEEK)
            if (metadata.isDirectory) deny(ResourceCapability.READ, ResourceCapability.WRITE, ResourceCapability.SEEK,
                ResourceCapability.SEEK_WRITE, ResourceCapability.ATOMIC_REPLACE)
            else deny(ResourceCapability.LIST, ResourceCapability.CREATE, ResourceCapability.BATCH_WRITE, ResourceCapability.ACCEPT_MOVE, ResourceCapability.ACCEPT_INTERNAL_MOVE, ResourceCapability.TRANSFER_TARGET)
            if (!writable) deny(ResourceCapability.WRITE, ResourceCapability.SEEK_WRITE, ResourceCapability.ATOMIC_REPLACE,
                ResourceCapability.CREATE, ResourceCapability.BATCH_WRITE, ResourceCapability.ACCEPT_MOVE, ResourceCapability.ACCEPT_INTERNAL_MOVE, ResourceCapability.TRANSFER_TARGET)
            if (features.readOnly) deny(ResourceCapability.DELETE, ResourceCapability.RENAME, ResourceCapability.MOVE)
            // Generic moves copy first and delete only after verification. Parent permissions
            // determine deletion: a read-only file can still be movable in a writable folder.
            val moveRequirements = listOf(base[ResourceCapability.MOVE], base[ResourceCapability.COPY], base[ResourceCapability.DELETE])
            base[ResourceCapability.MOVE] = when {
                metadata.isSymbolicLink || Support.UNSUPPORTED in moveRequirements -> Support.UNSUPPORTED
                moveRequirements.all { it == Support.SUPPORTED } -> Support.SUPPORTED
                else -> Support.UNKNOWN
            }
            if (creation == CreationGuarantee.UNSUPPORTED) deny(ResourceCapability.CREATE)
            if (base[ResourceCapability.CREATE] == Support.UNSUPPORTED) deny(ResourceCapability.BATCH_WRITE, ResourceCapability.ACCEPT_MOVE, ResourceCapability.ACCEPT_INTERNAL_MOVE, ResourceCapability.TRANSFER_TARGET)
            val effectiveCreation = if (base[ResourceCapability.CREATE] == Support.UNSUPPORTED) CreationGuarantee.UNSUPPORTED else creation
            return ResourceCapabilities(base.toMap(), effectiveCreation)
        }
    }
}
