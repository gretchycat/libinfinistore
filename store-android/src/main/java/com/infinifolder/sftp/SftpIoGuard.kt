package com.infinifolder.sftp

import com.infinifolder.storage.DiagnosticLog
import com.jcraft.jsch.SftpProgressMonitor
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounds channel inactivity, not total transfer duration. Real SFTP byte callbacks renew the deadline. */
internal class SftpIoGuard(timeoutSeconds: Int, private val disconnect: () -> Unit) : java.io.Closeable {
    companion object {
        private val clock = Executors.newScheduledThreadPool(2) { task -> Thread(task, "SftpStallWatch").apply { isDaemon = true } }
        private val aborts = Executors.newCachedThreadPool { task -> Thread(task, "SftpStallAbort").apply { isDaemon = true } }
    }
    private val lock = Any()
    private val timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong().coerceAtLeast(120))
    @Volatile private var progressAt = System.nanoTime()
    @Volatile private var expired = false
    private var stage: String? = null
    private var owner: Thread? = null
    private val watch = clock.scheduleAtFixedRate({
        val stalled = synchronized(lock) {
            if (!expired && stage != null && System.nanoTime() - progressAt >= timeoutNanos) {
                expired = true
                stage to owner
            } else null
        }
        if (stalled != null) {
            DiagnosticLog.record("sftp.stream.timeout", "stage=${stalled.first} timeoutSeconds=${TimeUnit.NANOSECONDS.toSeconds(timeoutNanos)} thread=${stalled.second?.name} stack=${stalled.second?.stackTrace?.take(8)?.joinToString(" <- ")}")
            aborts.execute { runCatching(disconnect) }
        }
    }, 1, 1, TimeUnit.SECONDS)

    val monitor = object : SftpProgressMonitor {
        override fun init(op: Int, source: String?, destination: String?, maximum: Long) { progressAt = System.nanoTime() }
        override fun count(count: Long): Boolean {
            if (count > 0) progressAt = System.nanoTime()
            return !expired
        }
        override fun end() { }
    }
    private fun timeout(cause: Exception? = null) = SocketTimeoutException("SFTP stopped making progress for ${TimeUnit.NANOSECONDS.toSeconds(timeoutNanos)} seconds").apply {
        if (cause != null) initCause(cause)
    }
    fun <T> call(label: String, action: () -> T): T {
        synchronized(lock) {
            if (expired) throw timeout()
            stage = label; owner = Thread.currentThread(); progressAt = System.nanoTime()
        }
        try {
            val result = action()
            if (expired) throw timeout()
            return result
        } catch (error: Exception) {
            if (expired && error !is SocketTimeoutException) throw timeout(error)
            throw error
        } finally { synchronized(lock) { stage = null; owner = null } }
    }
    override fun close() { watch.cancel(false) }
}
