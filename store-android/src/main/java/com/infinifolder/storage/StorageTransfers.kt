package com.infinifolder.storage

import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.ops.FileOperations

/** The browser and direct provider APIs use one verified transfer implementation. */
object StorageTransfers {
    fun transfer(provider: StorageProvider, source: ResourceRef, destination: ResourceRef, move: Boolean,
                 confirmRandomAccessFallback: ((RandomAccessFallbackRequest) -> Boolean)? = null): ProviderResult<ResourceRef> = try {
        val sourceEntry = when (val result = provider.stat(source)) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Failure -> throw java.io.IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> throw java.io.IOException("Source metadata is unavailable")
        }
        val copied = FileOperations.batchTransfer(listOf(sourceEntry), Uri.parse(destination.documentId), move,
            provider, { destination }, {}, { false },
            confirmRandomAccessFallback = confirmRandomAccessFallback).single()
        ProviderResult.Success(copied)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Transfer failed; original retained where possible", e) }
}
