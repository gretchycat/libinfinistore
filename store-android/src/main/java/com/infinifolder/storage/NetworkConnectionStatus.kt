package com.infinifolder.storage

import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArraySet

/** Last observed transport state, per saved connection. No polling or credential storage. */
object NetworkConnectionStatus {
    data class Key(val providerId: String, val connectionId: String)
    data class Target(val key: Key, val path: String)
    private data class Access(val version: Long, val unavailable: Boolean)
    enum class State { UNKNOWN, CONNECTED, DISCONNECTED, RECONNECTING }
    data class Request internal constructor(val key: Key, val version: Long)
    private class Entry {
        var sequence = 0L
        var resultVersion = 0L
        var state = State.UNKNOWN
        val reconnects = mutableSetOf<Long>()
        val targets = object : LinkedHashMap<String, Access>(256, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Access>?) = size > 256
        }
    }
    private val lock = Any()
    private val entries = mutableMapOf<Key, Entry>()
    private val listeners = CopyOnWriteArraySet<(Key) -> Unit>()
    fun addListener(listener: (Key) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Key) -> Unit) { listeners.remove(listener) }
    private fun state(entry: Entry): State = if (entry.reconnects.any { it > entry.resultVersion })
        State.RECONNECTING else entry.state
    fun state(key: Key): State = synchronized(lock) { entries[key]?.let(::state) ?: State.UNKNOWN }
    fun state(target: Target): State = synchronized(lock) {
        val entry = entries[target.key] ?: return@synchronized State.UNKNOWN
        val connection = state(entry)
        if (connection == State.DISCONNECTED || connection == State.RECONNECTING) connection
        else if (entry.targets[target.path]?.unavailable == true) State.DISCONNECTED else connection
    }
    /** Resolve the canonical provider reference, not a shortcut filename or host alias. */
    fun target(ref: com.infinifolder.model.ResourceRef): Target? {
        if (ref.providerId !in setOf("sftp", "ftp", "smb", "webdav")) return null
        val uri = android.net.Uri.parse(ref.documentId)
        val id = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        val path = uri.encodedPath.orEmpty().trimEnd('/').ifEmpty { "/" } +
            (uri.encodedQuery?.let { "?$it" } ?: "") + (uri.encodedFragment?.let { "#$it" } ?: "")
        return Target(Key(ref.providerId, id), path)
    }
    private fun access(request: Request, path: String, unavailable: Boolean) {
        val changed = synchronized(lock) {
            val entry = entries.getValue(request.key)
            val previous = entry.targets[path]
            if (previous != null && previous.version > request.version) false
            else {
                entry.targets[path] = Access(request.version, unavailable)
                (previous?.unavailable ?: false) != unavailable
            }
        }
        if (changed) notify(request.key)
    }
    fun accessible(request: Request, path: String) = access(request, path, false)
    fun inaccessible(request: Request, path: String, error: Throwable?, message: String = "") {
        // Transport loss is connection-wide; denied/missing targets are path-specific.
        if (!cancelled(error) && !isTransportFailure(error, message)) access(request, path, true)
    }
    private fun notify(key: Key) { listeners.forEach { runCatching { it(key) } } }

    fun begin(key: Key, reconnect: Boolean = false, indicateRetry: Boolean = true): Request {
        val request: Request
        val changed: Boolean
        synchronized(lock) {
            val entry = entries.getOrPut(key, ::Entry)
            val previous = state(entry)
            request = Request(key, ++entry.sequence)
            if (reconnect || indicateRetry && (previous == State.DISCONNECTED || previous == State.RECONNECTING))
                entry.reconnects.add(request.version)
            changed = state(entry) != previous
        }
        if (changed) notify(key)
        return request
    }

    /** Completion order cannot let an older failed request overwrite a newer success. */
    private fun finish(request: Request, result: State?) {
        val changed = synchronized(lock) {
            val entry = entries.getValue(request.key)
            val previous = state(entry)
            entry.reconnects.remove(request.version)
            if (result != null && request.version >= entry.resultVersion) {
                entry.state = result
                entry.resultVersion = request.version
            }
            state(entry) != previous
        }
        if (changed) {
            DiagnosticLog.record("network.connection.state",
                "provider=${request.key.providerId} connection=${request.key.connectionId} state=${state(request.key)}")
            notify(request.key)
        }
    }
    fun succeeded(request: Request) = finish(request, State.CONNECTED)
    fun observed(request: Request) = finish(request, null)
    fun failed(request: Request, error: Throwable?, message: String = "") =
        finish(request, if (isTransportFailure(error, message)) State.DISCONNECTED else null)

    /** Existing buffered data is not proof of a newly reconnected transport. */
    fun <T> watchIo(key: Key, work: () -> T): T {
        return watchIo(key, null, work)
    }
    fun <T> watchIo(key: Key, path: String?, work: () -> T): T {
        val request = begin(key, indicateRetry = false)
        return try { work().also { finish(request, null) } }
        catch (error: Exception) {
            failed(request, error)
            if (path != null) inaccessible(request, path, error)
            throw error
        }
    }

    private fun cancelled(error: Throwable?): Boolean = generateSequence(error) { it.cause }.take(16).any {
        it is InterruptedException || it is java.util.concurrent.CancellationException ||
            (it is InterruptedIOException && it !is java.net.SocketTimeoutException) ||
            it.message.orEmpty().contains("cancelled", true) || it.message.orEmpty().contains("canceled", true)
    }
    fun isTransportFailure(error: Throwable?, message: String = ""): Boolean {
        if (cancelled(error)) return false
        if (generateSequence(error) { it.cause }.take(16).any { it is jcifs.smb.SmbAuthException || it is java.nio.file.AccessDeniedException || it is SecurityException }) return false
        return generateSequence(error) { it.cause }.take(16).any {
            it is java.net.UnknownHostException || it is java.net.SocketException ||
                it is java.net.SocketTimeoutException || it is java.io.EOFException
        } || (sequenceOf(message) + generateSequence(error) { it.cause }.take(16).map { it.message.orEmpty() }).any { text ->
            listOf("session is down", "session is not connected", "inputstream is closed",
                "connection reset", "connection refused", "connection timed out", "network is unreachable",
                "no route to host", "broken pipe", "socket is closed", "failed to connect",
                "failed to resolve", "host is unreachable", "network name is no longer available").any { text.contains(it, true) }
        }
    }

    fun <T> reconnect(key: Key, work: () -> T): T {
        val request = begin(key, reconnect = true)
        return try {
            StorageActivity.track(StorageActivity.Kind.RECONNECTING, work).also { succeeded(request) }
        } catch (error: Exception) {
            // A completed, failed SSH handshake leaves this saved connection disconnected.
            finish(request, if (cancelled(error)) null else State.DISCONNECTED)
            throw error
        }
    }
}
