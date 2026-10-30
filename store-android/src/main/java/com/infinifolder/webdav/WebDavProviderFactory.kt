package com.infinifolder.webdav

import android.content.ContentResolver
import android.content.Context
import com.infinifolder.storage.*

class WebDavProviderFactory(private val context: Context, private val lookup: (String)->ProviderConnection?) : StorageProviderFactory {
    override val sourceTypeId = "webdav"
    val provider: WebDavStorageProvider by lazy { WebDavStorageProvider(context, connectionLookup=lookup) }
    override fun create(resolver: ContentResolver, connection: ProviderConnection?): StorageProvider = provider
}
