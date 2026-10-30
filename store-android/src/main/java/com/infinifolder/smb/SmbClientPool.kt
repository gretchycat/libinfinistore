package com.infinifolder.smb

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

class SmbClientPool(
    private val connectTimeoutMs: Int = 8_000,
    private val socketTimeoutMs: Int = 30_000
) : AutoCloseable {

    private val contextCache = ConcurrentHashMap<String, CIFSContext>()

    fun getContext(config: SmbConfig, creds: SmbCredentials): CIFSContext {
        return contextCache.computeIfAbsent(config.id) {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.connTimeout", connectTimeoutMs.toString())
                setProperty("jcifs.smb.client.soTimeout", socketTimeoutMs.toString())
                setProperty("jcifs.smb.client.responseTimeout", socketTimeoutMs.toString())
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
                setProperty("jcifs.smb.client.signingEnforced", "false")
                setProperty("jcifs.smb.client.signingPreferred", "false")
                setProperty("jcifs.smb.client.allowGuestFallback", "true")
            }
            val baseConfig = PropertyConfiguration(props)
            val baseContext = BaseContext(baseConfig)

            when {
                config.isGuest -> baseContext.withGuestCrendentials()
                config.username.isBlank() || config.username.equals("guest", ignoreCase = true) || config.username.equals("anonymous", ignoreCase = true) ->
                    baseContext.withAnonymousCredentials()
                else -> {
                    val auth = NtlmPasswordAuthenticator(
                        config.domain.trim().ifEmpty { null },
                        config.username.trim(),
                        creds.password ?: ""
                    )
                    baseContext.withCredentials(auth)
                }
            }
        }
    }

    fun getSmbFile(config: SmbConfig, creds: SmbCredentials, remotePath: String, isDirectory: Boolean? = null): SmbFile {
        val context = getContext(config, creds)
        val cleanPath = remotePath.trim('/')
        val url = when {
            cleanPath.isEmpty() -> "smb://${config.host}:${config.port}/"
            isDirectory == true || remotePath.endsWith('/') || !cleanPath.contains('/') ->
                "smb://${config.host}:${config.port}/$cleanPath/"
            isDirectory == false ->
                "smb://${config.host}:${config.port}/$cleanPath"
            else ->
                "smb://${config.host}:${config.port}/$cleanPath"
        }
        return SmbFile(url, context)
    }

    fun testConnection(config: SmbConfig, creds: SmbCredentials): Result<String> = try {
        // Fast TCP port test first to give instant feedback if host/port is unreachable
        Socket().use { socket ->
            socket.connect(InetSocketAddress(config.host, config.port), connectTimeoutMs)
        }

        // Test SMB handshake / session connect
        val targetPath = if (!config.initialShare.isNullOrBlank()) "/${config.initialShare.trim('/')}/" else "/"
        val targetFile = getSmbFile(config, creds, targetPath, isDirectory = true)
        targetFile.connect()

        val summary = StringBuilder("Connected successfully to SMB server ${config.host}:${config.port}!")

        if (!config.initialShare.isNullOrBlank()) {
            try {
                if (targetFile.exists()) {
                    summary.append("\nShare '${config.initialShare}' is verified and accessible.")
                } else {
                    summary.append("\nNote: Share '${config.initialShare}' was not found on this server.")
                }
            } catch (e: Throwable) {
                summary.append("\nNote: Share '${config.initialShare}' could not be accessed (${e.message ?: "Restricted"}). Please verify share name and permissions.")
            }
        } else {
            // Attempt to enumerate shares via MSRPC
            try {
                val shares = targetFile.listFiles()
                val shareNames = shares?.map { it.name.trimEnd('/') }?.filter { it.isNotEmpty() && !it.endsWith("$") } ?: emptyList()
                if (shareNames.isNotEmpty()) {
                    summary.append("\nFound ${shareNames.size} share(s): ${shareNames.take(6).joinToString(", ")}")
                    if (shareNames.size > 6) summary.append(", …")
                }
            } catch (e: Throwable) {
                val msg = e.message ?: ""
                if (msg.contains("0xC000020C") || msg.contains("0xC0000022") || msg.contains("denied", ignoreCase = true) || msg.contains("refused", ignoreCase = true)) {
                    summary.append("\n\nNote: This server restricts anonymous share enumeration. Please enter your Share name in the connection dialog to view files.")
                } else {
                    summary.append("\n\nNote: Could not enumerate shares (${e.message}). You can still access files by specifying a Share name directly.")
                }
            }
        }
        Result.success(summary.toString())
    } catch (e: Throwable) {
        Result.failure(e)
    }

    fun disconnect(connectionId: String) {
        contextCache.remove(connectionId)?.let { ctx ->
            try { ctx.close() } catch (_: Throwable) {}
        }
    }

    override fun close() {
        contextCache.keys().toList().forEach { disconnect(it) }
    }
}
