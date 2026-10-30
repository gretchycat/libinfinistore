package com.infinifolder.storage

import android.content.Context
import com.infinifolder.model.ResourceRef

object DebugDumpDestination {
    fun get(context: Context): ResourceRef? {
        val settings = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val provider = settings.getString("dump_provider", null) ?: return null
        val directory = settings.getString("dump_directory", null) ?: return null
        return ResourceRef(provider, directory)
    }
    fun set(context: Context, directory: ResourceRef) {
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().remove("output_uri")
            .putString("dump_provider", directory.providerId).putString("dump_directory", directory.documentId).apply()
    }
}
