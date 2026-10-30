package com.infinifolder.archive

import java.io.InterruptedIOException

/** Carry an operation's cancellation flag through synchronous provider reads and staging. */
object RecoveryCancellation {
    private val current = ThreadLocal<(() -> Boolean)?>()
    fun check() {
        if (Thread.currentThread().isInterrupted || current.get()?.invoke() == true)
            throw InterruptedIOException("File recovery cancelled")
    }
    fun <T> withCancellation(cancelled: () -> Boolean, work: () -> T): T {
        val previous = current.get()
        current.set { cancelled() || previous?.invoke() == true }
        return try { work() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
