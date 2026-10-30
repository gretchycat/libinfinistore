package com.infinifolder.smb

import android.content.Context
import android.net.wifi.WifiManager
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import java.io.IOException
import java.net.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredSmbServer(
    val host: String,
    val name: String,
    val port: Int = 445,
    val workgroup: String? = null,
    val source: String = "LAN"
)

class SmbDiscoveryService(private val context: Context? = null) {

    private val isRunning = AtomicBoolean(false)
    private var isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    fun discoverServers(
        onServerFound: (DiscoveredSmbServer) -> Unit,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
        onFinished: () -> Unit = {}
    ) {
        if (!isRunning.compareAndSet(false, true)) {
            return
        }
        isCancelled.set(false)

        val executor = Executors.newFixedThreadPool(32)
        val discovered = ConcurrentHashMap<String, DiscoveredSmbServer>()

        var multicastLock: WifiManager.MulticastLock? = null
        if (context != null) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("SmbDiscoveryLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            } catch (_: Throwable) {}
        }

        fun reportServer(server: DiscoveredSmbServer) {
            val existing = discovered[server.host]
            if (existing == null || (existing.name == existing.host && server.name != server.host)) {
                discovered[server.host] = server
                onServerFound(server)
            }
        }

        try {
            // 1. Launch WS-Discovery Probe on UDP 3702
            executor.execute {
                runWsDiscovery { s -> reportServer(s) }
            }

            // 2. Launch mDNS query for _smb._tcp.local on UDP 5353
            executor.execute {
                runMdnsDiscovery { s -> reportServer(s) }
            }

            // 3. Scan local subnets for TCP port 445 & 139
            val localSubnets = getLocalSubnetAddresses()
            val totalHosts = localSubnets.size
            val completedCount = AtomicInteger(0)

            for (targetIp in localSubnets) {
                if (isCancelled.get()) break
                executor.execute {
                    if (!isCancelled.get()) {
                        probeSmbPortAndResolve(targetIp) { s -> reportServer(s) }
                    }
                    val done = completedCount.incrementAndGet()
                    onProgress(done, totalHosts)
                }
            }

            executor.shutdown()
            try {
                executor.awaitTermination(6, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                executor.shutdownNow()
            }
        } finally {
            try {
                multicastLock?.release()
            } catch (_: Throwable) {}
            isRunning.set(false)
            onFinished()
        }
    }

    private fun probeSmbPortAndResolve(ip: String, onFound: (DiscoveredSmbServer) -> Unit) {
        var openPort: Int? = null
        // First check standard SMB port 445
        if (checkTcpPort(ip, 445, 450)) {
            openPort = 445
        } else if (checkTcpPort(ip, 139, 400)) { // Fallback to NetBIOS session port 139
            openPort = 139
        }

        if (openPort != null) {
            // Resolve host name and workgroup
            val (name, workgroup) = resolveHostAndWorkgroup(ip)
            val serverName = name.ifEmpty { "Samba Server ($ip)" }
            onFound(
                DiscoveredSmbServer(
                    host = ip,
                    name = serverName,
                    port = openPort,
                    workgroup = workgroup,
                    source = "Port $openPort"
                )
            )
        }
    }

    private fun checkTcpPort(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveHostAndWorkgroup(ip: String): Pair<String, String?> {
        var resolvedName: String? = null
        var resolvedWorkgroup: String? = null

        // Try NetBIOS node status query via jcifs-ng
        try {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.connTimeout", "1000")
                setProperty("jcifs.smb.client.soTimeout", "1500")
            }
            val baseContext = BaseContext(PropertyConfiguration(props))
            val nbtAddresses = baseContext.nameServiceClient.getNbtAllByAddress(ip)
            if (nbtAddresses != null) {
                for (nbt in nbtAddresses) {
                    val nbtName = nbt.name?.name?.trim() ?: continue
                    val isGroup = try { nbt.isGroupAddress(baseContext) } catch (_: Throwable) { false }
                    if (isGroup && resolvedWorkgroup == null && nbtName.isNotEmpty()) {
                        resolvedWorkgroup = nbtName
                    } else if (!isGroup && resolvedName == null && nbtName.isNotEmpty()) {
                        resolvedName = nbtName
                    }
                }
            }
        } catch (_: Throwable) {}

        // Fallback to DNS reverse lookup
        if (resolvedName.isNullOrEmpty()) {
            try {
                val inet = InetAddress.getByName(ip)
                val hostName = inet.hostName
                if (hostName != ip && hostName.isNotEmpty()) {
                    resolvedName = hostName.substringBefore('.')
                }
            } catch (_: Throwable) {}
        }

        return (resolvedName ?: ip) to resolvedWorkgroup
    }

    private fun runWsDiscovery(onFound: (DiscoveredSmbServer) -> Unit) {
        val probeUuid = UUID.randomUUID().toString()
        val probeXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                           xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                           xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery">
              <soap:Header>
                <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
                <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
                <wsa:MessageID>urn:uuid:$probeUuid</wsa:MessageID>
              </soap:Header>
              <soap:Body>
                <wsd:Probe>
                  <wsd:Types>wsdp:Device</wsd:Types>
                </wsd:Probe>
              </soap:Body>
            </soap:Envelope>
        """.trimIndent()

        try {
            val group = InetAddress.getByName("239.255.255.250")
            MulticastSocket().use { socket ->
                socket.soTimeout = 2500
                val data = probeXml.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(data, data.size, group, 3702)
                socket.send(packet)

                val buf = ByteArray(4096)
                val recvPacket = DatagramPacket(buf, buf.size)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < 2500 && !isCancelled.get()) {
                    try {
                        socket.receive(recvPacket)
                        val senderIp = recvPacket.address?.hostAddress ?: continue
                        val responseText = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                        val computerName = parseWsDiscoveryResponse(responseText) ?: senderIp

                        onFound(
                            DiscoveredSmbServer(
                                host = senderIp,
                                name = computerName,
                                port = 445,
                                source = "WS-Discovery"
                            )
                        )
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun parseWsDiscoveryResponse(xml: String): String? {
        val computerMatch = Regex("""<[^:>]+:Computer>([^<]+)</[^:>]+:Computer>""").find(xml)
        if (computerMatch != null) return computerMatch.groupValues[1].trim()

        val endpointMatch = Regex("""urn:uuid:([0-9a-fA-F-]+)""").find(xml)
        if (endpointMatch != null) {
            val nameMatch = Regex("""<[^:>]+:XAddrs>[^<]*//([^:/]+)""").find(xml)
            if (nameMatch != null) return nameMatch.groupValues[1].trim()
        }
        return null
    }

