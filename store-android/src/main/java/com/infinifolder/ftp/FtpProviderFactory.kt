package com.infinifolder.ftp

import android.content.ContentResolver
import android.content.Context
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageProviderFactory

class FtpProviderFactory(
    private val context: Context,
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProviderFactory {

    override val sourceTypeId = "ftp"

    val provider: FtpStorageProvider by lazy {
        FtpStorageProvider(context = context, connectionLookup = connectionLookup)
    }

    override fun create(resolver: ContentResolver, connection: ProviderConnection?): StorageProvider {
        return provider
    }
}
