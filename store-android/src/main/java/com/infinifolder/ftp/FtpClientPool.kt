package com.infinifolder.ftp

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

fun FTPClient.safeReplyString(): String {
    val reply = runCatching { replyString?.trim()?.ifEmpty { null } }.getOrNull()
    val code = runCatching { replyCode }.getOrDefault(0)
    return when {
        reply != null && code > 0 -> "$reply (code: $code)"
        reply != null -> reply
        code > 0 -> "Reply code $code"
        else -> "No reply from server"
    }
}

class FtpClientPool(
    private val connectTimeoutMs: Int = 10_000,
    private val dataTimeoutMs: Int = 30_000
) : AutoCloseable {

    private val activeClients = ConcurrentHashMap<String, FTPClient>()

    private fun createClient(config: FtpConfig): FTPClient {
        val client = if (config.useFtps) {
            val isImplicit = config.port == 990
            FTPSClient(isImplicit).apply {
                trustManager = TrustManagerUtils.getAcceptAllTrustManager()
            }
        } else {
            FTPClient()
        }
        client.connectTimeout = connectTimeoutMs
        client.defaultTimeout = dataTimeoutMs
        client.controlEncoding = config.encoding
        client.autodetectUTF8 = true
        client.listHiddenFiles = true
        return client
    }

    private fun connectAndLogin(client: FTPClient, config: FtpConfig, creds: FtpCredentials) {
        client.connect(config.host, config.port)
        val reply = client.replyCode
        if (!FTPReply.isPositiveCompletion(reply)) {
            val replyMsg = client.safeReplyString()
            try { client.disconnect() } catch (_: Throwable) {}
            throw IOException("FTP server refused connection: $replyMsg")
        }
        client.soTimeout = dataTimeoutMs

        if (config.useFtps && client is FTPSClient) {
            try {
                client.execPBSZ(0)
                client.execPROT("P")
            } catch (_: Throwable) {}
        }

        val username = if (config.isAnonymous) "anonymous" else config.username
        val password = if (config.isAnonymous) "infinifolder@local" else (creds.password ?: "")

        val loginOk = try {
            client.login(username, password)
        } catch (e: Throwable) {
            val replyMsg = client.safeReplyString()
            try { client.disconnect() } catch (_: Throwable) {}
            throw IOException("FTP login error for '$username': ${e.message} ($replyMsg)", e)
        }

        if (!loginOk) {
            val replyMsg = client.safeReplyString()
            val code = client.replyCode
            try { client.disconnect() } catch (_: Throwable) {}
            val hint = if (config.isAnonymous) {
                " (anonymous login may be disabled on this server; try editing the connection, unchecking Anonymous, and entering credentials)"
            } else ""
            throw IOException("FTP login failed for user '$username': $replyMsg$hint")
        }

        if (config.useFtps && client is FTPSClient) {
            try {
                client.execPBSZ(0)
                client.execPROT("P")
            } catch (_: Throwable) {}
        }

        if (config.passiveMode) {
            client.enterLocalPassiveMode()
        } else {
            client.enterLocalActiveMode()
        }
        client.setFileType(FTP.BINARY_FILE_TYPE)
    }

    @Synchronized
    fun getClient(config: FtpConfig, creds: FtpCredentials): FTPClient {
        val existing = activeClients[config.id]
        if (existing != null && existing.isConnected && existing.isAvailable) {
            try {
                if (existing.sendNoOp()) {
                    return existing
                }
            } catch (_: Throwable) {
                // Stale connection, reconnect below
            }
            try { existing.disconnect() } catch (_: Throwable) {}
        }

        val freshClient = createClient(config)
        connectAndLogin(freshClient, config, creds)
        activeClients[config.id] = freshClient
        return freshClient
    }

    fun <T> withClient(config: FtpConfig, creds: FtpCredentials, block: (FTPClient) -> T): T {
        val client = getClient(config, creds)
        return try {
            synchronized(client) { block(client) }
        } catch (e: Throwable) {
            // If the socket was dropped, invalidate cached client so next attempt reconnects cleanly
            disconnect(config.id)
            throw e
        }
    }

    fun testConnection(config: FtpConfig, creds: FtpCredentials): Result<String> = try {
        val client = createClient(config)
        try {
            connectAndLogin(client, config, creds)
            val sysType = try { client.systemType } catch (_: Throwable) { null }
            val info = StringBuilder("Connected successfully to FTP server at ${config.host}:${config.port}!")
            if (!sysType.isNullOrBlank()) {
                info.append("\nRemote system: $sysType")
            }
            val files = try { client.listFiles() } catch (_: Throwable) { null }
            if (files != null) {
                val count = files.count { it != null && it.name != "." && it.name != ".." }
                info.append("\nListing verified ($count item(s) found).")
            }
            Result.success(info.toString())
        } finally {
            try {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            } catch (_: Throwable) {}
        }
    } catch (e: Throwable) {
        Result.failure(e)
    }

    fun disconnect(connectionId: String) {
        activeClients.remove(connectionId)?.let { client ->
            try {
                if (client.isConnected) {
                    client.disconnect()
                }
            } catch (_: Throwable) {}
        }
    }

    override fun close() {
        activeClients.keys().toList().forEach { disconnect(it) }
    }
}
