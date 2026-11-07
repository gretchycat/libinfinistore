package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import com.infinifolder.mime.MimeRegistry
import io.github.gretchycat.infinistream.*

/** A protocol module owns its URI rules and factory. Discovery does not connect. */
data class ConnectionField(val key:String,val label:String,val secret:Boolean=false,val required:Boolean=false,val initial:String="")
interface StorageCapabilityHandler : CapabilityHandler {
    val sourceType: SourceTypeDescriptor
    val userConfigurable:Boolean get()=true
    fun factory(context: Context, connectionLookup: (String) -> ProviderConnection?): StorageProviderFactory
    fun rootUri(connection: ProviderConnection): Uri = Uri.Builder().scheme(sourceType.id).authority(connection.id).path("/").build()
    fun connectionFields():List<ConnectionField> = emptyList()
    /** Secret fields must be consumed by a backend-owned credential vault, never copied into options. */
    fun createConnection(context:Context,name:String,endpoint:String,fields:Map<String,String>):ProviderConnection {
        require(connectionFields().none { it.secret && !fields[it.key].isNullOrEmpty() }) { "This handler must implement secure credential storage" }
        return ProviderConnection(sourceTypeId=sourceType.id,displayName=name,endpoint=endpoint.takeIf{it.isNotBlank()},
            options=fields.filterKeys { key ->connectionFields().any {it.key==key&&!it.secret} })
    }
}
object StoreCapabilities {
    val registry get() = RuntimeCapabilities.library("store")
    fun protocols():List<StorageCapabilityHandler> = registry.query(Operations.LIST)
        .mapNotNull { it.handler.service(StorageCapabilityHandler::class.java) }.filter {it.userConfigurable}
    /** Bind an actual provider to enable permission/backend-aware resource probes, without a host singleton. */
    fun bind(provider:StorageProvider):java.io.Closeable=registry.register(BoundStorageCapabilityHandler(provider))
}
class ProtocolCapabilityHandler(
    override val sourceType:SourceTypeDescriptor, val schemes:Set<String>,
    private val create:(Context,(String)->ProviderConnection?)->StorageProviderFactory
) : StorageCapabilityHandler {
    override val descriptor=HandlerDescriptor("store.protocol.${sourceType.id}","store",sourceType.label,
        listOf(FormatSupport(uriSchemes=schemes)),setOf(Operations.LIST,Operations.READ,Operations.WRITE,Operations.METADATA),
        metadata=setOf("name","size","modified","resourceCapabilities"),limitations=listOf("Permissions, credentials, server and per-resource restrictions apply; inspect StorageProvider.capabilities before mutation"))
    override fun availability()=HandlerAvailability(AvailabilityState.CONDITIONAL,"Factory installed; access is checked on the actual resource")
    override fun factory(context:Context,connectionLookup:(String)->ProviderConnection?)=create(context,connectionLookup)
}
class StoreCapabilityProvider : CapabilityProvider {
    override val library="store"
    override fun handlers():List<CapabilityHandler> = listOf(
        BuiltinStorageCapabilityHandler("local","Local files",setOf("file"),false) { LocalStorageProvider(it) },
        BuiltinStorageCapabilityHandler("saf","Android documents",setOf("content"),false) { SafProvider(it.contentResolver) },
        BuiltinStorageCapabilityHandler("apps","Installed applications",setOf("apps"),true) { com.infinifolder.apps.AppsStorageProvider(it) },
        BuiltinStorageCapabilityHandler("links","Container links",setOf("container-links"),false) { ContainerLinksProvider(it) },
        BuiltinStorageCapabilityHandler("partial-downloads","Unfinished downloads",setOf("partial-downloads"),true) { PartialDownloadsProvider(it) },
        BuiltinStorageCapabilityHandler("recent-external-links","Recent external links",setOf("recent-external-links"),true) { RecentExternalLinksProvider(it) },
        BuiltinStorageCapabilityHandler("torrent","BitTorrent",setOf("magnet","torrent"),true) { com.infinifolder.torrent.MagnetStorageProvider(it) },
        ProtocolCapabilityHandler(com.infinifolder.sftp.SftpProviderFactory.sourceType,setOf("sftp","ssh","sshfs")) { c,l ->com.infinifolder.sftp.SftpProviderFactory(c,l) },
        ProtocolCapabilityHandler(com.infinifolder.ftp.FtpProviderFactory.sourceType,setOf("ftp","ftps")) { c,l ->com.infinifolder.ftp.FtpProviderFactory(c,l) },
        ProtocolCapabilityHandler(com.infinifolder.smb.SmbProviderFactory.sourceType,setOf("smb","cifs")) { c,l ->com.infinifolder.smb.SmbProviderFactory(c,l) },
        ProtocolCapabilityHandler(com.infinifolder.webdav.WebDavProviderFactory.sourceType,setOf("webdav","dav","davs")) { c,l ->com.infinifolder.webdav.WebDavProviderFactory(c,l) },
        StoreMagicHandler()
    )
}

