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
                setProperty("jcifs.smb.client.allowGuestFallback", (config.isGuest || config.username.isBlank() ||
                    config.username.equals("guest", true) || config.username.equals("anonymous", true)).toString())
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

    fun testConnection(config: SmbConfig, creds: SmbCredentials): Result<String> {
        // Probe input credentials, never the cached credentials of an earlier test.
        val probe = config.copy(id = "smb-probe-${java.util.UUID.randomUUID()}")
        var reachable = false
        var connected = false
        return try {
            Socket().use { it.connect(InetSocketAddress(config.host, config.port), connectTimeoutMs) }
            reachable = true
            val target = getSmbFile(probe, creds, config.rootPath, isDirectory = true)
            val files = target.use {
                it.connect()
                connected = true
                // A handshake/exists check does not demonstrate permission to list.
                it.listFiles() ?: throw IOException("Server returned no directory listing")
            }
            val count = files.size
            files.forEach { runCatching { it.close() } }
            com.infinifolder.storage.DiagnosticLog.record("smb.test.succeeded", "scope=directory-list items=$count")
            Result.success("Server reachable. SMB connected and listed '${config.rootPath}' successfully ($count items).")
        } catch (error: Exception) {
            com.infinifolder.storage.DiagnosticLog.record("smb.test.failed", "reachable=$reachable connected=$connected scope=directory-list", error)
            val status = when {
                !reachable -> "Server not reachable on the configured SMB port."
                !connected -> "Server reachable, but SMB connection, authentication or share access failed."
                else -> "Server reachable and SMB connected, but directory listing failed."
            }
            val hint = if (config.rootPath == "/")
                "The server may restrict share enumeration. Specify an accessible share and folder."
            else "Check the configured share/folder, credentials and directory-list permissions."
            Result.failure(IOException("$status\n\nFolder: ${config.rootPath}\n${error.message ?: error.javaClass.simpleName}\n\n$hint", error))
        } finally { disconnect(probe.id) }
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
