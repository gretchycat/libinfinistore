package com.infinifolder.archive

import java.io.InterruptedIOException

/** Carry an operation's cancellation flag through synchronous provider reads and staging. */
object RecoveryCancellation {
    private val current = ThreadLocal<(() -> Boolean)?>()
    fun check() {
        if (Thread.currentThread().isInterrupted || current.get()?.invoke() == true)
            throw InterruptedIOException("File recovery cancelled")
    }
    /** Undo must finish despite the cancelled operation that requested it. */
    fun <T> withoutCancellation(work: () -> T): T {
        val previous = current.get()
        val interrupted = Thread.interrupted()
        current.remove()
        return try { work() } finally {
            if (previous != null) current.set(previous) else current.remove()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    fun <T> withCancellation(cancelled: () -> Boolean, work: () -> T): T {
        val previous = current.get()
        current.set { cancelled() || previous?.invoke() == true }
        return try { work() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
