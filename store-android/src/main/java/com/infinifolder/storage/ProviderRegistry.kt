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
    val descriptor: SourceTypeDescriptor get() = SourceTypeDescriptor(sourceTypeId,sourceTypeId,"network-server","Registered storage provider",true)
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

    @Synchronized fun register(factory: StorageProviderFactory) {
        require(factory.sourceTypeId !in factories) { "Provider already registered: ${factory.sourceTypeId}" }
        factories[factory.sourceTypeId] = factory
    }

    @Synchronized fun create(sourceTypeId: String, resolver: ContentResolver, connection: ProviderConnection? = null): StorageProvider? {
        require(connection == null || connection.sourceTypeId == sourceTypeId) { "Connection type does not match provider type" }
        return factories[sourceTypeId]?.create(resolver, connection)
    }

    @Synchronized fun sourceTypes(): List<SourceTypeDescriptor> = factories.values.map { it.descriptor.copy(implemented=true) }
    @Synchronized fun installDiscovered(context:android.content.Context,lookup:(String)->ProviderConnection?) {
        StoreCapabilities.protocols().forEach { handler ->
            if(handler.sourceType.id !in factories)register(handler.factory(context,lookup))
        }
    }
    fun rootUri(connection:ProviderConnection):android.net.Uri? = StoreCapabilities.protocols()
        .firstOrNull { it.sourceType.id==connection.sourceTypeId }?.rootUri(connection)
}

class SafProviderFactory : StorageProviderFactory {
    override val sourceTypeId = "saf"
    override val descriptor=SourceTypeDescriptor("saf","Android storage / SD card","📁","User-authorized Android document trees",true)
    override fun create(resolver: ContentResolver, connection: ProviderConnection?): StorageProvider = SafProvider(resolver)
}
