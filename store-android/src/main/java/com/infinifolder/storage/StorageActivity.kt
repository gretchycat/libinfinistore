package com.infinifolder.storage

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/** Process-wide activity signals; concurrent work cannot clear another task's light. */
object StorageActivity {
    enum class Kind { LOADING, FILE_OPERATION, VERIFICATION }
    private val counts = AtomicIntegerArray(Kind.entries.size)
    private val visibleUntil = AtomicLongArray(Kind.entries.size)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val notifiedAt = AtomicLongArray(Kind.entries.size)
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    fun pending(kind: Kind): Boolean = counts.get(kind.ordinal) > 0
    // Green pulses follow read progress; pending loads can separately show a steady dim light.
    fun active(kind: Kind): Boolean = (kind != Kind.LOADING && counts.get(kind.ordinal) > 0) ||
        System.nanoTime() < visibleUntil.get(kind.ordinal)
    fun pulse(kind: Kind) {
        val now = System.nanoTime()
        visibleUntil.set(kind.ordinal, now + 200_000_000L)
        if (now - notifiedAt.get(kind.ordinal) >= 50_000_000L) {
            notifiedAt.set(kind.ordinal, now)
            notifyListeners()
        }
    }
    private fun notifyListeners() { listeners.forEach { runCatching(it) } }
    fun begin(kind: Kind): Closeable {
        val index = kind.ordinal
        pulse(kind)
        if (counts.incrementAndGet(index) == 1) notifyListeners()
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                pulse(kind)
                if (counts.decrementAndGet(index) == 0) notifyListeners()
            }
        }
    }
    fun <T> track(kind: Kind, work: () -> T): T = begin(kind).use { work() }
    fun loadingExecutor(delegate: Executor): Executor = Executor { work ->
        delegate.execute { begin(Kind.LOADING).use { work.run() } }
    }
}
