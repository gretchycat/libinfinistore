package com.infinifolder.model

import android.net.Uri

/** Provider-neutral identity. documentId is opaque and must never be treated as a path. */
data class ResourceRef(val providerId: String, val documentId: String)

data class ResourceMetadata(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val isDirectory: Boolean,
    val isHidden: Boolean = name.startsWith("."),
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    val canExecute: Boolean = false,
    val permissionsMode: Int? = null,
    val permissionsFormatted: String? = null,
    val owner: String? = null,
    val group: String? = null,
    val isSymbolicLink: Boolean = false,
    val linkTarget: String? = null,
    val isBrokenLink: Boolean = false,
    val operationHints: Map<ResourceCapability, Support> = emptyMap(),
    val readError: String? = null,
    val recoveryNote: String? = null,
    val partialRecoveryAvailable: Boolean = false
)

data class ResourceEntry(val ref: ResourceRef, val metadata: ResourceMetadata, val uri: Uri)

enum class ResourceCapability { METADATA, BATCH_WRITE, ACCEPT_MOVE, ACCEPT_INTERNAL_MOVE, TRANSFER_TARGET, FILESYSTEM_INFO, STAT, PARENT, RESOLVE_LINK, ATOMIC_REPLACE, SEEK_WRITE, WRITE, SEEK, LIST, READ, CREATE, RENAME, COPY, MOVE, DELETE, OBSERVE }

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T, val isLoading: Boolean = false, val notice: String? = null) : ProviderResult<T>
    data class Unsupported(val capability: ResourceCapability) : ProviderResult<Nothing>
    data class Failure(val message: String, val cause: Throwable? = null) : ProviderResult<Nothing>
}
