package com.infinifolder.torrent

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import bt.Bt
import bt.data.Storage
import bt.data.StorageUnit
import bt.runtime.BtRuntime
import bt.net.buffer.ByteBufferView
import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.metainfo.Torrent
import bt.metainfo.TorrentFile
import bt.torrent.callbacks.FileDownloadCompleteCallback
import bt.torrent.fileselector.FilePriority
import bt.torrent.fileselector.FilePrioritySkipSelector
import com.infinifolder.model.*
import com.infinifolder.storage.*
import com.infinifolder.archive.BlockDevice
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Read-only virtual view of a BitTorrent swarm. Files are fetched only when copied/read. */
class MagnetStorageProvider(context: Context) : StorageProvider, RandomAccessTransferSource {
    override val id = "torrent"
    override val features = BackendFeatures(randomRead = Support.UNSUPPORTED, randomWrite = Support.UNSUPPORTED,
        creation = CreationGuarantee.UNSUPPORTED, rename = Support.UNSUPPORTED, delete = Support.UNSUPPORTED,
        nativeLinks = Support.UNSUPPORTED, readOnly = true)
    private val app = context.applicationContext
    private val metadata = ConcurrentHashMap<String, Torrent>()
    private val activeMetadataRequests = AtomicInteger()
    private val metadataRequests = ConcurrentHashMap<String, CompletableFuture<Torrent>>()
    private val metadataPeerHints = ConcurrentHashMap<String, List<bt.net.Peer>>()
    private val runtimePorts = ConcurrentHashMap<BtRuntime, Int>()
    private val reservedPorts = ConcurrentHashMap.newKeySet<Int>()
    private val metainfoTorrentKeys = ConcurrentHashMap.newKeySet<String>()
    private val attemptedAutoResumeKeys = ConcurrentHashMap.newKeySet<String>()
    private val cacheRoot = File(app.cacheDir, "torrent-downloads")
    private val transferPreferences by lazy { app.getSharedPreferences(TorrentTransferPreferences.NAME, Context.MODE_PRIVATE) }
    private val resumePreferences by lazy { app.getSharedPreferences("torrent-resume-targets", Context.MODE_PRIVATE) }

    private fun resumeTargetKey(source: ResourceRef, parent: ResourceRef, requestedName: String): String =
        sha256Hex("${source.documentId}\u0000${parent.providerId}\u0000${parent.documentId}\u0000$requestedName")

    /** An automatic resume should be attempted once per process; user initiated retries remain available. */
    private fun autoResumeDisabledKey(destination: ResourceRef) =
        "auto-disabled." + sha256Hex("${destination.providerId}\u0000${destination.documentId}")

    override fun setAutoResume(destination: ResourceRef, enabled: Boolean) {
        val editor = resumePreferences.edit()
        if (enabled) editor.remove(autoResumeDisabledKey(destination))
        else editor.putBoolean(autoResumeDisabledKey(destination), true)
        if (!editor.commit()) throw IOException("Could not save the download's automatic resume setting")
    }

    override fun claimAutoResume(source: ResourceRef, destination: ResourceRef): Boolean =
        !resumePreferences.getBoolean(autoResumeDisabledKey(destination), false) &&
            attemptedAutoResumeKeys.add(sha256Hex("${source.documentId}\u0000${destination.providerId}\u0000${destination.documentId}"))

