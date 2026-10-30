package com.infinifolder.torrent

import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Fences queued writes before flushing/publishing the final resumable checkpoint. */
internal class TorrentTransferLifecycle(val ioLock: Any, private val cancelled: () -> Boolean) {
    private val acceptingWrites = AtomicBoolean(true)
    private fun checkWritable() {
        if (!acceptingWrites.get() || cancelled() || Thread.currentThread().isInterrupted)
            throw InterruptedIOException("Torrent transfer paused")
    }
    fun <T> write(block: () -> T): T {
        checkWritable()
        return synchronized(ioLock) { checkWritable(); block() }
    }
    fun closeWrites() { acceptingWrites.set(false) }
    fun isClosing() = !acceptingWrites.get()

    fun finish(stop: () -> Unit, persist: () -> Unit, completed: Boolean = false) {
        closeWrites()
        val interruptedBeforeShutdown = Thread.interrupted()
        var interrupted = interruptedBeforeShutdown
        var finished = false
        try {
            try { stop() }
            finally {
                // Bt shutdown can interrupt its caller again. Checkpoint I/O must
                // run after writers drain and with the interrupt flag cleared.
                interrupted = Thread.interrupted() || interrupted
                synchronized(ioLock) {
                    try { persist() }
                    finally { interrupted = Thread.interrupted() || interrupted }
                }
            }
            finished = true
        } finally {
            // A successful engine shutdown may interrupt its caller. Do not
            // pass that synthetic interrupt into the final remote rename.
            // Preserve a pre-existing interrupt and every real cancellation.
            if (interrupted && (!completed || !finished || interruptedBeforeShutdown || cancelled()))
                Thread.currentThread().interrupt()
        }
    }
}
