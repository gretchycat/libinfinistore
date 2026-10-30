package com.infinifolder.smb

import android.content.ContentResolver
import android.content.Context
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageProviderFactory

class SmbProviderFactory(
    private val context: Context,
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProviderFactory {

    override val sourceTypeId = "smb"

    val provider: SmbStorageProvider by lazy {
        SmbStorageProvider(context = context, connectionLookup = connectionLookup)
    }

    override fun create(resolver: ContentResolver, connection: ProviderConnection?): StorageProvider {
        return provider
    }
}
