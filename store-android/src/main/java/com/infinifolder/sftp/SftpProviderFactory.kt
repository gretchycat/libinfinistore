package com.infinifolder.sftp

import android.content.ContentResolver
import android.content.Context
import com.infinifolder.storage.ProviderConnection
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageProviderFactory

class SftpProviderFactory(
    private val context: Context,
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProviderFactory {

    override val sourceTypeId = "sftp"

    val provider: SftpStorageProvider by lazy {
        SftpStorageProvider(context, connectionLookup = connectionLookup)
    }

    override fun create(resolver: ContentResolver, connection: ProviderConnection?): StorageProvider {
        return provider
    }
}