    override fun findResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String): ResumeDestination? {
        val key = resumeTargetKey(source, destinationParent, requestedName)
        val provider = resumePreferences.getString("$key.provider", null) ?: return null
        val document = resumePreferences.getString("$key.document", null) ?: return null
        val name = resumePreferences.getString("$key.name", null) ?: return null
        return ResumeDestination(ResourceRef(provider, document), name)
    }

    override fun rememberResumeDestination(
        source: ResourceRef,
        destinationParent: ResourceRef,
        requestedName: String,
        destination: ResourceRef,
        destinationName: String
    ) {
        val key = resumeTargetKey(source, destinationParent, requestedName)
        resumePreferences.edit().putString("$key.provider", destination.providerId)
            .putString("$key.document", destination.documentId).putString("$key.name", destinationName)
            .putString("$key.source.provider", source.providerId).putString("$key.source.document", source.documentId)
            .putString("$key.parent.provider", destinationParent.providerId).putString("$key.parent.document", destinationParent.documentId)
            .putString("$key.requested", requestedName).commit()
    }

    override fun forgetResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String) {
        val key = resumeTargetKey(source, destinationParent, requestedName)
        resumePreferences.edit().remove("$key.provider").remove("$key.document").remove("$key.name")
            .remove("$key.source.provider").remove("$key.source.document")
            .remove("$key.parent.provider").remove("$key.parent.document").remove("$key.requested").commit()
    }

    override fun pendingResumeTransfers(): List<PendingResumeTransfer> {
        val prefs = resumePreferences
        return prefs.all.keys.asSequence().filter { it.endsWith(".provider") }.mapNotNull { providerKey ->
            val key = providerKey.removeSuffix(".provider")
            val destinationProvider = prefs.getString(providerKey, null) ?: return@mapNotNull null
            val destinationDocument = prefs.getString("$key.document", null) ?: return@mapNotNull null
            val name = prefs.getString("$key.name", null) ?: return@mapNotNull null
            // Archive writes are staged in an in-memory/container batch transaction. They
            // cannot safely be resumed by a fresh process without reopening that transaction.
            if (destinationProvider == "archive" || !name.endsWith(".incomplete", ignoreCase = true)) return@mapNotNull null
            val sourceProvider = prefs.getString("$key.source.provider", null) ?: return@mapNotNull null
            val sourceDocument = prefs.getString("$key.source.document", null) ?: return@mapNotNull null
            val parentProvider = prefs.getString("$key.parent.provider", null) ?: return@mapNotNull null
            val parentDocument = prefs.getString("$key.parent.document", null) ?: return@mapNotNull null
            val requested = prefs.getString("$key.requested", null) ?: return@mapNotNull null
            PendingResumeTransfer(
                ResourceRef(sourceProvider, sourceDocument), ResourceRef(parentProvider, parentDocument), requested,
                ResourceRef(destinationProvider, destinationDocument), name, name.removeSuffix(".incomplete")
            )
        }.toList()
    }

    override fun migrateResumeState(source: ResourceRef, oldDestination: ResourceRef, newDestination: ResourceRef) {
        runCatching {
            val uri = Uri.parse(source.documentId)
            val torrent = fetchMetadata(source(uri))
            val path = segments(uri)
            val selectedPath = if (torrent.files.size > 1) path.drop(1) else path
            val file = torrent.files.firstOrNull { it.pathElements == selectedPath } ?: return
            val oldState = resumeStateFile(torrent, file, oldDestination)
            if (!oldState.isFile) return
            val newState = resumeStateFile(torrent, file, newDestination)
            if (oldState != newState && !oldState.renameTo(newState)) {
                oldState.copyTo(newState, overwrite = true)
                oldState.delete()
            }
            DiagnosticLog.record("torrent.resume.state-migrated", "from=${oldDestination.providerId} to=${newDestination.providerId}")
        }.onFailure { DiagnosticLog.record("torrent.resume.state-migration-failed", "destination=${newDestination.providerId}", it) }
    }

    override fun finishResume(source: ResourceRef, destination: ResourceRef) {
        discardResumeState(source, destination)
    }

    override fun discardResumeState(source: ResourceRef, destination: ResourceRef) {
        runCatching {
            val uri = Uri.parse(source.documentId)
            val torrent = fetchMetadata(source(uri))
            val path = segments(uri)
            val selectedPath = if (torrent.files.size > 1) path.drop(1) else path
            val file = torrent.files.firstOrNull { it.pathElements == selectedPath } ?: return
            val state = resumeStateFile(torrent, file, destination)
            if (state.delete()) DiagnosticLog.record("torrent.resume.state-discarded", "destination=${destination.providerId}:${destination.documentId}")
        }.onFailure { DiagnosticLog.record("torrent.resume.cleanup-failed", "destination=${destination.providerId}", it) }
    }

    private fun sha256Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun resumeStateFile(torrent: Torrent, file: TorrentFile, destination: ResourceRef): File {
        val path = file.pathElements.joinToString("/")
        val key = sha256Hex("${torrent.torrentId}\u0000$path\u0000${destination.providerId}\u0000${destination.documentId}")
        val directory = File(app.filesDir, "torrent-resume").apply { mkdirs() }
        return File(directory, "$key.properties")
    }

    private fun loadVerifiedPieces(stateFile: File, torrent: Torrent, file: TorrentFile): java.util.BitSet {
        if (!stateFile.isFile) return java.util.BitSet()
        return runCatching {
            val properties = java.util.Properties().apply { stateFile.inputStream().use(::load) }
            if (properties.getProperty("version") != "2") {
                DiagnosticLog.record("torrent.resume.state-reset", "file=${stateFile.name} reason=legacy-checkpoint")
                return@runCatching java.util.BitSet()
            }
            if (properties.getProperty("hash") != torrent.torrentId.toString() ||
                properties.getProperty("path") != file.pathElements.joinToString("/") ||
                properties.getProperty("size") != file.size.toString() ||
                properties.getProperty("pieceSize") != torrent.chunkSize.toString()) return@runCatching java.util.BitSet()
            val bytes = android.util.Base64.decode(properties.getProperty("pieces", ""), android.util.Base64.NO_WRAP)
            java.util.BitSet.valueOf(bytes)
        }.getOrElse {
            DiagnosticLog.record("torrent.resume.state-invalid", "file=${stateFile.name}", it)
            java.util.BitSet()
        }
    }

    private fun saveVerifiedPieces(stateFile: File, torrent: Torrent, file: TorrentFile, pieces: java.util.BitSet) {
        runCatching {
            val properties = java.util.Properties().apply {
                setProperty("version", "2")
                setProperty("hash", torrent.torrentId.toString())
                setProperty("path", file.pathElements.joinToString("/"))
                setProperty("size", file.size.toString())
                setProperty("pieceSize", torrent.chunkSize.toString())
                setProperty("pieces", android.util.Base64.encodeToString(pieces.toByteArray(), android.util.Base64.NO_WRAP))
            }
            val temp = File(stateFile.parentFile, "${stateFile.name}.tmp")
            temp.outputStream().use { properties.store(it, null) }
            if (!temp.renameTo(stateFile)) {
                stateFile.delete()
                if (!temp.renameTo(stateFile)) throw IOException("Could not publish torrent resume state")
            }
        }.onFailure { DiagnosticLog.record("torrent.resume.state-save-failed", "file=${stateFile.name}", it) }
    }

    /** Register a .torrent file's local metadata and return a normal browseable magnet URI. */
    fun registerTorrentFile(bytes: ByteArray): Uri {
        if (bytes.size > MAX_TORRENT_FILE_BYTES) throw IOException("Torrent file is larger than the supported 16 MiB limit")
        val torrent = try { bt.metainfo.MetadataService().fromByteArray(bytes) }
            catch (error: Exception) { throw IOException("Invalid or unsupported .torrent file: ${error.message}", error) }
        val key = torrent.torrentId.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (key.length != 40) throw IOException("Unsupported torrent info hash")
        persistMetainfo(key, bytes)
        metadata[key] = torrent
        metainfoTorrentKeys.add(key)
        // A user may re-open the .torrent file after an older build failed to resume
        // because its metadata was only held in memory. Let pending sessions retry now.
        attemptedAutoResumeKeys.clear()
        DiagnosticLog.record("torrent.metainfo.imported",
            "name=${torrent.name} hash=$key files=${torrent.files.size} private=${torrent.isPrivate} trackerAnnounce=${torrent.announceKey.isPresent}")
        return Uri.parse("magnet:?xt=urn:btih:$key&dn=${Uri.encode(torrent.name)}")
    }

    /** Build a browseable root without waiting for metadata or contacting trackers. */
    fun browseRootUri(uri: Uri): Uri {
        val source = source(uri)
        return rootUri(source, torrentKey(uri))
    }

    override fun refForUri(uri: Uri): ResourceRef? = when (uri.scheme?.lowercase()) {
        "magnet", "torrent" -> ResourceRef(id, uri.toString())
        else -> null
    }

    override fun deviceIdentity(ref: ResourceRef): String? = runCatching {
        "torrent:${torrentKey(Uri.parse(ref.documentId))}"
    }.getOrNull()

    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = guarded {
        val uri = Uri.parse(ref.documentId)
        val path = segments(uri)
        if (!isTorrentUri(uri) || path.isEmpty()) ProviderResult.Success(null)
        else ProviderResult.Success(ResourceRef(id, Uri.Builder().scheme("torrent").authority(torrentKey(uri))
            .appendQueryParameter("magnet", source(uri)).apply { path.dropLast(1).forEach(::appendPath) }.build().toString()))
    }

    private fun isTorrentUri(uri: Uri) = uri.scheme.equals("torrent", true)
    private fun magnet(uri: Uri): String = if (uri.scheme.equals("magnet", true)) uri.toString()
        else uri.getQueryParameter("magnet") ?: throw IOException("Torrent link has no magnet URI")

    private fun torrentKey(uri: Uri): String {
        val source = magnet(uri)
        val parsed = Uri.parse(source)
        if (!parsed.scheme.equals("magnet", true)) throw IOException("Invalid magnet URI")
        // Android treats magnet:?... as an opaque URI, so queryParameterNames throws.
        // Reparse only its query as a hierarchical URI; keep `source` unchanged for Bt.
        val query = source.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) throw IOException("Magnet link has no BitTorrent info hash")
        val queryUri = Uri.parse("https://magnet.invalid/?$query")
        val xtValues = queryUri.queryParameterNames
            .filter { it.equals("xt", true) }
            .flatMap { queryUri.getQueryParameters(it) }
        val hash = xtValues.firstNotNullOfOrNull { value ->
            val marker = "urn:btih:"
            val start = value.indexOf(marker, ignoreCase = true)
            if (start < 0) null else value.substring(start + marker.length).trim().let(::normalizeInfoHash)
        } ?: throw IOException("Magnet link has no supported BitTorrent v1 info hash (xt=urn:btih:...)")
        return hash
    }

    /** Canonicalize BEP 9's 40-character hex and 32-character base32 v1 hashes to lowercase hex. */
    private fun normalizeInfoHash(value: String): String? {
        val hash = value.substringBefore('&').trim()
        if (hash.matches(Regex("[0-9a-fA-F]{40}"))) return hash.lowercase()
        if (!hash.matches(Regex("[a-zA-Z2-7]{32}"))) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bytes = ByteArray(20)
        var accumulator = 0
        var bits = 0
        var index = 0
        for (character in hash.uppercase()) {
            val value5 = alphabet.indexOf(character)
            if (value5 < 0) return null
            accumulator = (accumulator shl 5) or value5
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (index >= bytes.size) return null
                bytes[index++] = (accumulator shr bits).toByte()
            }
        }
        if (index != bytes.size || bits != 0) return null
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun fetchMetadata(source: String): Torrent {
        val root = Uri.parse(source)
        val key = torrentKey(root)
        metadata[key]?.let {
            DiagnosticLog.record("torrent.metadata.cache-hit", "hash=$key source=memory files=${it.files.size}")
            return it
        }
        val requestedAt = System.nanoTime()
        DiagnosticLog.record("torrent.metadata.request", "hash=$key activeMetadataRequests=${activeMetadataRequests.get()}")
        val request = CompletableFuture<Torrent>()
        val pending = metadataRequests.putIfAbsent(key, request)
        if (pending != null) {
            DiagnosticLog.record("torrent.metadata.join", "hash=$key sharedRequest=true")
            try { return pending.get() }
            catch (error: java.util.concurrent.ExecutionException) {
                throw IOException("Shared magnet metadata request failed", error.cause ?: error)
            }
        }
        try {
            val result = fetchMetadataOnce(source, key)
            request.complete(result)
            return result
        } catch (error: Throwable) {
            request.completeExceptionally(error)
            throw error
        } finally {
            metadataRequests.remove(key, request)
            DiagnosticLog.record("torrent.metadata.request-finished", "hash=$key elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAt)}")
        }
    }

    private fun fetchMetadataOnce(source: String, key: String): Torrent {
        metadata[key]?.let { return it }
        loadPersistedMetainfo(key)?.let {
            metadata[key] = it
            metainfoTorrentKeys.add(key)
            DiagnosticLog.record("torrent.metainfo.restored", "name=${it.name} hash=$key files=${it.files.size}")
            return it
        }
        recoverLegacyDownloadsMetainfo(key, source)?.let {
            metadata[key] = it
            metainfoTorrentKeys.add(key)
            DiagnosticLog.record("torrent.metainfo.recovered", "name=${it.name} hash=$key source=Downloads")
            return it
        }
        val query = Uri.parse("https://magnet.invalid/?" + source.substringAfter('?', "").substringBefore('#'))
        val trackers = query.getQueryParameters("tr")
        // Host/scheme/port diagnose tracker routing without private path/query tokens.
        val endpoints = trackers.map { tracker -> runCatching {
            val endpoint = Uri.parse(tracker)
            "${endpoint.scheme}://${endpoint.host}:${endpoint.port}"
        }.getOrDefault("invalid") }
        DiagnosticLog.record("torrent.metadata.start", "hash=$key timeoutMinutes=$METADATA_TIMEOUT_MINUTES trackers=${trackers.size} endpoints=$endpoints runtime=shared-network-config activeMetadataRequests=${activeMetadataRequests.incrementAndGet()}")
        val startedAt = System.nanoTime()
        try {
        val found = AtomicReference<Torrent?>()
        val failure = AtomicReference<Throwable?>()
        val ready = CountDownLatch(1)
        val runtime = newTorrentRuntime()
        val client = try { Bt.client(runtime)
            .storage(DiscardStorage)
            .magnet(btMagnet(source))
            .fileSelector(FilePrioritySkipSelector { FilePriority.SKIP })
            .stopWhenDownloaded()
            .afterTorrentFetched { torrent ->
                DiagnosticLog.record("torrent.metadata.received", "hash=$key files=${torrent.files.size} elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}")
                rememberMetadataPeers(runtime, torrent)
                found.set(torrent)
                ready.countDown()
            }
            .build()
        } catch (error: Throwable) {
            releaseTorrentRuntime(runtime)
            throw error
        }
        var metadataCompletion: CompletableFuture<*>? = null
        try {
        val completion = client.startAsync()
        metadataCompletion = completion
        completion.whenComplete { _, error ->
            DiagnosticLog.record("torrent.metadata.client-ended", "hash=$key received=${found.get() != null} elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}", error)
            if (error != null) failure.set(error)
            ready.countDown()
        }
        try {
            val deadline = startedAt + TimeUnit.MINUTES.toNanos(METADATA_TIMEOUT_MINUTES)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val waitForever = transferPreferences.getBoolean(TorrentTransferPreferences.WAIT_FOREVER, false)
                if (!waitForever && remaining <= 0) {
                    DiagnosticLog.record("torrent.metadata.timeout", "hash=$key received=${found.get() != null} clientDone=${completion.isDone}")
                    break
                }
                val waitNanos = if (waitForever) TimeUnit.SECONDS.toNanos(15) else minOf(remaining, TimeUnit.SECONDS.toNanos(15))
                if (ready.await(waitNanos, TimeUnit.NANOSECONDS)) break
                DiagnosticLog.record("torrent.metadata.waiting", "hash=$key elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)} received=${found.get() != null} clientDone=${completion.isDone} indefinite=$waitForever")
            }
        } finally {
            DiagnosticLog.record("torrent.metadata.stopping", "hash=$key received=${found.get() != null}")
        }
        failure.get()?.let { throw IOException("Could not retrieve torrent metadata: ${it.cause?.message ?: it.message}", it) }
        val torrent = found.get() ?: throw IOException("No peer or tracker supplied metadata for this magnet link")
        metadata[key] = torrent
        return torrent
        } finally {
            if (found.get() != null) try { metadataCompletion?.get(2, TimeUnit.SECONDS) }
            catch (error: InterruptedException) { Thread.currentThread().interrupt() }
            catch (error: Exception) { DiagnosticLog.record("torrent.metadata.finish-wait", "hash=$key cause=${error.javaClass.simpleName}") }
            stopTorrentClient(client, runtime)
        }
        } catch (error: Exception) {
            DiagnosticLog.record("torrent.metadata.failed", "hash=$key elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}", error)
            throw error
        } finally {
            DiagnosticLog.record("torrent.metadata.finished", "hash=$key activeMetadataRequests=${activeMetadataRequests.decrementAndGet()}")
        }
    }

    private fun metainfoFile(key: String): File = File(File(app.filesDir, "torrent-metainfo"), "$key.torrent")

    private fun persistMetainfo(key: String, bytes: ByteArray) {
        val destination = metainfoFile(key)
        runCatching {
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, "${destination.name}.tmp")
            temp.outputStream().use { it.write(bytes) }
            if (!temp.renameTo(destination)) {
                destination.delete()
                if (!temp.renameTo(destination)) throw IOException("Could not publish cached torrent metadata")
            }
            DiagnosticLog.record("torrent.metainfo.cached", "hash=$key bytes=${bytes.size}")
        }.onFailure { DiagnosticLog.record("torrent.metainfo.cache-failed", "hash=$key", it) }
    }

    private fun loadPersistedMetainfo(key: String): Torrent? {
        val file = metainfoFile(key)
        if (!file.isFile) return null
        return runCatching {
            val parsed = bt.metainfo.MetadataService().fromByteArray(file.readBytes())
            val parsedKey = parsed.torrentId.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (parsedKey != key) throw IOException("Cached torrent metadata hash does not match its filename")
            parsed
        }.onFailure { DiagnosticLog.record("torrent.metainfo.cache-invalid", "hash=$key", it) }.getOrNull()
    }

    /** Recover sessions created by older builds from the matching .torrent left in Downloads. */
    private fun recoverLegacyDownloadsMetainfo(key: String, source: String): Torrent? = runCatching {
        val query = source.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        val queryUri = Uri.parse("https://magnet.invalid/?$query")
        val name = queryUri.getQueryParameter("dn")?.takeIf { it.isNotBlank() } ?: return null
        // Keep this recovery narrowly scoped: never let an untrusted magnet name escape Downloads.
        if (name != File(name).name || name == "." || name == "..") return null
        val downloads = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)
            .canonicalFile
        val candidate = File(downloads, "$name.torrent").canonicalFile
        if (candidate.parentFile != downloads || !candidate.isFile || candidate.length() !in 1..MAX_TORRENT_FILE_BYTES.toLong()) return null
        val bytes = candidate.readBytes()
        val torrent = bt.metainfo.MetadataService().fromByteArray(bytes)
        val candidateKey = torrent.torrentId.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (candidateKey != key) {
            DiagnosticLog.record("torrent.metainfo.recovery-mismatch", "expected=$key found=$candidateKey file=${candidate.name}")
            return null
        }
        persistMetainfo(key, bytes)
        attemptedAutoResumeKeys.clear()
        torrent
    }.onFailure { DiagnosticLog.record("torrent.metainfo.recovery-failed", "hash=$key source=Downloads", it) }.getOrNull()

    private fun newTorrentRuntime(trustedPieces: java.util.BitSet? = null,
        availablePieces: java.util.BitSet? = null, onChecked: (Int, Boolean) -> Unit = { _, _ -> }): BtRuntime {
        // Probe both protocols while holding the TCP socket. Retain an in-process
        // reservation until shutdown so concurrent runtimes never select one port.
        val port = synchronized(reservedPorts) {
            var selected: Int? = null
            repeat(32) {
                if (selected == null) java.net.ServerSocket(0).use { tcp ->
                    if (tcp.localPort !in reservedPorts) runCatching {
                        java.net.DatagramSocket(tcp.localPort).use { selected = tcp.localPort }
                    }
                }
            }
            (selected ?: throw IOException("No free torrent TCP/UDP listener port")).also { reservedPorts.add(it) }
        }
        try {
            val config = bt.runtime.Config().apply {
                setAcceptorPort(port)
                setTrackerTimeout(java.time.Duration.ofSeconds(15))
                // Bt 1.10 binds every outbound peer socket to acceptorAddress. Its default address
                // selection picks one local interface (often IPv4), which makes all peers returned
                // in the other address family fail with "Address family not supported". An IPv6
                // wildcard lets Android's dual-stack socket route both IPv4 and IPv6 peer sockets.
                setAcceptorAddress(java.net.InetAddress.getByAddress(ByteArray(16)))
            }
            DiagnosticLog.record("torrent.runtime.network", "acceptor=ipv6-wildcard dualStack=true listenerPort=$port dhtPort=$port")
            val builder = BtRuntime.builder(config)
                .autoLoadModules()
                // Android's interface enumeration returns no usable NetworkInterface for the
                // dual-stack wildcard acceptor. LSD then throws once per second and cannot help
                // discover Internet peers; tracker and DHT discovery remain enabled.
                .disableLocalServiceDiscovery()
                .module(DualStackTrackerModule())
                .module(TorrentPeerModule())
                .module(DHTModule(DHTConfig().apply {
                    setShouldUseRouterBootstrap(true)
                    setListeningPort(port)
                }))
            if (trustedPieces != null) builder.module(ResumeVerifierModule(trustedPieces, onChecked, availablePieces))
            return builder.build().also { runtimePorts[it] = port }
        } catch (error: Throwable) {
            reservedPorts.remove(port)
            throw error
        }
    }

    /** Query HTTP(S) trackers with OkHttp. Bt 1.10's Apache HTTP client collides with Android's
     * platform Apache classes, so use Android-compatible networking and feed discovered peers
     * directly into the torrent runtime. */
    private fun probeTrackers(runtime: BtRuntime, torrent: Torrent, operation: String,
        nextAnnounce: ConcurrentHashMap<String, Long>? = null) {
        val announce = torrent.announceKey.orElse(null)
        val allUrls = announce?.let { if (it.isMultiKey) it.trackerUrls.flatten().distinct() else listOf(it.trackerUrl) }.orEmpty()
        val trackerService = runCatching { runtime.service(bt.peer.IPeerRegistry::class.java) }.getOrElse { error ->
            DiagnosticLog.record("torrent.tracker.peer-registry.failed", "operation=$operation", error)
            return
        }
        if (!torrent.isPrivate) {
            val udpUrls = allUrls.filter { runCatching { java.net.URI(it).scheme.equals("udp", true) }.getOrDefault(false) }
            udpUrls.forEach { trackerService.addPeerSource(torrent.torrentId, bt.tracker.AnnounceKey(it)) }
            val hints = metadataPeerHints[torrent.torrentId.toString()].orEmpty()
            hints.forEach { peer -> trackerService.addPeer(torrent.torrentId,
                bt.net.InetPeer.build(peer.inetAddress, peer.port)) }
            trackerService.triggerPeerCollection(torrent.torrentId)
            DiagnosticLog.record("torrent.discovery.sources", "operation=$operation hash=${torrent.torrentId} udpTrackers=${udpUrls.size} metadataPeerHints=${hints.size}")
        }
        // UDP uses the dual-stack factory and its own tracker interval scheduler.
        // Never send a UDP URI through OkHttp's HTTP-only request builder.
        val urls = allUrls.filter { runCatching { java.net.URI(it).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false) }
        if (urls.isEmpty()) return
        val peerId = runCatching { runtime.service(bt.service.IdentityService::class.java).localPeerId.bytes }
            .getOrElse { error ->
                DiagnosticLog.record("torrent.tracker.identity.failed", "operation=$operation", error)
                return
            }
        val left = torrent.files.sumOf { it.size.coerceAtLeast(0L) }
        urls.forEach { url ->
            if (nextAnnounce != null) {
                val now = System.nanoTime()
                var allowed = false
                nextAnnounce.compute(url) { _, previous ->
                    if (previous == null || now >= previous) {
                        allowed = true
                        now + TimeUnit.MINUTES.toNanos(1)
                    } else previous
                }
                if (!allowed) return@forEach
            }
            val host = runCatching { java.net.URI(url).host ?: "?" }.getOrDefault("invalid-url")
            DiagnosticLog.record("torrent.tracker.probe.scheduled", "operation=$operation host=$host")
            CompletableFuture.runAsync {
                val started = System.nanoTime()
                try {
                    val query = buildString {
                        append(url.substringBefore('#'))
                        append(if (url.substringBefore('#').contains('?')) '&' else '?')
                        append("info_hash=").append(percentEncode(torrent.torrentId.bytes))
                        append("&peer_id=").append(percentEncode(peerId))
                        append("&port=").append(runtime.config.acceptorPort)
                        append("&uploaded=0&downloaded=0&left=").append(left)
                        append("&compact=1&numwant=50&event=started")
                    }
                    DiagnosticLog.record("torrent.tracker.request.started", "operation=$operation host=$host")
                    val request = okhttp3.Request.Builder().url(query).header("User-Agent", "InfiniFolder/1.0").build()
                    val response = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                        .callTimeout(20, TimeUnit.SECONDS).build().newCall(request).execute()
                    response.use { http ->
                        if (!http.isSuccessful) throw IOException("Tracker returned HTTP ${http.code}")
                        val payload = http.body?.bytes() ?: throw IOException("Tracker returned an empty response")
                        val dictionary = BencodeReader(payload).read() as? Map<*, *>
                            ?: throw IOException("Tracker response is not a bencoded dictionary")
                        nextAnnounce?.set(url, System.nanoTime() + TimeUnit.SECONDS.toNanos(
                            maxOf(60L, (dictionary["interval"] as? Long) ?: 60L,
                                (dictionary["min interval"] as? Long) ?: 0L).coerceAtMost(86400L)))
                        val failure = (dictionary["failure reason"] as? ByteArray)?.toString(Charsets.UTF_8)
                        if (failure != null) throw IOException("Tracker rejected announce: ${failure.take(180)}")
                        val peers4 = dictionary["peers"] as? ByteArray
                        val peers6 = dictionary["peers6"] as? ByteArray
                        val peerList = dictionary["peers"] as? List<*>
                        val added4 = peers4?.let { addCompactPeers(it, 6, torrent, trackerService) } ?: 0
                        val added6 = peers6?.let { addCompactPeers(it, 18, torrent, trackerService) } ?: 0
                        val addedDictionary = peerList?.let { addDictionaryPeers(it, torrent, trackerService) } ?: 0
                        DiagnosticLog.record(
                            "torrent.tracker.probe",
                            "operation=$operation host=$host status=${http.code} " +
                                "ipv4Entries=${peers4?.size?.div(6) ?: 0} ipv6Entries=${peers6?.size?.div(18) ?: 0} " +
                                "dictionaryEntries=$addedDictionary peersAdded=${added4 + added6 + addedDictionary} " +
                                "seeds=${dictionary["complete"] ?: "?"} leechers=${dictionary["incomplete"] ?: "?"} " +
                                "elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}"
                        )
                    }
                } catch (error: Exception) {
                    DiagnosticLog.record(
                        "torrent.tracker.probe.failed",
                        "operation=$operation host=$host type=${error.javaClass.simpleName} " +
                            "elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} message=${error.message?.take(180)}"
                    )
                }
            }
            }
        }

    private fun percentEncode(bytes: ByteArray): String = buildString(bytes.size * 3) {
        bytes.forEach { append('%').append("0123456789ABCDEF"[(it.toInt() ushr 4) and 15]).append("0123456789ABCDEF"[it.toInt() and 15]) }
    }

    private fun addCompactPeers(bytes: ByteArray, width: Int, torrent: Torrent, registry: bt.peer.IPeerRegistry): Int {
        var added = 0
        var offset = 0
        while (offset + width <= bytes.size) {
            val addressBytes = bytes.copyOfRange(offset, offset + width - 2)
            val port = ((bytes[offset + width - 2].toInt() and 0xff) shl 8) or (bytes[offset + width - 1].toInt() and 0xff)
            offset += width
            if (port == 0) continue
            runCatching { registry.addPeer(torrent.torrentId, bt.net.InetPeer.build(java.net.InetAddress.getByAddress(addressBytes), port)); added++ }
        }
        return added
    }

    private fun addDictionaryPeers(peers: List<*>, torrent: Torrent, registry: bt.peer.IPeerRegistry): Int {
        var added = 0
        peers.forEach { item ->
            val peer = item as? Map<*, *> ?: return@forEach
            val address = (peer["ip"] as? ByteArray)?.toString(Charsets.UTF_8) ?: return@forEach
            val port = (peer["port"] as? Long)?.toInt() ?: return@forEach
            if (port !in 1..65535) return@forEach
            runCatching { registry.addPeer(torrent.torrentId, bt.net.InetPeer.build(java.net.InetAddress.getByName(address), port)); added++ }
        }
        return added
    }

    /** Minimal bounded bencode reader for standard HTTP tracker responses. */
    private class BencodeReader(private val input: ByteArray) {
        private var offset = 0
        fun read(): Any {
            if (offset >= input.size) throw IOException("Unexpected end of tracker response")
            return when (input[offset].toInt().toChar()) {
                'd' -> readDictionary()
                'l' -> readList()
                'i' -> readInteger()
                in '0'..'9' -> readBytes()
                else -> throw IOException("Invalid bencode token in tracker response")
            }
        }
        private fun readDictionary(): Map<String, Any> {
            offset++
            val result = LinkedHashMap<String, Any>()
            while (offset < input.size && input[offset].toInt().toChar() != 'e') {
                val key = readBytes().toString(Charsets.UTF_8)
                result[key] = read()
            }
            if (offset >= input.size) throw IOException("Unterminated tracker dictionary")
            offset++
            return result
        }
        private fun readList(): List<Any> {
            offset++
            val result = ArrayList<Any>()
            while (offset < input.size && input[offset].toInt().toChar() != 'e') result.add(read())
            if (offset >= input.size) throw IOException("Unterminated tracker list")
            offset++
            return result
        }
        private fun readInteger(): Long {
            offset++
            val end = (offset until input.size).firstOrNull { input[it] == 'e'.code.toByte() } ?: -1
            if (end < 0) throw IOException("Unterminated tracker integer")
            val value = input.copyOfRange(offset, end).toString(Charsets.US_ASCII).toLongOrNull()
                ?: throw IOException("Invalid tracker integer")
            offset = end + 1
            return value
        }
        private fun readBytes(): ByteArray {
            val colon = (offset until input.size).firstOrNull { input[it] == ':'.code.toByte() } ?: -1
            if (colon < 0) throw IOException("Invalid tracker byte string")
            val length = input.copyOfRange(offset, colon).toString(Charsets.US_ASCII).toIntOrNull()
                ?: throw IOException("Invalid tracker byte string length")
            if (length < 0 || colon + 1 + length > input.size) throw IOException("Tracker byte string exceeds response")
            offset = colon + 1 + length
            return input.copyOfRange(colon + 1, offset)
        }
    }

    private fun rememberMetadataPeers(runtime: BtRuntime, torrent: Torrent) {
        runCatching {
            val peers = LinkedHashMap<String, bt.net.Peer>()
            val ownId = runtime.service(bt.service.IdentityService::class.java).localPeerId
            runtime.service(bt.net.IPeerConnectionPool::class.java).visitConnections(torrent.torrentId) { connection ->
                val peer = connection.remotePeer
                if (!connection.isClosed && peer.port in 1..65535 && peer.peerId.orElse(null) != ownId && peers.size < 64)
                    peers["${peer.inetAddress.hostAddress}:${peer.port}"] = bt.net.InetPeer.build(peer.inetAddress, peer.port)
            }
            metadataPeerHints[torrent.torrentId.toString()] = peers.values.toList()
            DiagnosticLog.record("torrent.metadata.peer-hints", "hash=${torrent.torrentId} peers=${peers.size}")
        }.onFailure { DiagnosticLog.record("torrent.metadata.peer-hints.failed", "hash=${torrent.torrentId}", it) }
    }

    /** Count discovered candidates and successful connections without putting peer IPs in debug dumps. */
    private fun monitorPeerEvents(runtime: BtRuntime, torrent: Torrent, operation: String) {
        val torrentId = torrent.torrentId
        val discovered = AtomicInteger()
        val discoveryLogTime = java.util.concurrent.atomic.AtomicLong()
        val connected = AtomicInteger()
        val disconnected = AtomicInteger()
        val connectionStarts = ConcurrentHashMap<bt.net.ConnectionKey, Long>()
        runtime.eventSource
            .onPeerDiscovered(torrentId) {
                val count = discovered.incrementAndGet()
                val now = System.nanoTime()
                val previous = discoveryLogTime.get()
                if ((count <= 3 || now - previous >= TimeUnit.SECONDS.toNanos(5)) && discoveryLogTime.compareAndSet(previous, now))
                    DiagnosticLog.record("torrent.peer.discovered", "operation=$operation count=$count")
            }
            .onPeerConnected(torrentId) { event ->
                val peer = event.peer
                connectionStarts[event.connectionKey] = System.nanoTime()
                DiagnosticLog.record(
                    "torrent.peer.connected",
                    "operation=$operation count=${connected.incrementAndGet()} family=${if (peer.inetAddress.address.size == 16) "ipv6" else "ipv4"} port=${peer.port} btHandshakeComplete=true"
                )
            }
            .onPeerDisconnected(torrentId) { event ->
                val peer = event.peer
                val connectedAt = connectionStarts.remove(event.connectionKey)
                val lifetimeMs = connectedAt?.let { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it) }
                DiagnosticLog.record(
                    "torrent.peer.disconnected",
                    "operation=$operation count=${disconnected.incrementAndGet()} family=${if (peer.inetAddress.address.size == 16) "ipv6" else "ipv4"} port=${peer.port} btHandshakeComplete=true lifetimeMs=${lifetimeMs ?: "unknown"}"
                )
            }
            .onPeerBitfieldUpdated(torrentId) { event ->
                DiagnosticLog.record(
                    "torrent.peer.bitfield",
                    "operation=$operation pieces=${event.bitfield.piecesComplete}/${event.bitfield.piecesTotal}"
                )
            }
    }

    private fun stopTorrentClient(client: bt.runtime.BtClient, runtime: BtRuntime) {
        runCatching { client.stop() }
        releaseTorrentRuntime(runtime)
    }

    private fun releaseTorrentRuntime(runtime: BtRuntime) {
        val stopped = runCatching { runtime.shutdown() }
        if (stopped.isSuccess) runtimePorts.remove(runtime)?.let { port ->
            reservedPorts.remove(port)
            DiagnosticLog.record("torrent.runtime.stopped", "listenerPort=$port")
        } else DiagnosticLog.record("torrent.runtime.shutdown-failed", "Port reservation retained", stopped.exceptionOrNull())
    }

    private fun source(uri: Uri): String = if (isTorrentUri(uri)) magnet(uri) else uri.toString()
    /** Bt 1.10's magnet parser does not percent-decode `tr` values. Decode URL delimiters
     * commonly escaped by magnet generators so tracker URLs retain their udp:// or https:// scheme. */
    private fun btMagnet(source: String): String {
        val queryStart = source.indexOf('?')
        if (queryStart < 0) return source
        val queryEnd = source.indexOf('#', queryStart).let { if (it < 0) source.length else it }
        val query = source.substring(queryStart + 1, queryEnd).split('&').joinToString("&") { parameter ->
            val key = parameter.substringBefore('=')
            val normalized = if (!key.equals("tr", true) || '=' !in parameter) parameter else {
                val value = parameter.substringAfter('=')
                    .replace(Regex("%3a", RegexOption.IGNORE_CASE), ":")
                    .replace(Regex("%2f", RegexOption.IGNORE_CASE), "/")
                    .replace(Regex("%3f", RegexOption.IGNORE_CASE), "?")
                "$key=$value"
            }
            // java.net.URI.getSchemeSpecificPart() decodes percent escapes. Bt 1.10 then
            // splits that decoded string on '&', so an escaped '&' in dn becomes a bogus
            // parameter without '=' and crashes with "length=1; index=1". Double-escape
            // remaining percent signs so the library sees the original encoded value.
            normalized.replace("%", "%25")
        }
        return source.substring(0, queryStart + 1) + query + source.substring(queryEnd)
    }
    private fun torrent(uri: Uri): Torrent = fetchMetadata(source(uri))
    /** Magnet metadata contains the info dictionary, not its external tracker list. */
    private fun withMagnetTrackers(torrent: Torrent, source: String): Torrent {
        if (torrent.announceKey.isPresent) return torrent
        val query = source.substringAfter('?', "").substringBefore('#')
        val trackers = Uri.parse("https://magnet.invalid/?$query").getQueryParameters("tr").distinct()
            .filter { url -> runCatching {
                val endpoint = java.net.URI(url)
                endpoint.scheme?.lowercase() in setOf("udp", "http", "https") && !endpoint.host.isNullOrEmpty()
            }.getOrDefault(false) }
        if (trackers.isEmpty()) return torrent
        val announce = bt.tracker.AnnounceKey(trackers.map { listOf(it) })
        return object : Torrent by torrent {
            override fun getAnnounceKey(): java.util.Optional<bt.tracker.AnnounceKey> = java.util.Optional.of(announce)
        }
    }

    private fun configureTorrentSource(builder: bt.TorrentClientBuilder<*>, source: String) {
        val key = torrentKey(Uri.parse(source))
        val imported = metadata[key]?.let { withMagnetTrackers(it, source) }
        if (imported != null) {
            val announce = imported.announceKey.orElse(null)
            val trackers = announce?.let {
                val urls = if (it.isMultiKey) it.trackerUrls.flatten() else listOf(it.trackerUrl)
                urls.distinct().joinToString(",") { url ->
                    runCatching { java.net.URI(url).let { endpoint -> "${endpoint.scheme}://${endpoint.host ?: "?"}" } }
                        .getOrDefault("invalid-url")
                }
            } ?: "none"
            DiagnosticLog.record("torrent.client.source", "type=metainfo hash=$key files=${imported.files.size} trackerCount=${announce?.let { if (it.isMultiKey) it.trackerUrls.flatten().distinct().size else 1 } ?: 0} trackers=$trackers")
            builder.torrent {
                if (imported.isPrivate) imported else object : Torrent by imported {
                    override fun getAnnounceKey(): java.util.Optional<bt.tracker.AnnounceKey> = java.util.Optional.empty()
                }
            }
        } else {
            DiagnosticLog.record("torrent.client.source", "type=magnet hash=$key")
            builder.magnet(btMagnet(source))
        }
    }

    /** Record peer and piece state while the client runs, so zero-byte stalls can be distinguished. */
    private fun startTorrentClient(client: bt.runtime.BtClient, operation: String,
        onState: ((bt.torrent.TorrentSessionState) -> Unit)? = null): CompletableFuture<*> {
        var lastLoggedAt = 0L
        return client.startAsync({ state ->
            val now = System.nanoTime()
            if (now - lastLoggedAt >= TimeUnit.MILLISECONDS.toNanos(SESSION_LOG_INTERVAL_MS)) {
                lastLoggedAt = now
                DiagnosticLog.record("torrent.session", "operation=$operation downloaded=${state.downloaded} uploaded=${state.uploaded} left=${state.left} " +
                    "pieces=${state.piecesComplete}/${state.piecesTotal} peers=${state.connectedPeers.size}")
            }
            onState?.invoke(state)
        }, if (onState != null) 1000L else SESSION_LOG_INTERVAL_MS)
    }
    // Android exposes an opaque magnet:? URI in pathSegments on some releases;
    // always treat it as the torrent root instead of a child path.
    private fun segments(uri: Uri): List<String> = if (uri.scheme.equals("magnet", true)) emptyList()
        else uri.pathSegments.filter { it.isNotEmpty() }
    private fun rootUri(source: String, key: String): Uri = Uri.Builder().scheme("torrent").authority(key)
        .appendQueryParameter("magnet", source).path("/").build()
    private fun childUri(parent: Uri, path: List<String>): Uri = Uri.Builder().scheme("torrent").authority(torrentKey(parent))
        .appendQueryParameter("magnet", source(parent)).apply { path.forEach(::appendPath) }.build()

    private data class Node(val name: String, val directory: Boolean, val size: Long?, val path: List<String>)
    private fun fullPaths(t: Torrent): List<Pair<List<String>, TorrentFile>> = t.files.map { file ->
        val path = file.pathElements.toList().let { if (t.files.size > 1) listOf(t.name) + it else it }
        path to file
    }

    private fun nodes(uri: Uri, t: Torrent): List<Node> {
        val prefix = segments(uri)
        val direct = linkedMapOf<String, Node>()
        fullPaths(t).forEach { (path, file) ->
            if (path.size <= prefix.size || path.take(prefix.size) != prefix) return@forEach
            val name = path[prefix.size]
            val childPath = prefix + name
            val isDir = path.size > prefix.size + 1
            val previous = direct[name]
            direct[name] = if (isDir) Node(name, true, null, childPath)
                else previous ?: Node(name, false, file.size, childPath)
        }
        return direct.values.sortedWith(compareBy<Node>({ !it.directory }, { it.name.lowercase() }))
    }

    private fun entry(source: String, key: String, node: Node): ResourceEntry {
        val uri = Uri.Builder().scheme("torrent").authority(key).appendQueryParameter("magnet", source)
            .apply { node.path.forEach(::appendPath) }.build()
        val mime = if (node.directory) "vnd.android.document/directory" else com.infinifolder.mime.MimeRegistry.guess(node.name)
        return ResourceEntry(ResourceRef(id, uri.toString()), ResourceMetadata(node.name, mime, node.size, null, node.directory), uri)
    }

    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = guarded {
        val uri = Uri.parse(ref.documentId)
        val source = source(uri)
        val key = torrentKey(uri)
        val t = fetchMetadata(source)
        if (uri.scheme.equals("magnet", true)) {
            val root = rootUri(source, key)
            ProviderResult.Success(ResourceEntry(ResourceRef(id, root.toString()), ResourceMetadata(t.name, "vnd.android.document/directory", t.size, null, true), root))
        } else {
            val path = segments(uri)
            val node = if (path.isEmpty()) Node(t.name, true, t.size, emptyList()) else {
                val files = fullPaths(t)
                val file = files.firstOrNull { it.first == path }?.second
                val directory = files.any { it.first.size > path.size && it.first.take(path.size) == path }
                when {
                    file != null -> Node(path.last(), false, file.size, path)
                    directory -> Node(path.last(), true, null, path)
                    else -> throw FileNotFoundException("Torrent entry not found")
                }
            }
            ProviderResult.Success(entry(source, key, node))
        }
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = guarded {
        val uri = Uri.parse(parent.documentId)
        val source = source(uri)
        val t = fetchMetadata(source)
        val parentPath = segments(uri)
        if (parentPath.isNotEmpty() && fullPaths(t).none { (path, _) -> path == parentPath || (path.size > parentPath.size && path.take(parentPath.size) == parentPath) })
            throw IOException("Torrent entry is not a directory")
        ProviderResult.Success(nodes(uri, t).map { entry(source, torrentKey(uri), it) })
    }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = guarded {
        val uri = Uri.parse(ref.documentId)
        val source = source(uri)
        val t = withMagnetTrackers(fetchMetadata(source), source)
        val path = segments(uri)
        val desired = if (t.files.size > 1) path.drop(1) else path
        val torrentFile = t.files.firstOrNull { it.pathElements == desired }
            ?: throw IOException("Select a file inside the torrent before copying")
        val key = torrentKey(uri)
        val root = File(cacheRoot, key)
        val output = safeTorrentFile(root, t, torrentFile)
        val verified = File(output.parentFile, output.name + ".ifverified")
        if (torrentFile.size == 0L) {
            if (!output.exists() && !output.createNewFile()) throw IOException("Unable to prepare empty torrent file")
            verified.writeText(key)
            return@guarded ProviderResult.Success(output.inputStream())
        }
        if (verified.isFile && output.length() == torrentFile.size) return@guarded ProviderResult.Success(output.inputStream())
        val free = StatFs(app.cacheDir.absolutePath).availableBytes
        if (torrentFile.size > free) throw IOException("Not enough free cache space to receive this torrent file (${torrentFile.size} bytes required)")
        reserveDownloadSpace(output, torrentFile.size, key)

        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val runtime = newTorrentRuntime()
        monitorPeerEvents(runtime, t, "cache-download")
        val clientBuilder = Bt.client(runtime).storage(bt.data.file.FileSystemStorage(root))
        configureTorrentSource(clientBuilder, source)
        val client = clientBuilder
            .fileSelector(FilePrioritySkipSelector { candidate ->
                if (candidate.pathElements == torrentFile.pathElements) FilePriority.NORMAL_PRIORITY else FilePriority.SKIP
            })
            .afterFileDownloaded(FileDownloadCompleteCallback { _, candidate, _ -> if (candidate.pathElements == torrentFile.pathElements) completed.countDown() })
            .stopWhenDownloaded()
            .build()
        val running = startTorrentClient(client, "cache-download")
        probeTrackers(runtime, t, "cache-download")
        running.whenComplete { _, error ->
            if (error != null || completed.count > 0L) {
                failure.compareAndSet(null, error ?: IOException("Torrent engine stopped before the selected file completed; see torrent processing diagnostics"))
                completed.countDown()
            }
        }
        val waitStarted = System.nanoTime()
        try {
            while (!completed.await(250, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Torrent download cancelled")
                if (!transferPreferences.getBoolean(TorrentTransferPreferences.WAIT_FOREVER, false) &&
                    System.nanoTime() - waitStarted >= TimeUnit.MINUTES.toNanos(DOWNLOAD_TIMEOUT_MINUTES))
                    throw IOException("Torrent download timed out before the file was complete")
            }
        } finally { stopTorrentClient(client, runtime) }
        failure.get()?.let { throw IOException("Torrent download failed: ${it.cause?.message ?: it.message}", it) }
        val downloaded = safeTorrentFile(root, t, torrentFile)
        if (!downloaded.isFile || downloaded.length() != torrentFile.size) throw IOException("Torrent file did not pass its size check")
        File(downloaded.parentFile, downloaded.name + ".ifverified").writeText(key)
        ProviderResult.Success(downloaded.inputStream())
    }

    /** Receive verified torrent pieces directly into a random-write destination; no full-file cache. */
    override fun transferTo(
        source: ResourceRef,
        destination: ResourceRef,
        target: BlockDevice,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        onPieceVerification: (verifiedPieces: Int, totalPieces: Int) -> Unit,
        checkpointStore: TransferCheckpointStore?,
        onBytesRemaining: (Long) -> Unit,
        onResumeStatus: (String) -> Unit,
        onTorrentStats: (TorrentTransferStats) -> Unit
    ): ProviderResult<Long> = guarded {
        DiagnosticLog.record("torrent.destination.begin", "source=${source.documentId} targetSize=${target.size}")
        val uri = Uri.parse(source.documentId)
        val magnetSource = source(uri)
        val torrent = withMagnetTrackers(fetchMetadata(magnetSource), magnetSource)
        val path = segments(uri)
        val selectedPath = if (torrent.files.size > 1) path.drop(1) else path
        val file = torrent.files.firstOrNull { it.pathElements == selectedPath }
            ?: throw IOException("Select a torrent file before copying")
        if (file.size < 0L) throw IOException("Torrent reported an invalid file size")
        if (target.isReadOnly || !target.capabilities.contains(com.infinifolder.archive.DeviceCapability.WRITE) ||
            !target.capabilities.contains(com.infinifolder.archive.DeviceCapability.TRUNCATE) ||
            !target.capabilities.contains(com.infinifolder.archive.DeviceCapability.READ))
            throw IOException("Destination must support random-access read/write and truncation for verified torrent transfers")
        DiagnosticLog.record("torrent.destination.metadata", "name=${torrent.name} files=${torrent.files.size} selected=${file.pathElements.joinToString("/")} bytes=${file.size}")
        val resumeFile = resumeStateFile(torrent, file, destination)
        val previousSize = target.size
        val destinationAlreadySized = previousSize == file.size
        if (!destinationAlreadySized && resumeFile.exists()) {
            if (!resumeFile.delete()) {
                DiagnosticLog.record("torrent.resume.state-reset-failed", "file=${resumeFile.name} reason=destination-size-changed")
                throw IOException("Cannot safely resume: the destination was replaced but its old verification state could not be cleared")
            }
            DiagnosticLog.record("torrent.resume.state-reset", "file=${resumeFile.name} previousBytes=$previousSize expectedBytes=${file.size}")
        }
        val verifiedPieces = if (destinationAlreadySized) loadVerifiedPieces(resumeFile, torrent, file) else java.util.BitSet()
        if (!destinationAlreadySized) {
            val sizingStarted = System.nanoTime()
            target.truncate(file.size)
            DiagnosticLog.record("torrent.destination.sized", "bytes=${file.size} previousBytes=$previousSize elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sizingStarted)}")
        } else {
            DiagnosticLog.record("torrent.resume.begin", "name=${file.pathElements.lastOrNull()} verifiedPieces=${verifiedPieces.cardinality()} destination=${destination.providerId}")
        }
        if (file.size == 0L) return@guarded ProviderResult.Success(0L)

        val selectedIndex = torrent.files.indexOfFirst { it.pathElements == file.pathElements }
        val fileGlobalStart = if (selectedIndex <= 0) 0L else torrent.files.take(selectedIndex).sumOf { it.size.coerceAtLeast(0L) }

        val targetIoLock = Any()
        val lifecycle = TorrentTransferLifecycle(targetIoLock, isCancelled)
        val checkpoint = TorrentVerificationCheckpoint(
            TorrentCheckpointIdentity("${destination.providerId}:${destination.documentId}", torrent.torrentId.toString(),
                file.pathElements.joinToString("/"), torrent.chunkSize, torrent.chunkHashes.count(),
                torrent.files.sumOf { it.size }, fileGlobalStart, file.size),
            checkpointStore, { target.flush() }, destinationAlreadySized
        ) { event, error -> DiagnosticLog.record("torrent.checkpoint.$event", "Resume checkpoint fallback", error) }
        val trustedPieces = checkpoint.trustedPieces
        verifiedPieces.or(trustedPieces)
        DiagnosticLog.record("torrent.checkpoint.loaded", "trustedPieces=${trustedPieces.cardinality()} destination=${destination.providerId} reason=${checkpoint.loadReason}")
        val hasExistingData = !verifiedPieces.isEmpty
        onResumeStatus(when {
            !hasExistingData -> "Waiting for peers…"
            !trustedPieces.isEmpty -> "Restoring saved progress: ${java.text.NumberFormat.getIntegerInstance().format(trustedPieces.cardinality())} cached pieces; checking remaining records…"
            else -> "Checking downloaded pieces…"
        })
        DiagnosticLog.record("torrent.resume.startup", "hasExistingData=$hasExistingData availablePieces=${verifiedPieces.cardinality()} trustedPieces=${trustedPieces.cardinality()} unreceivedPieces=unknown")
        var transferFinished = false

        fun pieceBytesInSelectedFile(index: Int): Long {
            val start = index.toLong() * torrent.chunkSize
            return (minOf(fileGlobalStart + file.size, start + torrent.chunkSize) -
                maxOf(fileGlobalStart, start)).coerceAtLeast(0L)
        }
        var verifiedDestinationBytes = 0L
        var initialPiece = verifiedPieces.nextSetBit(0)
        while (initialPiece >= 0) {
            verifiedDestinationBytes += pieceBytesInSelectedFile(initialPiece)
            initialPiece = verifiedPieces.nextSetBit(initialPiece + 1)
        }
        fun setPieceVerified(index: Int, valid: Boolean) {
            if (verifiedPieces.get(index) != valid) {
                verifiedDestinationBytes += pieceBytesInSelectedFile(index) * if (valid) 1 else -1
                verifiedPieces.set(index, valid)
            }
        }
        fun reportRemainingBytes() = onBytesRemaining((file.size - verifiedDestinationBytes).coerceAtLeast(0L))
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val written = java.util.concurrent.atomic.AtomicLong(0L)
        fun writeDestination(block: () -> Unit) {
            try { lifecycle.write(block) }
            catch (error: Exception) {
                // Bt otherwise retries failed blocks indefinitely, hiding the
                // storage failure under thousands of identical worker errors.
                if (!lifecycle.isClosing() && !isCancelled() && failure.compareAndSet(null, error)) {
                    lifecycle.closeWrites()
                    DiagnosticLog.record("torrent.destination.write-failed", "Partial data and checkpoints retained", error)
                    completed.countDown()
                }
                throw error
            }
        }
        val lastStorageActivity = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val checkedBytes = java.util.concurrent.atomic.AtomicLong()
        val receivedCache = TorrentWriteCache()
        val destinationReadBytes = java.util.concurrent.atomic.AtomicLong()
        val boundaryMemory = java.util.concurrent.atomic.AtomicLong()
        val boundaryUnits = java.util.concurrent.CopyOnWriteArrayList<TorrentBoundaryStorageUnit>()
        val selectedPieceStart = (fileGlobalStart / torrent.chunkSize) * torrent.chunkSize
        val selectedEnd = fileGlobalStart + file.size
        val selectedPieceEnd = minOf(torrent.size,
            selectedEnd + (torrent.chunkSize - selectedEnd % torrent.chunkSize) % torrent.chunkSize)
        val destinationStorage = object : Storage {
            override fun getUnit(torrent: Torrent, candidate: TorrentFile): StorageUnit =
                if (candidate.pathElements != file.pathElements) {
                    val index = torrent.files.indexOfFirst { it.pathElements == candidate.pathElements }
                    val start = torrent.files.take(index.coerceAtLeast(0)).sumOf { it.size }
                    val keepStart = (selectedPieceStart - start).coerceIn(0L, candidate.size)
                    val keepEnd = (selectedPieceEnd - start).coerceIn(0L, candidate.size)
                    DiagnosticLog.record("torrent.destination.skipped-file", "bytes=${candidate.size} boundaryBytes=${(keepEnd - keepStart).coerceAtLeast(0L)}")
                    TorrentBoundaryStorageUnit(candidate.size, keepStart, keepEnd, boundaryMemory).also { boundaryUnits.add(it) }
                }
                else object : StorageUnit {
                    // Do not advertise the pre-sized destination as reusable torrent
                    // data. That would make Bt scan gigabytes over SFTP at startup.
                    // Only bytes received in this session may be read back for hashes.
                    private val writtenRanges = java.util.TreeMap<Long, Long>()
                    private var readAheadStart = -1L
                    private var readAhead = ByteArray(0)

                    private fun recordWrittenRange(offset: Long, length: Int) {
                        if (length <= 0) return
                        synchronized(writtenRanges) {
                            var start = offset
                            var end = offset + length
                            val previous = writtenRanges.floorEntry(start)
                            if (previous != null && previous.value >= start) {
                                start = previous.key
                                end = maxOf(end, previous.value)
                                writtenRanges.remove(previous.key)
                            }
                            var next = writtenRanges.ceilingEntry(start)
                            while (next != null && next.key <= end) {
                                end = maxOf(end, next.value)
                                writtenRanges.remove(next.key)
                                next = writtenRanges.ceilingEntry(start)
                            }
                            writtenRanges[start] = end
                        }
                    }

                    private fun readableRangeLength(offset: Long, requested: Int): Int =
                        synchronized(writtenRanges) {
                            val range = writtenRanges.floorEntry(offset)
                            if (range == null || range.value <= offset) 0
                            else minOf(requested.toLong(), range.value - offset).toInt()
                        }

                    init {
                        var piece = verifiedPieces.nextSetBit(0)
                        while (piece >= 0) {
                            val pieceStart = piece.toLong() * torrent.chunkSize
                            val localStart = maxOf(0L, pieceStart - fileGlobalStart)
                            val localEnd = minOf(file.size, pieceStart + torrent.chunkSize - fileGlobalStart)
                            if (localEnd > localStart) recordWrittenRange(localStart, (localEnd - localStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            piece = verifiedPieces.nextSetBit(piece + 1)
                        }
                    }

                    // Bt still performs its normal piece hash check, but newly received
                    // bytes come from bounded write-through memory instead of a remote
                    // reread. Previous-session/evicted bytes fall back to the destination.
                    override fun readBlock(buffer: ByteBuffer, offset: Long): Int {
                        val requested = buffer.remaining()
                        if (requested == 0) return 0
                        if (offset < 0L || offset >= file.size) return -1
                        val count = minOf(
                            minOf(requested.toLong(), file.size - offset).toInt(),
                            readableRangeLength(offset, requested)
                        )
                        if (count <= 0) return -1
                        synchronized(targetIoLock) {
                            val cached = receivedCache.read(buffer, offset, count)
                            if (cached > 0) {
                                lastStorageActivity.set(System.nanoTime())
                                checkedBytes.addAndGet(cached.toLong())
                                return cached
                            }
                            if (offset < readAheadStart || offset + count > readAheadStart + readAhead.size) {
                                val aheadCount = minOf(1024 * 1024, readableRangeLength(offset, 1024 * 1024), (file.size - offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                val bytes = ByteArray(aheadCount)
                                var total = 0
                                while (total < bytes.size) {
                                    if (isCancelled()) throw java.io.InterruptedIOException("Torrent verification cancelled")
                                    val read = target.readAt(offset + total, bytes, total, bytes.size - total)
                                    if (read <= 0) break
                                    total += read
                                    destinationReadBytes.addAndGet(read.toLong())
                                    lastStorageActivity.set(System.nanoTime())
                                }
                                readAheadStart = offset
                                readAhead = bytes.copyOf(total)
                            }
                            val available = minOf(count, (readAheadStart + readAhead.size - offset).toInt())
                            if (available <= 0) return -1
                            buffer.put(readAhead, (offset - readAheadStart).toInt(), available)
                            lastStorageActivity.set(System.nanoTime())
                            checkedBytes.addAndGet(available.toLong())
                            return available
                        }
                    }
                    override fun writeBlock(buffer: ByteBuffer, offset: Long): Int {
                        if (isCancelled()) throw java.io.InterruptedIOException("Torrent transfer cancelled")
                        val count = buffer.remaining()
                        if (count == 0) return 0
                        if (written.get() == 0L) DiagnosticLog.record("torrent.destination.first-block", "offset=$offset bytes=$count")
                        val bytes = ByteArray(count)
                        buffer.get(bytes)
                        writeDestination {
                            checkpoint.beforeWrite(offset, count)
                            val firstPiece = ((fileGlobalStart + offset) / torrent.chunkSize).toInt()
                            val lastPiece = ((fileGlobalStart + offset + count - 1) / torrent.chunkSize).toInt()
                            for (piece in firstPiece..lastPiece) setPieceVerified(piece, false)
                            target.writeAt(offset, bytes)
                            receivedCache.put(offset, bytes)
                            readAheadStart = -1L
                            readAhead = ByteArray(0)
                        }
                        lastStorageActivity.set(System.nanoTime())
                        recordWrittenRange(offset, count)
                        val total = written.addAndGet(count.toLong())
                        onBytesWritten(count.toLong())
                        if (total % (8L * 1024 * 1024) < count) {
                            DiagnosticLog.record("torrent.destination.progress", "bytes=$total expected=${file.size}")
                        }
                        return count
                    }
                    override fun writeBlock(buffer: ByteBufferView, offset: Long): Int {
                        if (isCancelled()) throw java.io.InterruptedIOException("Torrent transfer cancelled")
                        val count = buffer.remaining()
                        if (count == 0) return 0
                        if (written.get() == 0L) DiagnosticLog.record("torrent.destination.first-block", "offset=$offset bytes=$count")
                        val bytes = ByteArray(count)
                        buffer.get(bytes)
                        writeDestination {
                            checkpoint.beforeWrite(offset, count)
                            val firstPiece = ((fileGlobalStart + offset) / torrent.chunkSize).toInt()
                            val lastPiece = ((fileGlobalStart + offset + count - 1) / torrent.chunkSize).toInt()
                            for (piece in firstPiece..lastPiece) setPieceVerified(piece, false)
                            target.writeAt(offset, bytes)
                            receivedCache.put(offset, bytes)
                            readAheadStart = -1L
                            readAhead = ByteArray(0)
                        }
                        lastStorageActivity.set(System.nanoTime())
                        recordWrittenRange(offset, count)
                        val total = written.addAndGet(count.toLong())
                        onBytesWritten(count.toLong())
                        if (total % (8L * 1024 * 1024) < count) {
                            DiagnosticLog.record("torrent.destination.progress", "bytes=$total expected=${file.size}")
                        }
                        return count
                    }
                    override fun capacity() = file.size
                    override fun size() = file.size
                    override fun close() = Unit
                }
            override fun flush() = target.flush()
        }
        val totalPieceCount = torrent.chunkHashes.count()
        var lastStartupReport = 0L
        val verificationReady = java.util.concurrent.atomic.AtomicBoolean(false)
        val trackerNextAnnounce = ConcurrentHashMap<String, Long>()
        if (!hasExistingData) {
            verificationReady.set(true)
            reportRemainingBytes()
        }
        val availablePieces = verifiedPieces.clone() as java.util.BitSet
        val runtime = newTorrentRuntime(trustedPieces, availablePieces) { index, valid ->
            synchronized(targetIoLock) {
                if (lifecycle.isClosing() || isCancelled()) throw java.io.InterruptedIOException("Torrent verification paused")
                checkpoint.checked(index, valid)
                setPieceVerified(index, valid)
                if (index + 1 == totalPieceCount) {
                    reportRemainingBytes()
                    verificationReady.set(true)
                }
                lastStorageActivity.set(System.nanoTime())
                if (index + 1 == totalPieceCount || System.nanoTime() - lastStartupReport >= TimeUnit.MILLISECONDS.toNanos(250)) {
                    lastStartupReport = System.nanoTime()
                    if (hasExistingData) onPieceVerification(index + 1, totalPieceCount)
                }
                if ((index + 1) % 32 == 0 || index + 1 == totalPieceCount)
                    DiagnosticLog.record(if (hasExistingData) "torrent.resume.checking" else "torrent.resume.empty-records",
                        "checkedPieces=${index + 1} totalPieces=$totalPieceCount trustedPieces=${trustedPieces.cardinality()} savedPieces=${checkpoint.savedPieceCount} checkpointSaves=${checkpoint.persistenceCount} checkpointSaveMs=${checkpoint.persistenceMillis}")
            }
        }
        monitorPeerEvents(runtime, torrent, "direct-transfer")
        if (hasExistingData) onPieceVerification(0, totalPieceCount)
        runtime.eventSource.onPieceVerified(torrent.torrentId) { event ->
            synchronized(targetIoLock) {
                if (lifecycle.isClosing()) return@onPieceVerified
                if (!verifiedPieces.get(event.pieceIndex)) {
                    setPieceVerified(event.pieceIndex, true)
                    saveVerifiedPieces(resumeFile, torrent, file, verifiedPieces)
                    if (verifiedPieces.cardinality() % 32 == 0) {
                        DiagnosticLog.record("torrent.resume.piece", "verified=${verifiedPieces.cardinality()} total=$totalPieceCount")
                    }
                }
                reportRemainingBytes()
                checkpoint.pieceVerified(event.pieceIndex)
                // This is live download validation, not a startup verification phase.
                // Overall download progress is reported through remaining/received bytes.
            }
        }
        val clientBuilder = Bt.client(runtime).storage(destinationStorage)
        configureTorrentSource(clientBuilder, magnetSource)
        val client = clientBuilder
            .fileSelector(FilePrioritySkipSelector { candidate ->
                if (candidate.pathElements == file.pathElements) FilePriority.NORMAL_PRIORITY else FilePriority.SKIP
            })
            .afterFileDownloaded(FileDownloadCompleteCallback { _, candidate, _ ->
                if (candidate.pathElements == file.pathElements) completed.countDown()
            })
            .stopWhenDownloaded()
            .build()
        try {
            DiagnosticLog.record("torrent.destination.client-start", "selected=${file.pathElements.joinToString("/")}")
            val peerActivity = runtime.service(TorrentPeerActivity::class.java)
            var sampledAt = System.nanoTime()
            var previousDownloaded = 0L
            var previousUploaded = 0L
            val running = startTorrentClient(client, "direct-transfer") { state ->
                val now = System.nanoTime()
                val elapsed = (now - sampledAt).coerceAtLeast(1L)
                val downloaded = state.downloaded.coerceAtLeast(0L)
                val uploaded = state.uploaded.coerceAtLeast(0L)
                fun rate(delta: Long) = (delta.coerceAtLeast(0L).toDouble() * 1_000_000_000 / elapsed).toLong()
                onTorrentStats(TorrentTransferStats(state.connectedPeers.size,
                    peerActivity.servingPeers(torrent.torrentId, state.connectedPeers),
                    rate(downloaded - previousDownloaded), rate(uploaded - previousUploaded), uploaded,
                    transferPreferences.getBoolean(TorrentTransferPreferences.WAIT_FOREVER, false)))
                sampledAt = now; previousDownloaded = downloaded; previousUploaded = uploaded
            }
            DiagnosticLog.record("torrent.destination.client-started", "done=${running.isDone}")
            running.whenComplete { _, error ->
            if (error != null || completed.count > 0L) {
                failure.compareAndSet(null, error ?: IOException("Torrent engine stopped before the selected file completed; see torrent processing diagnostics"))
                completed.countDown()
            }
        }
            var lastProgress = System.nanoTime()
            var lastLoggedBytes = 0L
            var lastTrackerRefresh = 0L
            while (!completed.await(250, TimeUnit.MILLISECONDS)) {
                if (isCancelled() || Thread.currentThread().isInterrupted) {
                    stopTorrentClient(client, runtime)
                    throw java.io.InterruptedIOException("Torrent transfer cancelled")
                }
                val indefinitePeerWait = verificationReady.get() &&
                    transferPreferences.getBoolean(TorrentTransferPreferences.WAIT_FOREVER, false)
                if (!indefinitePeerWait && System.nanoTime() - maxOf(lastProgress, lastStorageActivity.get()) >= TimeUnit.MINUTES.toNanos(DOWNLOAD_TIMEOUT_MINUTES)) {
                    stopTorrentClient(client, runtime)
                    throw IOException("Torrent download timed out before the file was complete")
                }
                val now = System.nanoTime()
                if (verificationReady.get() && lastTrackerRefresh == 0L) {
                    synchronized(targetIoLock) {
                        try { checkpoint.save() }
                        catch (error: Exception) { DiagnosticLog.record("torrent.checkpoint.post-verification-save-failed", "Will recheck if needed", error) }
                        DiagnosticLog.record("torrent.resume.ready", "verifiedPieces=${verifiedPieces.cardinality()} totalPieces=$totalPieceCount")
                    }
                    probeTrackers(runtime, torrent, if (hasExistingData) "direct-transfer-after-verification" else "direct-transfer-new", trackerNextAnnounce)
                    lastTrackerRefresh = now
                } else if (verificationReady.get() && now - lastTrackerRefresh >= TimeUnit.MINUTES.toNanos(1) &&
                    now - maxOf(lastProgress, lastStorageActivity.get()) >= TimeUnit.SECONDS.toNanos(30)) {
                    DiagnosticLog.record("torrent.peer.refresh", "reason=no-data-after-verification")
                    probeTrackers(runtime, torrent, "direct-transfer-stalled", trackerNextAnnounce)
                    lastTrackerRefresh = now
                }
                val currentBytes = written.get()
                if (currentBytes != lastLoggedBytes) {
                    lastLoggedBytes = currentBytes
                    lastProgress = System.nanoTime()
                } else if (!indefinitePeerWait && currentBytes == 0L && System.nanoTime() - maxOf(lastProgress, lastStorageActivity.get()) >= TimeUnit.MINUTES.toNanos(NO_DATA_TIMEOUT_MINUTES)) {
                    stopTorrentClient(client, runtime)
                    throw IOException("No torrent data has arrived for ${NO_DATA_TIMEOUT_MINUTES} minutes. The swarm may have no reachable peers.")
                }
            }
            failure.get()?.let { throw IOException("Torrent transfer failed: ${it.cause?.message ?: it.message}", it) }
            target.flush()
            DiagnosticLog.record("torrent.destination.complete", "bytes=${written.get()} expected=${file.size}")
            transferFinished = true
            ProviderResult.Success(file.size)
        } finally {
            DiagnosticLog.record("torrent.destination.client-stop", "bytes=${written.get()} verificationMemoryBytes=${receivedCache.hitsBytes} destinationReadBytes=${destinationReadBytes.get()}")
            try {
                lifecycle.finish(completed = transferFinished, stop = { stopTorrentClient(client, runtime) }, persist = {
                    target.flush()
                    // Publication/rename belongs to the caller. Keep the checkpoint
                    // even after all bytes arrive until the final file is published.
                    checkpoint.save()
                    DiagnosticLog.record(if (transferFinished) "torrent.checkpoint.awaiting-publication" else "torrent.checkpoint.paused",
                        "savedPieces=${checkpoint.savedPieceCount} reason=${if (transferFinished) "download-verified" else if (isCancelled()) "finish-later" else "transfer-stopped"}")
                })
            } catch (error: Exception) {
                DiagnosticLog.record("torrent.checkpoint.final-save-failed", "Resume will recheck if needed", error)
            } finally {
                boundaryUnits.forEach { it.close() }
                try { checkpointStore?.close() }
                catch (error: Exception) { DiagnosticLog.record("torrent.checkpoint.close-failed", "Checkpoint device cleanup", error) }
            }
        }
    }

    private fun safeTorrentFile(root: File, torrent: Torrent, file: TorrentFile): File {
        val parts = file.pathElements.map { part ->
            part.replace(Regex("[\\u0000-\\u001f/\\\\]"), "_").trim().trimEnd('.', ' ')
                .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "_"
        }
        val path = (if (torrent.files.size > 1) listOf(torrent.name) else emptyList()) + parts
        val base = root.canonicalFile
        val result = path.fold(base) { current, segment -> File(current, segment) }.canonicalFile
        if (!result.toPath().startsWith(base.toPath())) throw IOException("Unsafe path in torrent metadata")
        result.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Unable to create torrent cache directory") }
        return result
    }

    /** Allocate the selected torrent file before peers start sending pieces. */
    private fun reserveDownloadSpace(file: File, size: Long, key: String) {
        if (size < 0L) throw IOException("Torrent reported an invalid file size")
        try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0L)
                try {
                    android.system.Os.posix_fallocate(raf.fd, 0L, size)
                } catch (error: android.system.ErrnoException) {
                    // Some cache filesystems do not implement fallocate. Preserve the
                    // logical size there; ENOSPC and other real allocation failures
                    // still abort before starting the network transfer.
                    if (error.errno !in setOf(
                            android.system.OsConstants.EOPNOTSUPP,
                            android.system.OsConstants.ENOSYS,
                            android.system.OsConstants.EINVAL
                        )) throw error
                    raf.setLength(size)
                }
                if (raf.length() != size) throw IOException("Could not reserve the expected torrent file size")
            }
            DiagnosticLog.record("torrent.cache.reserved", "hash=$key bytes=$size")
        } catch (error: Exception) {
            DiagnosticLog.record("torrent.cache.reserve-failed", "hash=$key bytes=$size", error)
            throw IOException("Could not reserve cache space for the torrent file: ${error.message}", error)
        }
    }

    override fun openSeekable(ref: ResourceRef, writable: Boolean) = ProviderResult.Unsupported(if (writable) ResourceCapability.SEEK_WRITE else ResourceCapability.SEEK)
    override fun openWrite(ref: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.WRITE)
    override fun createDirectory(parent: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
    override fun createFile(parent: ResourceRef, name: String, mimeType: String) = ProviderResult.Unsupported(ResourceCapability.CREATE)
    override fun rename(ref: ResourceRef, name: String) = ProviderResult.Unsupported(ResourceCapability.RENAME)
    override fun delete(ref: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.DELETE)
    override fun copy(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.COPY)
    override fun move(source: ResourceRef, destination: ResourceRef) = ProviderResult.Unsupported(ResourceCapability.MOVE)

    private inline fun <T> guarded(action: () -> ProviderResult<T>): ProviderResult<T> = try { action() }
        catch (e: Exception) {
            DiagnosticLog.record("torrent.operation.failed", e.message ?: e.javaClass.simpleName, e)
            ProviderResult.Failure(e.message ?: "Torrent operation failed", e)
        }

    private object DiscardStorage : Storage {
        override fun getUnit(torrent: Torrent, torrentFile: TorrentFile): StorageUnit = object : StorageUnit {
            override fun readBlock(buffer: ByteBuffer, offset: Long) = -1
            override fun writeBlock(buffer: ByteBuffer, offset: Long): Int { val n = buffer.remaining(); buffer.position(buffer.limit()); return n }
            override fun writeBlock(buffer: ByteBufferView, offset: Long): Int { val n = buffer.remaining(); buffer.position(buffer.limit()); return n }
            override fun capacity() = torrentFile.size
            override fun size() = 0L
            override fun close() = Unit
        }
        override fun flush() = Unit
    }

    companion object {
        private const val MAX_TORRENT_FILE_BYTES = 16 * 1024 * 1024
        private const val METADATA_TIMEOUT_MINUTES = 3L
        private const val DOWNLOAD_TIMEOUT_MINUTES = 30L
        private const val NO_DATA_TIMEOUT_MINUTES = 3L
        private const val SESSION_LOG_INTERVAL_MS = 10_000L
    }
}
