package com.infinifolder.storage

import com.infinifolder.model.ProviderResult

/** Missing, incomplete and failed provider responses must never become a confirmed empty folder. */
internal fun <T> directoryQueryResult(
    rows: List<T>?, loading: Boolean = false, error: String? = null, info: String? = null
): ProviderResult<List<T>> {
    if (rows == null) return ProviderResult.Failure("The storage provider did not return a folder listing. Try Refresh.")
    val failure = error?.takeIf { it.isNotBlank() }
    if (failure != null && rows.isEmpty()) return ProviderResult.Failure("Could not read this folder: $failure")
    return ProviderResult.Success(rows, loading, failure ?: info?.takeIf { it.isNotBlank() })
}
