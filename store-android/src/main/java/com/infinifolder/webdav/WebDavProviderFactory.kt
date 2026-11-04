package com.infinifolder.webdav

import android.content.ContentResolver
import android.content.Context
import com.infinifolder.storage.*

class WebDavProviderFactory(private val context: Context, private val lookup: (String)->ProviderConnection?) : StorageProviderFactory {
    override val sourceTypeId = "webdav"
    override val descriptor get() = sourceType
    companion object { val sourceType = com.infinifolder.storage.SourceTypeDescriptor("webdav","WebDAV","🌐","Web-based file servers",true) }
    val provider: WebDavStorageProvider by lazy { WebDavStorageProvider(context, connectionLookup=lookup) }
    override fun create(resolver: ContentResolver, connection: ProviderConnection?): StorageProvider = provider
}