private class BuiltinStorageCapabilityHandler(id:String,title:String,schemes:Set<String>,readOnly:Boolean,
    private val create:(Context)->StorageProvider):StorageCapabilityHandler {
    override val sourceType=SourceTypeDescriptor(id,title,"folder",title,true)
    override val userConfigurable=false
    override val descriptor=HandlerDescriptor("store.builtin.$id","store",title,listOf(FormatSupport(uriSchemes=schemes)),
        setOf(Operations.LIST,Operations.READ,Operations.METADATA)+if(readOnly)emptySet()else setOf(Operations.WRITE),
        limitations=listOf("Android/provider grants and per-resource capabilities apply; factory construction does not establish access"))
    override fun availability()=HandlerAvailability(AvailabilityState.CONDITIONAL,"Requires Android context and resource access")
    override fun factory(context:Context,connectionLookup:(String)->ProviderConnection?)=object:StorageProviderFactory {
        override val sourceTypeId=sourceType.id
        override val descriptor=sourceType
        override fun create(resolver:android.content.ContentResolver,connection:ProviderConnection?)=create(context)
    }
}

private class BoundStorageCapabilityHandler(private val provider:StorageProvider):CapabilityHandler {
    override val descriptor=HandlerDescriptor("store.bound.${provider.id}","store","Active ${provider.id}",emptyList(),
        setOf(Operations.LIST,Operations.READ,Operations.METADATA)+if(provider.features.readOnly)emptySet()else setOf(Operations.WRITE),
        limitations=listOf("Explicit resource probes may perform provider metadata I/O; transport timeouts apply"))
    override fun availability()=HandlerAvailability(AvailabilityState.CONDITIONAL,"Actual resource operations are resolved by the provider")
    override fun <T:Any> service(type:Class<T>):T?=if(type.isInstance(provider))type.cast(provider)else super.service(type)
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        context.cancellation.check()
        val uri=context.resource.uri?.let(Uri::parse)?:return HandlerProbe(MatchConfidence.NONE,emptySet())
        val ref=provider.refForUri(uri)?:return HandlerProbe(MatchConfidence.NONE,emptySet())
        return when(val result=provider.capabilities(ref)) {
            is com.infinifolder.model.ProviderResult.Success -> {
                val mapping=mapOf(com.infinifolder.model.ResourceCapability.READ to Operations.READ,
                    com.infinifolder.model.ResourceCapability.LIST to Operations.LIST,
                    com.infinifolder.model.ResourceCapability.WRITE to Operations.WRITE,
                    com.infinifolder.model.ResourceCapability.STAT to Operations.METADATA)
                val operations=mapping.filterKeys {result.value[it]==com.infinifolder.model.Support.SUPPORTED}.values.toSet()
                HandlerProbe(MatchConfidence.VERIFIED,operations,"Provider resource capability result")
            }
            is com.infinifolder.model.ProviderResult.Failure -> HandlerProbe(MatchConfidence.NONE,emptySet(),"Provider access failed")
            is com.infinifolder.model.ProviderResult.Unsupported -> HandlerProbe(MatchConfidence.NONE,emptySet(),"Provider capability probe unsupported")
        }
    }
}

/** Standard bounded probe service; the compatibility detector remains the single implementation. */
private class StoreMagicHandler:CapabilityHandler,FormatProbe {
    override val descriptor=HandlerDescriptor("store.magic","store","File type identification",MimeRegistry.declaredFormats(),setOf("identify"),
        metadata=setOf("mimeType","description","confidence"),limitations=listOf("Android service; bounded legacy identification is provisional; parser validation is still required"))
    override fun availability()=if(android.os.Build.VERSION.SDK_INT>=26)HandlerAvailability()
        else HandlerAvailability(AvailabilityState.UNAVAILABLE,"Android storage identification runtime unavailable")
    override fun detect(reader:ProbeReader):List<FormatCandidate> {
        val prefix=reader.bytes(0,minOf(reader.metadata.length?:65536L,65536L).toInt())
        val result=FileMagicDetector.detectFromBytes(prefix)?:return emptyList()
        return listOf(FormatCandidate(ContentType(mimeType=result.mimeType),.5,result.description))
    }
}
