package com.infinifolder.webdav

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.DeviceCapability
import com.infinifolder.archive.RangeCachedBlockDevice
import com.infinifolder.storage.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.*
import java.net.URL
import java.util.Base64
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import okio.source
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class WebDavStorageProvider(
    private val context: Context,
    val credentialStore: WebDavCredentialStore = WebDavCredentialStore(context),
    private val connectionLookup: (String) -> ProviderConnection?
) : StorageProvider {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    override val id = "webdav"
    override val features = BackendFeatures(randomWrite = Support.UNSUPPORTED, creation = CreationGuarantee.EXCLUSIVE, rename = Support.SUPPORTED, delete = Support.SUPPORTED, nativeLinks = Support.UNSUPPORTED)
    override fun refForUri(uri: Uri) = if (WebDavUri.owns(uri)) ResourceRef(id, uri.toString()) else null
    override fun deviceIdentity(ref: ResourceRef): String? = runCatching { "webdav:${WebDavUri.id(Uri.parse(ref.documentId))}" }.getOrNull()
    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = ProviderResult.Success(WebDavUri.parent(Uri.parse(ref.documentId))?.let { ResourceRef(id, it.toString()) })

    private data class Config(val endpoint: String, val user: String, val password: String, val initialPath: String)
    private fun config(uri: Uri): Config {
        val c = connectionLookup(WebDavUri.id(uri)) ?: throw IOException("Saved WebDAV connection not found")
        val endpoint = c.options["url"] ?: c.endpoint ?: throw IOException("WebDAV URL is missing")
        val parsed = Uri.parse(endpoint)
        if (parsed.scheme !in setOf("http", "https")) throw IOException("WebDAV URL must start with https:// or http://")
        val cred = credentialStore.load(c.id)
        return Config(endpoint.trimEnd('/'), c.options["username"].orEmpty(), cred?.password.orEmpty(), c.options["initialPath"] ?: "/")
    }
    private fun url(cfg: Config, path: String): String {
        val base = Uri.parse(cfg.endpoint)
        val prefix = base.encodedPath.orEmpty().trimEnd('/')
        val encoded = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }
        return base.buildUpon().encodedPath(prefix + "/" + encoded).encodedQuery(base.encodedQuery).build().toString()
    }
    private data class DavResponse(val response: Response, val method: String) : Closeable {
        val code get() = response.code
        val message get() = response.message
        fun header(name: String) = response.header(name)
        fun stream(): InputStream = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
        fun errorText() = response.body?.string()?.take(512).orEmpty()
        override fun close() = response.close()
    }
    private fun request(cfg: Config, path: String, method: String, headers: Map<String,String> = emptyMap(), body: ByteArray? = null): DavResponse {
        val builder = Request.Builder().url(url(cfg,path)).header("User-Agent", "InfiniFolder WebDAV")
        if (cfg.user.isNotEmpty()) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("${cfg.user}:${cfg.password}".toByteArray(Charsets.UTF_8)))
        headers.forEach { (k,v) -> builder.header(k,v) }
        val requestBody = body?.toRequestBody(headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull())
        val response = http.newCall(builder.method(method, requestBody).build()).execute()
        return DavResponse(response, method)
    }
    private fun ensureOk(c: DavResponse, accepted: IntRange = 200..299) {
        val code = c.code
        if (code !in accepted) {
            val message = runCatching { c.errorText() }.getOrNull().orEmpty()
            c.close(); throw IOException("WebDAV ${c.method} failed: HTTP $code ${c.message}${if (message.isNotBlank()) " — $message" else ""}")
        }
    }
    private data class Prop(var href: String, var name: String="", var directory: Boolean=false, var size: Long?=null, var modified: Long?=null)
    private fun propfind(cfg: Config, path: String, depth: Int): List<Prop> {
        val xml = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>".toByteArray()
        val c = request(cfg,path,"PROPFIND",mapOf("Depth" to depth.toString(),"Content-Type" to "application/xml; charset=utf-8"),xml)
        ensureOk(c, 200..299)
        val out = mutableListOf<Prop>()
        c.stream().use { stream ->
            val parser = XmlPullParserFactory.newInstance().newPullParser(); parser.setInput(stream,"UTF-8")
            var item: Prop?=null; var key=""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when(parser.name.substringAfter(':')) {
                        "response" -> item=Prop("")
                        "href" -> key="href"
                        "displayname" -> key="name"
                        "getcontentlength" -> key="size"
                        "getlastmodified" -> key="modified"
                        "collection" -> item?.directory=true
                    }
                } else if(parser.eventType == XmlPullParser.TEXT) {
                    val text=parser.text.trim(); when(key) { "href" -> item?.href=text; "name" -> item?.name=text; "size" -> item?.size=text.toLongOrNull(); "modified" -> item?.modified=runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).parse(text)?.time }.getOrNull() }; key=""
                } else if(parser.eventType == XmlPullParser.END_TAG && parser.name.substringAfter(':')=="response") { item?.let { if(it.href.isNotBlank()) out+=it }; item=null }
                parser.next()
            }
        }
        c.close(); return out
    }
    private fun responsePath(cfg: Config, href: String): String {
        val absolute = runCatching { URL(URL(cfg.endpoint), href).toURI() }.getOrThrow()
        val base = URL(cfg.endpoint).toURI().rawPath.trimEnd('/')
        val full = absolute.rawPath
        if (base.isNotEmpty() && full != base && !full.startsWith("$base/")) throw IOException("WebDAV returned a path outside the configured collection")
        return Uri.decode(full.removePrefix(base)).ifEmpty { "/" }
    }
    private fun entry(cfg: Config, p: Prop, connId: String): ResourceEntry {
        val path=responsePath(cfg,p.href); val name=p.name.ifBlank { path.trimEnd('/').substringAfterLast('/') }
        val uri=WebDavUri.build(connId,path)
        return ResourceEntry(ResourceRef(id,uri.toString()), ResourceMetadata(name,if(p.directory) "vnd.android.document/directory" else com.infinifolder.mime.MimeRegistry.guess(name),p.size,p.modified,p.directory),uri)
    }
    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<BlockDevice> {
        return try {
        if (writable) ProviderResult.Unsupported(ResourceCapability.SEEK_WRITE) else {
        val uri=Uri.parse(ref.documentId); val cfg=config(uri); val path=WebDavUri.path(uri)
        val head=request(cfg,path,"GET",mapOf("Range" to "bytes=0-0")); val code=head.code
        val size=head.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        if(code != 206 || size == null || size < 0) { head.close(); ProviderResult.Unsupported(ResourceCapability.SEEK) } else {
        head.stream().use { it.read() }; head.close()
        val fileSize=size
        val device=object:BlockDevice {
            override val size: Long=fileSize
            override val capabilities=setOf(DeviceCapability.READ,DeviceCapability.SEEK)
            override fun close() {}
            override fun writeAt(offset:Long,buffer:ByteArray,srcOffset:Int,length:Int){throw IOException("WebDAV range source is read-only")}
            override fun readAt(offset:Long,buffer:ByteArray,dstOffset:Int,length:Int):Int {
                com.infinifolder.archive.requireDeviceRange(offset,buffer,dstOffset,length)
                if(length==0 || offset>=fileSize) return 0
                val last=(offset+length-1).coerceAtMost(fileSize-1); val c=request(cfg,path,"GET",mapOf("Range" to "bytes=$offset-$last"))
                if(c.code!=206) { val status=c.code; c.close(); throw IOException("WebDAV server stopped honoring byte ranges (HTTP $status)") }
                val wanted=(last-offset+1).toInt(); var total=0
                c.stream().use { stream -> while(total<wanted){val n=stream.read(buffer,dstOffset+total,wanted-total);if(n<0)break;total+=n} };c.close();return total
            }
        }
        ProviderResult.Success(RangeCachedBlockDevice(device))
        }
        }
    } catch(e:Exception){ProviderResult.Failure(e.message ?: "WebDAV range read failed",e)}
    }

    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = try {
        val uri=Uri.parse(ref.documentId); val cfg=config(uri); val path=WebDavUri.path(uri)
        val item=propfind(cfg,path,0).firstOrNull() ?: throw IOException("WebDAV resource not found")
        ProviderResult.Success(entry(cfg,item,WebDavUri.id(uri)))
    } catch(e:Exception) { ProviderResult.Failure(e.message ?: "WebDAV stat failed",e) }
    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri=Uri.parse(parent.documentId); val cfg=config(uri); val path=WebDavUri.path(uri)
        val items=propfind(cfg,path,1).filter { responsePath(cfg,it.href).trimEnd('/') != path.trimEnd('/') }
        ProviderResult.Success(items.map { entry(cfg,it,WebDavUri.id(uri)) }.sortedWith(compareBy({ !it.metadata.isDirectory },{ it.metadata.name.lowercase() })))
    } catch(e:Exception) { ProviderResult.Failure(e.message ?: "WebDAV listing failed",e) }
    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri=Uri.parse(ref.documentId); val cfg=config(uri); val c=request(cfg,WebDavUri.path(uri),"GET"); ensureOk(c); ProviderResult.Success(object:FilterInputStream(c.stream()){ override fun close(){try{super.close()}finally{c.close()} }})
    } catch(e:Exception){ProviderResult.Failure(e.message ?: "WebDAV read failed",e)}
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = try {
        val uri=Uri.parse(ref.documentId); val cfg=config(uri); val input=PipedInputStream(64*1024); val output=PipedOutputStream(input)
        val result=CompletableFuture<Unit>()
        val builder=Request.Builder().url(url(cfg,WebDavUri.path(uri))).put(object:RequestBody(){override fun contentType()=null;override fun contentLength()=-1L;override fun writeTo(sink:BufferedSink){input.source().use { source -> sink.writeAll(source) }}})
            .header("User-Agent","InfiniFolder WebDAV")
        if(cfg.user.isNotEmpty()) builder.header("Authorization","Basic "+Base64.getEncoder().encodeToString("${cfg.user}:${cfg.password}".toByteArray(Charsets.UTF_8)))
        val call=http.newCall(builder.build())
        CompletableFuture.runAsync({ try { call.execute().use { response -> if(!response.isSuccessful) throw IOException("WebDAV PUT failed: HTTP ${response.code} ${response.message}") }; result.complete(Unit) } catch(e:Throwable){ result.completeExceptionally(e) } })
        ProviderResult.Success(object:FilterOutputStream(output){ override fun close(){ var failure:Throwable?=null;try{super.close();result.get()}catch(e:Throwable){failure=(e as? ExecutionException)?.cause ?: e;call.cancel()}finally{input.close()};if(failure!=null)throw IOException("WebDAV upload failed: ${failure.message}",failure) }})
    } catch(e:Exception){ProviderResult.Failure(e.message ?: "WebDAV write failed",e)}
    override fun createDirectory(parent: ResourceRef,name:String):ProviderResult<ResourceEntry> = mutateCreate(parent,name,true)
    override fun createFile(parent: ResourceRef,name:String,mimeType:String):ProviderResult<ResourceEntry> = mutateCreate(parent,name,false)
    private fun mutateCreate(parent:ResourceRef,name:String,dir:Boolean):ProviderResult<ResourceEntry> = try {
        StorageSafety.requireNewName(this,parent,name); val pu=Uri.parse(parent.documentId); val path=WebDavUri.path(pu).trimEnd('/')+"/"+name
        val cfg=config(pu); val c=if(dir) request(cfg,path,"MKCOL") else request(cfg,path,"PUT",mapOf("If-None-Match" to "*"),ByteArray(0)); ensureOk(c,200..299); c.close()
        val r=ResourceRef(id,WebDavUri.build(WebDavUri.id(pu),path).toString()); when(val s=stat(r)){is ProviderResult.Success->ProviderResult.Success(s.value);else->throw IOException("Created WebDAV resource but could not inspect it")}
    }catch(e:Exception){ProviderResult.Failure(e.message ?: "WebDAV create failed",e)}
    override fun rename(ref:ResourceRef,name:String):ProviderResult<ResourceEntry> = try {
        val uri=Uri.parse(ref.documentId); val old=WebDavUri.path(uri); val new=old.substringBeforeLast('/')+"/"+name; val cfg=config(uri)
        val c=request(cfg,old,"MOVE",mapOf("Destination" to url(cfg,new),"Overwrite" to "F")); ensureOk(c); c.close()
        val r=ResourceRef(id,WebDavUri.build(WebDavUri.id(uri),new).toString()); when(val s=stat(r)){is ProviderResult.Success->ProviderResult.Success(s.value);else->throw IOException("Renamed but cannot inspect destination")}
    }catch(e:Exception){ProviderResult.Failure(e.message ?: "WebDAV rename failed",e)}
    override fun delete(ref:ResourceRef):ProviderResult<Unit> = try { val u=Uri.parse(ref.documentId);val cfg=config(u);val c=request(cfg,WebDavUri.path(u),"DELETE");ensureOk(c);c.close();ProviderResult.Success(Unit) }catch(e:Exception){ProviderResult.Failure(e.message ?: "WebDAV delete failed",e)}
    override fun copy(source:ResourceRef,destination:ResourceRef)=ProviderResult.Unsupported(ResourceCapability.COPY)
    override fun move(source:ResourceRef,destination:ResourceRef)=ProviderResult.Unsupported(ResourceCapability.MOVE)
    fun testConnection(connection:ProviderConnection, user:String,password:String):Result<String> = runCatching {
        val cfg=Config(connection.options["url"] ?: connection.endpoint ?: error("URL missing"),user,password,connection.options["initialPath"] ?: "/")
        val c=request(cfg,cfg.initialPath,"PROPFIND",mapOf("Depth" to "0","Content-Type" to "application/xml"),"<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toByteArray());ensureOk(c);c.close();"Connected to ${cfg.endpoint} (WebDAV PROPFIND succeeded)."
    }
}
