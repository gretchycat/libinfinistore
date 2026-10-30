package com.infinifolder.torrent

import bt.net.InetPeer
import bt.net.Peer
import bt.runtime.Config
import bt.service.IdentityService
import bt.torrent.TorrentRegistry
import bt.tracker.*
import bt.metainfo.TorrentId
import com.infinifolder.storage.DiagnosticLog
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class DualStackUdpTrackerFactory(
    private val config: Config, private val identity: IdentityService, private val registry: TorrentRegistry
) : TrackerFactory {
    private val trackers = ConcurrentHashMap.newKeySet<DualStackUdpTracker>()
    private val random = SecureRandom()
    private val announceKey = random.nextInt()
    override fun getTracker(url: String): Tracker =
        DualStackUdpTracker(URI(url), config, identity, registry, random, announceKey).also { trackers.add(it) }
    fun close() { trackers.forEach { it.close() }; trackers.clear() }
}

private class DualStackUdpTracker(
    private val endpoint: URI, private val config: Config, private val identity: IdentityService,
    private val registry: TorrentRegistry, private val random: SecureRandom, private val announceKey: Int
) : Tracker {
    @Volatile private var closed = false
    private val sockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
    init { require(endpoint.host != null && endpoint.port in 1..65535) { "Invalid UDP tracker endpoint" } }

    override fun request(torrentId: TorrentId): TrackerRequestBuilder = object : TrackerRequestBuilder(torrentId) {
        override fun start() = announce(torrentId, 2, numWant)
        override fun stop() = announce(torrentId, 3, numWant)
        override fun complete() = announce(torrentId, 1, numWant)
        override fun query() = announce(torrentId, 0, numWant)
    }

    private fun announce(id: TorrentId, event: Int, requested: Int?): TrackerResponse {
        if (closed) return TrackerResponse.failure("UDP tracker is closed")
        val started = System.nanoTime()
        val peers = LinkedHashMap<String, Peer>()
        var successful: TrackerResponse? = null
        var failure: Exception? = null
        try {
            val addresses = InetAddress.getAllByName(endpoint.host).distinct().sortedBy { it.address.size }
            // Query both families: successful IPv4 discovery must not suppress IPv6.
            val budget = config.trackerTimeout.toNanos().coerceAtLeast(TimeUnit.SECONDS.toNanos(1))
            val perAddress = (budget / addresses.size.coerceAtLeast(1)).coerceAtLeast(TimeUnit.MILLISECONDS.toNanos(250))
            for (address in addresses) {
                if (closed || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("UDP tracker stopped")
                val family = if (address.address.size == 16) "ipv6" else "ipv4"
                val deadline = minOf(started + budget, System.nanoTime() + perAddress)
                if (System.nanoTime() >= deadline) break
                try {
                    val result = queryAddress(address, id, event, requested, deadline)
                    result.peers.forEach { peer -> peers["${peer.inetAddress.hostAddress}:${peer.port}"] = peer }
                    successful = result
                    DiagnosticLog.record("torrent.tracker.udp.response", "host=${endpoint.host} family=$family hash=$id peers=${result.peers.count()} seeders=${result.seederCount} leechers=${result.leecherCount} interval=${result.interval}")
                } catch (error: Exception) {
                    failure = error
                    DiagnosticLog.record("torrent.tracker.udp.failed", "host=${endpoint.host} family=$family hash=$id cause=${error.javaClass.simpleName}")
                }
            }
        } catch (error: Exception) { failure = error }
        return successful?.also { it.peers = peers.values.toList() }
            ?: TrackerResponse.exceptional(failure ?: IOException("No UDP tracker address responded"))
    }

    private fun queryAddress(address: InetAddress, id: TorrentId, event: Int, requested: Int?, deadline: Long): TrackerResponse {
        // Explicit family matching avoids binding an IPv4 socket to an IPv6 tracker.
        val local = InetAddress.getByAddress(ByteArray(address.address.size))
        val socket = DatagramSocket(null)
        sockets.add(socket)
        try {
            if (closed) throw java.io.InterruptedIOException("UDP tracker stopped")
            socket.bind(InetSocketAddress(local, 0))
            socket.connect(InetSocketAddress(address, endpoint.port))
            val connectId = random.nextInt()
            val connect = ByteBuffer.allocate(16).putLong(0x41727101980L).putInt(0).putInt(connectId).array()
            val connected = exchange(socket, connect, 0, connectId, deadline)
            if (connected.remaining() < 8) throw IOException("Truncated UDP connect response")
            val connectionId = connected.long
            val session = registry.getDescriptor(id).orElse(null)?.sessionState?.orElse(null)
            val downloaded = session?.downloaded?.coerceAtLeast(0) ?: 0L
            // Before metadata exists the file size is unknown; do not advertise a seed.
            val left = if (registry.getTorrent(id).isPresent) session?.left?.takeIf { it >= 0 } ?: 1L else 1L
            val uploaded = session?.uploaded?.coerceAtLeast(0) ?: 0L
            val path = endpoint.rawPath.orEmpty() + (endpoint.rawQuery?.let { "?$it" } ?: "")
            val urlData = path.toByteArray(Charsets.UTF_8)
            if (urlData.size > 4096) throw IOException("UDP tracker URL is too long")
            val extra = urlData.size + 2 * ((urlData.size + 254) / 255)
            val transaction = random.nextInt()
            val request = ByteBuffer.allocate(98 + extra)
                .putLong(connectionId).putInt(1).putInt(transaction).put(id.bytes).put(identity.localPeerId.bytes)
                .putLong(downloaded).putLong(left).putLong(uploaded).putInt(event).putInt(0).putInt(announceKey)
                .putInt(requested ?: config.numberOfPeersToRequestFromTracker).putShort(config.acceptorPort.toShort())
            var offset = 0
            while (offset < urlData.size) {
                val count = minOf(255, urlData.size - offset)
                request.put(2).put(count.toByte()).put(urlData, offset, count)
                offset += count
            }
            val response = exchange(socket, request.array(), 1, transaction, deadline)
            if (response.remaining() < 12) throw IOException("Truncated UDP announce response")
            val result = TrackerResponse.ok()
            result.interval = response.int.coerceAtLeast(1)
            result.leecherCount = response.int.coerceAtLeast(0)
            result.seederCount = response.int.coerceAtLeast(0)
            val width = address.address.size
            val peers = ArrayList<Peer>()
            // BEP 15: six-byte entries for IPv4 transport, eighteen for IPv6.
            // Any incomplete trailing extension bytes are not treated as peers.
            while (response.remaining() >= width + 2) {
                val bytes = ByteArray(width).also { response.get(it) }
                val port = response.short.toInt() and 0xffff
                val peer = InetAddress.getByAddress(bytes)
                if (port != 0 && !peer.isAnyLocalAddress && !peer.isMulticastAddress)
                    peers.add(InetPeer.build(peer, port))
            }
            result.peers = peers
            return result
        } finally { sockets.remove(socket); socket.close() }
    }

    private fun exchange(socket: DatagramSocket, request: ByteArray, action: Int, transaction: Int, deadline: Long): ByteBuffer {
        socket.send(DatagramPacket(request, request.size))
        val bytes = ByteArray(65535)
        while (!closed && !Thread.currentThread().isInterrupted) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("UDP tracker response timeout")
            socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val packet = DatagramPacket(bytes, bytes.size)
            socket.receive(packet)
            if (packet.length < 8) continue
            val response = ByteBuffer.wrap(bytes, 0, packet.length)
            val receivedAction = response.int
            if (response.int != transaction) continue
            if (receivedAction == 3) {
                val message = ByteArray(response.remaining().coerceAtMost(512)).also { response.get(it) }
                throw IOException("UDP tracker rejected request: ${message.toString(Charsets.UTF_8)}")
            }
            if (receivedAction != action) throw IOException("Unexpected UDP tracker response action")
            return response
        }
        throw java.io.InterruptedIOException("UDP tracker stopped")
    }

    override fun close() { closed = true; sockets.forEach { it.close() }; sockets.clear() }
    override fun toString() = "DualStackUdpTracker{host=${endpoint.host}, port=${endpoint.port}}"
}
