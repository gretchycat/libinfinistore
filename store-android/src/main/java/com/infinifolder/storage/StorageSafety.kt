package com.infinifolder.storage

import com.infinifolder.model.*
import java.io.IOException

/** Shared guards used before traversal or creation on every backend. */
object StorageSafety {
    fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && '\u0000' !in name) { "Invalid file name" }
    }

    fun <T> complete(result: ProviderResult<List<T>>): List<T> = when (result) {
        is ProviderResult.Success -> {
            if (result.isLoading || !result.notice.isNullOrBlank()) {
                throw IOException(result.notice ?: "Folder is still loading; retry when complete")
            }
            result.value
        }
        is ProviderResult.Failure -> throw IOException(result.message, result.cause)
        is ProviderResult.Unsupported -> throw IOException("Folder listing is unsupported")
    }

    fun requireNewName(provider: StorageProvider, parent: ResourceRef, name: String) {
        validateName(name)
        if (complete(provider.list(parent)).any { it.metadata.name.equals(name, ignoreCase = true) }) {
            throw java.nio.file.FileAlreadyExistsException(name)
        }
    }
}
