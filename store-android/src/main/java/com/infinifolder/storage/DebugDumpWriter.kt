package com.infinifolder.storage

import com.infinifolder.model.*
import java.io.IOException

data class DebugArtifact(val name: String, val mime: String, val bytes: ByteArray)

/** Backend-neutral debug export. Returned entries retain any server-assigned names. */
object DebugDumpWriter {
    fun write(provider: StorageProvider, directory: ResourceRef, artifacts: List<DebugArtifact>): ProviderResult<List<ResourceEntry>> {
        val written = mutableListOf<ResourceEntry>()
        return try {
            for (artifact in artifacts) {
                StorageSafety.validateName(artifact.name)
                val entry = when (val created = provider.createFile(directory, artifact.name, artifact.mime)) {
                    is ProviderResult.Success -> created.value
                    is ProviderResult.Failure -> throw IOException(created.message, created.cause)
                    is ProviderResult.Unsupported -> throw IOException("Debug dump folder cannot create files")
                }
                try {
                    when (val opened = provider.openWrite(entry.ref)) {
                        is ProviderResult.Success -> opened.value.use { it.write(artifact.bytes); it.flush() }
                        is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
                        is ProviderResult.Unsupported -> throw IOException("Debug dump file is not writable")
                    }
                    written += entry
                } catch (e: Exception) {
                    provider.delete(entry.ref) // Only the newly-created partial artifact belongs to this operation.
                    throw e
                }
            }
            ProviderResult.Success(written)
        } catch (e: Exception) { ProviderResult.Failure("Debug dump failed after ${written.size} files: ${e.message}", e) }
    }
}
