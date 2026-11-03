package com.infinifolder.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import com.infinifolder.archive.RecoveryCancellation
import com.infinifolder.storage.NetworkConnectionStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class SftpConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val initialPath: String = "/",
    val timeoutSeconds: Int = 15
)

class SftpClientPool : Closeable {

    private data class ActiveClient(
        val session: Session,
        var channel: ChannelSftp,
        val config: SftpConfig,
        val credentials: SftpCredentials,
        var lastUsedMillis: Long = System.currentTimeMillis()
    ) {
        val rangeLock = Any()
        var rangeChannel: ChannelSftp? = null
        val lifecycleLock = Any()
        var users = 0
        var retiring = false
        val streamLock = Any()
        val streams = CopyOnWriteArrayList<ChannelSftp>()
    }

    private val clients = mutableMapOf<String, ActiveClient>()
    private val retired = mutableListOf<ActiveClient>()
    private val connectedBefore = mutableSetOf<String>()
    private val lock = Any()
    // Channel.connect swallows InterruptedException while waiting for SSH's
    // confirmation. Keep that handshake off cancellable icon/transfer workers.
    private val channelOpens = java.util.concurrent.ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
        java.util.concurrent.ThreadFactory { Thread(it, "SftpChannelOpen").apply { isDaemon = true } })
        .apply { allowCoreThreadTimeOut(true) }

    private class ChannelOpenFailure(message: String, cause: Throwable) : IOException(message, cause)

    private fun disconnectChannel(channel: ChannelSftp?) {
        val interrupted = Thread.interrupted()
        try { channel?.disconnect() } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun <T> useClient(client: ActiveClient, work: () -> T): T {
        synchronized(client.lifecycleLock) {
            if (client.retiring) throw ChannelOpenFailure("SFTP session is reconnecting", IOException("Session retired"))
            client.users++
        }
        return try { work() } finally { synchronized(client.lifecycleLock) { client.users-- } }
    }

    private fun connectChannel(client: ActiveClient, config: SftpConfig, bulkRequests: Int): ChannelSftp {
        RecoveryCancellation.check()
        val opened = CompletableFuture<ChannelSftp>()
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds.toLong() + 5)
        try { channelOpens.execute {
            var channel: ChannelSftp? = null
            try {
                if (abandoned.get()) throw java.io.InterruptedIOException("SFTP channel request cancelled before setup")
                channel = client.session.openChannel("sftp") as ChannelSftp
                channel.setBulkRequests(bulkRequests)
                channel.connect(config.timeoutSeconds * 1000)
                opened.complete(channel)
            } catch (error: Exception) {
                val reason = channel?.exitStatus ?: -1
                runCatching { disconnectChannel(channel) }
                com.infinifolder.storage.DiagnosticLog.record("sftp.channel.open.failed",
                    "connection=${config.id} reason=$reason streams=${client.streams.count { it.isConnected }}", error)
                opened.completeExceptionally(ChannelOpenFailure(
                    "Cannot open SFTP channel (SSH reason $reason): ${error.message}", error))
            }
        } } catch (error: java.util.concurrent.RejectedExecutionException) {
            throw IOException("SFTP channel setup is busy; try again shortly", error)
        }
        try {
            while (true) {
                RecoveryCancellation.check()
                if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException("SFTP channel setup timed out")
                try { return opened.get(100, TimeUnit.MILLISECONDS).also { RecoveryCancellation.check() } }
                catch (_: java.util.concurrent.TimeoutException) { /* Keep cancellation responsive. */ }
            }
        } catch (error: Exception) {
            // A cancelled waiter must still close a channel confirmed later.
            // Do not cancel the handshake task: that can orphan a server channel.
            abandoned.set(true)
            opened.whenComplete { channel, _ -> disconnectChannel(channel) }
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("SFTP channel opening cancelled").apply { initCause(error) }
            }
            if (error is java.util.concurrent.ExecutionException) throw (error.cause as? Exception ?: error)
            throw error
        }
    }

    fun <T> withChannel(config: SftpConfig, credentials: SftpCredentials, block: (ChannelSftp) -> T): T {
        val client = getOrCreateClient(config, credentials)
        return withChannel(client, config, block)
    }

    /** Retry an idempotent, read-only operation once when JSch's session stream has gone stale. */
    fun <T> withReadChannel(config: SftpConfig, credentials: SftpCredentials, block: (ChannelSftp) -> T): T {
        val client = getOrCreateClient(config, credentials)
        fun read(owner: ActiveClient): T = withChannel(owner, config) { channel ->
            val value = block(channel)
            // Optional metadata probes must not hide cancellation and return a
            // channel with an interrupted request still pending to the pool.
            RecoveryCancellation.check()
            value
        }
        try {
            return read(client)
        } catch (first: Exception) {
            RecoveryCancellation.check()
            val replaceSession = isSessionUnavailable(client, first) || first is ChannelOpenFailure
            // JSch also reports an unexpected reply packet as an empty FAILURE.
            // withChannel has already discarded that metadata channel. Retry
            // only this read callback once, preserving active stream channels.
            val emptyFailure = first is SftpException && first.id == ChannelSftp.SSH_FX_FAILURE && first.message.isNullOrBlank()
            if (!replaceSession && !emptyFailure) throw first
            com.infinifolder.storage.DiagnosticLog.record(
                if (replaceSession) "sftp.session.reconnect" else "sftp.channel.read.retry",
                "connection=${config.id} operation=read-only cause=${first.javaClass.simpleName}" +
                    if (emptyFailure) " code=${ChannelSftp.SSH_FX_FAILURE} reason=empty-failure" else ""
            )
            val replacement = if (replaceSession) replaceStaleClient(config, credentials, client) else client
            try {
                return read(replacement)
            } catch (retry: Exception) {
                if (retry !== first) retry.addSuppressed(first)
                com.infinifolder.storage.DiagnosticLog.record(
                    if (replaceSession) "sftp.session.reconnect.failed" else "sftp.channel.read.retry.failed",
                    "connection=${config.id} operation=read-only", retry)
                if (retry is SftpException && retry.id == ChannelSftp.SSH_FX_FAILURE && retry.message.isNullOrBlank())
                    throw IOException("SFTP read failed after retry (code ${retry.id}; no error message)", retry)
                throw retry
            }
        }
    }

    private fun <T> withChannel(client: ActiveClient, config: SftpConfig, block: (ChannelSftp) -> T): T {
        return useClient(client) { synchronized(client) {
            try {
                RecoveryCancellation.check()
                if (!client.channel.isConnected) {
                    client.channel = connectChannel(client, config, 128)
                }
                client.lastUsedMillis = System.currentTimeMillis()
                block(client.channel)
            } catch (e: Exception) {
                // Interrupted requests can leave unread replies on an otherwise
                // connected channel. Replace it for the next operation, without
                // replaying this callback or disconnecting independent readers.
                disconnectChannel(client.channel)
                if (isSessionUnavailable(client, e)) discardClient(config.id, client)
                throw e
            }
        } }
    }

    /** Independent stream channels; the caller owns and disconnects the returned channel. */
    fun openReadChannel(config: SftpConfig, credentials: SftpCredentials, bulkRequests: Int = 128): ChannelSftp {
        val client = getOrCreateClient(config, credentials)
        try {
            return openReadChannel(client, config, bulkRequests)
        } catch (first: Exception) {
            RecoveryCancellation.check()
            if (!isSessionUnavailable(client, first) && first !is ChannelOpenFailure) throw first
            com.infinifolder.storage.DiagnosticLog.record(
                "sftp.session.reconnect",
                "connection=${config.id} operation=open-range-channel cause=${first.javaClass.simpleName}"
            )
            val replacement = replaceStaleClient(config, credentials, client)
            try {
                return openReadChannel(replacement, config, bulkRequests)
            } catch (retry: Exception) {
                if (retry !== first) retry.addSuppressed(first)
                com.infinifolder.storage.DiagnosticLog.record(
                    "sftp.session.reconnect.failed",
                    "connection=${config.id} operation=open-stream-channel", retry)
                throw retry
            }
        }
    }

    private fun openReadChannel(client: ActiveClient, config: SftpConfig, bulkRequests: Int): ChannelSftp {
        // Reserve capacity for metadata and range reads. Bound concurrent streams
        // instead of letting background decoder lanes exhaust server channels.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds.toLong())
        return useClient(client) { synchronized(client.streamLock) {
            while (true) {
                RecoveryCancellation.check()
                client.streams.removeAll { !it.isConnected }
                if (client.streams.size < 4) {
                    val channel = connectChannel(client, config, bulkRequests)
                    client.streams.add(channel)
                    return@synchronized channel
                }
                if (System.nanoTime() >= deadline) throw IOException("SFTP streams are busy; try again shortly")
                // Closing callers do not need this monitor. Recheck live channels
                // periodically so the public ChannelSftp ownership contract stays intact.
                try { (client.streamLock as java.lang.Object).wait(100) }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("Waiting for SFTP channel cancelled").apply { initCause(error) }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("SFTP channel unavailable")
        } }
    }

    /** One reusable range channel per session; idle block devices hold no channel. */
    internal fun <T> withRangeChannel(config: SftpConfig, credentials: SftpCredentials, block: (ChannelSftp) -> T): T {
        val client = getOrCreateClient(config, credentials)
        fun read(owner: ActiveClient): T = useClient(owner) { synchronized(owner.rangeLock) {
            RecoveryCancellation.check()
            try {
                val channel = owner.rangeChannel?.takeIf { it.isConnected }
                    ?: connectChannel(owner, config, 1).also { owner.rangeChannel = it }
                block(channel)
            } catch (error: Exception) {
                disconnectChannel(owner.rangeChannel)
                owner.rangeChannel = null
                throw error
            }
        } }
        try { return read(client) } catch (first: Exception) {
            RecoveryCancellation.check()
            // Retry only channel setup, before the range callback has executed.
            if (first !is ChannelOpenFailure) throw first
            com.infinifolder.storage.DiagnosticLog.record("sftp.session.reconnect",
                "connection=${config.id} operation=open-range-channel")
            return read(replaceStaleClient(config, credentials, client))
        }
    }

    private fun isSessionUnavailable(client: ActiveClient, error: Throwable): Boolean {
        if (!client.session.isConnected) return true
        return generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message.orEmpty()
            message.contains("session is down", ignoreCase = true) ||
                message.contains("session is not connected", ignoreCase = true) ||
                // JSch may leave Session.isConnected true after its shared input
                // stream has been closed. The next SFTP request then fails here.
                message.contains("inputstream is closed", ignoreCase = true)
        }
    }

    /** Replace only the failed pooled session; another thread may already have refreshed it. */
    private fun replaceStaleClient(config: SftpConfig, credentials: SftpCredentials,
        stale: ActiveClient): ActiveClient = NetworkConnectionStatus.reconnect(NetworkConnectionStatus.Key("sftp", config.id)) { synchronized(lock) {
        val current = clients[config.id]
        if (current !== stale && current?.session?.isConnected == true) return@synchronized current
        reapRetired()
        if (retired.count { it.config.id == config.id } >= 4)
            throw IOException("SFTP reconnect is waiting for existing streams to close")
        // A refused new channel need not mean existing transfers have failed.
        // Retire routing without disconnecting their live read/write channels.
        if (current != null) {
            clients.remove(config.id)
            synchronized(current.lifecycleLock) { current.retiring = true }
            if (current.session.isConnected) retired.add(current) else disconnectClient(current)
        }
        val fresh = createClient(config, credentials)
        clients[config.id] = fresh
        com.infinifolder.storage.DiagnosticLog.record("sftp.session.reconnected", "connection=${config.id}")
        fresh
    } }

    private fun discardClient(connectionId: String, stale: ActiveClient) {
        synchronized(lock) {
            if (clients[connectionId] === stale) clients.remove(connectionId)
            disconnectClient(stale)
        }
    }

    private fun disconnectClient(client: ActiveClient) {
        try { disconnectChannel(client.rangeChannel) } catch (_: Exception) {}
        client.streams.forEach { runCatching { disconnectChannel(it) } }
        try { disconnectChannel(client.channel) } catch (_: Exception) {}
        try { client.session.disconnect() } catch (_: Exception) {}
    }

    private fun getOrCreateClient(config: SftpConfig, credentials: SftpCredentials): ActiveClient {
        synchronized(lock) {
            reapRetired()
            val existing = clients[config.id]
            if (existing != null && existing.session.isConnected) {
                return existing
            }
            if (existing != null) {
                disconnect(config.id)
            }

            val fresh = if (config.id in connectedBefore)
                NetworkConnectionStatus.reconnect(NetworkConnectionStatus.Key("sftp", config.id)) { createClient(config, credentials) }
                else createClient(config, credentials)
            clients[config.id] = fresh
            connectedBefore.add(config.id)
            return fresh
        }
    }

    // Called under the pool lock. A retired range call finishes on its old session;
    // independent streams retain theirs until their normal close/cancellation.
    private fun reapRetired() {
        val ready = retired.filter { synchronized(it.lifecycleLock) {
            it.users == 0 && it.streams.none { channel -> channel.isConnected }
        } }
        retired.removeAll(ready.toSet())
        ready.forEach(::disconnectClient)
    }

    private fun createClient(config: SftpConfig, credentials: SftpCredentials): ActiveClient {
        val jsch = JSch()

        if (credentials.authType == SftpAuthType.KEY_CERT) {
            val prvKeyBytes = credentials.privateKey?.trim()?.toByteArray(StandardCharsets.UTF_8)
                ?: throw IOException("Private key required for certificate/key authentication")
            val certBytes = credentials.certificate?.trim()?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)
            val passBytes = credentials.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)

            jsch.addIdentity("sftp_${config.id}", prvKeyBytes, certBytes, passBytes)
        }

        val session = jsch.getSession(config.username, config.host, config.port)
        if (credentials.authType == SftpAuthType.PASSWORD) {
            session.setPassword(credentials.password ?: "")
        }

        // Custom SocketFactory with TCP_NODELAY and 1MB socket send/receive buffers
        val socketFactory = object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String, port: Int): java.net.Socket {
                val socket = java.net.Socket()
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 1024 * 1024
                socket.sendBufferSize = 1024 * 1024
                socket.connect(java.net.InetSocketAddress(host, port), config.timeoutSeconds * 1000)
                return socket
            }

            override fun getInputStream(socket: java.net.Socket): InputStream = socket.getInputStream()

            override fun getOutputStream(socket: java.net.Socket): OutputStream = socket.getOutputStream()
        }
        session.setSocketFactory(socketFactory)

        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig(
            "PreferredAuthentications",
            if (credentials.authType == SftpAuthType.PASSWORD) {
                "password,keyboard-interactive"
            } else {
                "publickey,keyboard-interactive,password"
            }
        )

        // Prioritize hardware-accelerated ciphers (GCM / CTR) on ARM/x86 processors
        val defaultCiphers = JSch.getConfig("cipher.s2c") ?: ""
        val fastCiphers = listOf(
            "aes128-gcm@openssh.com",
            "aes256-gcm@openssh.com",
            "chacha20-poly1305@openssh.com",
            "aes128-ctr",
            "aes192-ctr",
            "aes256-ctr"
        )
        val preferredCiphers = (fastCiphers + defaultCiphers.split(",")).distinct().filter { it.isNotBlank() }.joinToString(",")
        session.setConfig("cipher.s2c", preferredCiphers)
        session.setConfig("cipher.c2s", preferredCiphers)
        session.setConfig("compression.s2c", "none")
        session.setConfig("compression.c2s", "none")

        session.timeout = config.timeoutSeconds * 1000
        session.setServerAliveInterval(30_000)
        try {
            session.connect()
            val channel = session.openChannel("sftp") as ChannelSftp
            try {
                channel.setBulkRequests(128)
                channel.connect(config.timeoutSeconds * 1000)
            } catch (e: Exception) {
                channel.disconnect()
                throw e
            }
            return ActiveClient(session, channel, config, credentials)
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }

    fun testConnection(config: SftpConfig, credentials: SftpCredentials): Result<String> {
        return try {
            val client = createClient(config, credentials)
            val pwd = client.channel.pwd()
            val serverVersion = client.session.serverVersion ?: "SSH-2.0"
            client.channel.disconnect()
            client.session.disconnect()
            Result.success("Connected successfully!\nServer: $serverVersion\nWorking directory: $pwd")
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun openStream(config: SftpConfig, credentials: SftpCredentials, remotePath: String): InputStream {
        val channel = openReadChannel(config, credentials)
        val guard = SftpIoGuard(config.timeoutSeconds) { disconnectChannel(channel) }
        val sftpStream = try { guard.call("open read") { channel.get(remotePath, guard.monitor) } } catch (e: Exception) {
            guard.close(); disconnectChannel(channel)
            throw e
        }
        val buffered = java.io.BufferedInputStream(sftpStream, 128 * 1024)
        return object : InputStream() {
            private var closed = false

            override fun read(): Int = guard.call("read") { buffered.read() }

            override fun read(b: ByteArray): Int = guard.call("read") { buffered.read(b, 0, b.size) }

            override fun read(b: ByteArray, off: Int, len: Int): Int = guard.call("read") { buffered.read(b, off, len) }

            override fun skip(n: Long): Long = guard.call("skip") { buffered.skip(n) }

            override fun available(): Int = buffered.available()

            override fun close() {
                if (!closed) {
                    closed = true
                    try { RecoveryCancellation.withoutCancellation { guard.call("close read") { buffered.close() } } } finally { guard.close(); disconnectChannel(channel) }
                }
            }
        }
    }

    fun openWriteStream(config: SftpConfig, credentials: SftpCredentials, remotePath: String): OutputStream {
        val channel = openReadChannel(config, credentials)
        val guard = SftpIoGuard(config.timeoutSeconds) { disconnectChannel(channel) }
        val sftpOut = try { guard.call("open write") { channel.put(remotePath, guard.monitor, ChannelSftp.OVERWRITE) } } catch (e: Exception) {
            guard.close(); disconnectChannel(channel)
            throw e
        }
        val buffered = java.io.BufferedOutputStream(sftpOut, 128 * 1024)
        return object : OutputStream() {
            private var closed = false

            override fun write(b: Int) {
                guard.call("write") { buffered.write(b) }
            }

            override fun write(b: ByteArray) {
                guard.call("write") { buffered.write(b, 0, b.size) }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                guard.call("write") { buffered.write(b, off, len) }
            }

            override fun flush() {
                guard.call("flush write") { buffered.flush() }
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    // close flushes buffered bytes and waits for SFTP completion; failures must escape.
                    try { RecoveryCancellation.withoutCancellation { guard.call("close write") { buffered.close() } } } finally { guard.close(); disconnectChannel(channel) }
                }
            }
        }
    }

    fun disconnect(connectionId: String) {
        synchronized(lock) {
            clients.remove(connectionId)?.let(::disconnectClient)
            retired.filter { it.config.id == connectionId }.also { retired.removeAll(it.toSet()) }
                .forEach(::disconnectClient)
        }
    }

    override fun close() {
        synchronized(lock) {
            clients.values.forEach(::disconnectClient)
            clients.clear()
            retired.forEach(::disconnectClient)
            retired.clear()
        }
    }
}