    private fun runMdnsDiscovery(onFound: (DiscoveredSmbServer) -> Unit) {
        // Query for _smb._tcp.local via mDNS on UDP 5353
        try {
            val group = InetAddress.getByName("224.0.0.251")
            MulticastSocket().use { socket ->
                socket.soTimeout = 2000
                val queryBytes = buildMdnsQuery("_smb._tcp.local")
                val packet = DatagramPacket(queryBytes, queryBytes.size, group, 5353)
                socket.send(packet)

                val buf = ByteArray(4096)
                val recvPacket = DatagramPacket(buf, buf.size)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < 2000 && !isCancelled.get()) {
                    try {
                        socket.receive(recvPacket)
                        val senderIp = recvPacket.address?.hostAddress ?: continue
                        val hostName = try {
                            val h = recvPacket.address.hostName
                            if (h != senderIp) h.substringBefore('.') else senderIp
                        } catch (_: Throwable) { senderIp }

                        onFound(
                            DiscoveredSmbServer(
                                host = senderIp,
                                name = hostName,
                                port = 445,
                                source = "mDNS"
                            )
                        )
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun buildMdnsQuery(serviceName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Transaction ID (0)
        out.write(0); out.write(0)
        // Flags: Standard query
        out.write(0); out.write(0)
        // Questions: 1
        out.write(0); out.write(1)
        // Answer RRs: 0, Authority RRs: 0, Additional RRs: 0
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write(0); out.write(0)

        // Labels for serviceName, e.g. "_smb", "_tcp", "local"
        for (part in serviceName.split('.')) {
            if (part.isNotEmpty()) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0) // Root label

        // Type PTR (12)
        out.write(0); out.write(12)
        // Class IN (1)
        out.write(0); out.write(1)

        return out.toByteArray()
    }

    private fun getLocalSubnetAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue

                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val prefix = ia.networkPrefixLength
                        if (prefix in 20..30) {
                            val ipBytes = addr.address
                            val ipInt = ((ipBytes[0].toInt() and 0xFF) shl 24) or
                                    ((ipBytes[1].toInt() and 0xFF) shl 16) or
                                    ((ipBytes[2].toInt() and 0xFF) shl 8) or
                                    (ipBytes[3].toInt() and 0xFF)

                            val mask = (-1 shl (32 - prefix))
                            val netInt = ipInt and mask
                            val hostCount = (1 shl (32 - prefix)) - 2

                            // For safety and speed, scan up to 254 hosts on /24 or /23
                            val scanLimit = hostCount.coerceAtMost(254)
                            for (i in 1..scanLimit) {
                                val targetInt = netInt + i
                                if (targetInt != ipInt) { // Don't scan ourselves
                                    val ipStr = "${(targetInt ushr 24) and 0xFF}." +
                                            "${(targetInt ushr 16) and 0xFF}." +
                                            "${(targetInt ushr 8) and 0xFF}." +
                                            "${targetInt and 0xFF}"
                                    addresses.add(ipStr)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return addresses
    }
}
