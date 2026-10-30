package com.infinifolder.storage

import android.content.ContentResolver
import java.util.UUID

/** Non-secret connection metadata. Credentials belong in an Android Keystore backed vault. */
data class ProviderConnection(
    val id: String = UUID.randomUUID().toString(),
    val sourceTypeId: String,
    val displayName: String,
    val endpoint: String? = null,
    val options: Map<String, String> = emptyMap(),
    val credentialHandle: String? = null
)

data class SourceTypeDescriptor(
    val id: String,
    val label: String,
    val icon: String,
    val description: String,
    val implemented: Boolean
)

interface StorageProviderFactory {
    val sourceTypeId: String
    fun create(resolver: ContentResolver, connection: ProviderConnection? = null): StorageProvider
}

/** Persistence hook for future saved network/cloud connections; implementations must not store passwords here. */
interface ProviderConnectionRepository {
    fun list(): List<ProviderConnection>
    fun save(connection: ProviderConnection)
    fun remove(connectionId: String)
}

/** Registration point for built-in and future local, network, and cloud providers. */
class StorageProviderRegistry {
    private val factories = linkedMapOf<String, StorageProviderFactory>()

    fun register(factory: StorageProviderFactory) {
        require(factory.sourceTypeId !in factories) { "Provider already registered: ${factory.sourceTypeId}" }
        factories[factory.sourceTypeId] = factory
    }

    fun create(sourceTypeId: String, resolver: ContentResolver, connection: ProviderConnection? = null): StorageProvider? {
        require(connection == null || connection.sourceTypeId == sourceTypeId) { "Connection type does not match provider type" }
        return factories[sourceTypeId]?.create(resolver, connection)
    }

    fun sourceTypes(): List<SourceTypeDescriptor> = sourceCatalog.map { descriptor ->
        descriptor.copy(implemented = descriptor.id in factories)
    }

    private val sourceCatalog = listOf(
        SourceTypeDescriptor("saf", "Android storage / SD card", "📁", "User-authorized Android document trees", false),
        SourceTypeDescriptor("smb", "SMB / Samba", "🖧", "Network shares", false),
        SourceTypeDescriptor("sftp", "SFTP / SSH", "🔐", "Secure remote file servers", false),
        SourceTypeDescriptor("ftp", "FTP", "🌐", "FTP servers", false),
        SourceTypeDescriptor("nfs", "NFS", "🗄", "Network file systems", false),
        SourceTypeDescriptor("webdav", "WebDAV", "🌐", "Web-based file servers", false),
        SourceTypeDescriptor("cloud", "Cloud storage", "☁️", "Cloud and web storage providers", false)
    )
}

class SafProviderFactory : StorageProviderFactory {
    override val sourceTypeId = "saf"
    override fun create(resolver: ContentResolver, connection: ProviderConnection?): StorageProvider = SafProvider(resolver)
}
