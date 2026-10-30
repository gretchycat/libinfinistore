package com.infinifolder.ops

import kotlin.math.ceil

/** Estimates from actual progress, using a monotonic clock and separate phase samples. */
internal class OperationEtaEstimator {
    private var key = ""
    private var lastCount = 0L
    private var lastSampleNanos = 0L
    private var lastAdvanceNanos = 0L
    private var rate = 0.0

    fun update(op: OperationSnapshot, now: Long = System.nanoTime()): OperationSnapshot {
        if (op.state != OperationState.RUNNING) return op.copy(estimatedRemainingSeconds = null)
        val verification = op.torrentPiecesTotal != null || op.message?.let {
            it.startsWith("Verifying", true) || it.startsWith("Checking", true) || it.startsWith("Verified", true)
        } == true
        val phase = verification && op.bytesPerSecond == 0L && !(op.remainingBytes != null && op.bytes > 0L)
        val pieces = phase && op.torrentPiecesTotal != null
        val count: Long
        val remaining: Long
        val nextKey: String
        when {
            pieces -> {
                count = op.torrentPiecesChecked?.toLong() ?: 0L
                remaining = (op.torrentPiecesTotal!!.toLong() - count).coerceAtLeast(0)
                nextKey = "pieces:${op.currentItem}"
            }
            phase && op.currentItemTotalBytes != null -> {
                count = op.currentItemBytes ?: 0L
                remaining = (op.currentItemTotalBytes - count).coerceAtLeast(0)
                nextKey = "verify:${op.currentItem}"
            }
            op.remainingBytes != null || op.totalBytes != null -> {
                count = op.bytes
                remaining = op.remainingBytes ?: (op.totalBytes!! - count).coerceAtLeast(0)
                nextKey = "bytes"
            }
            op.totalItems > 0 -> {
                count = op.itemsProcessed.toLong()
                remaining = (op.totalItems.toLong() - count).coerceAtLeast(0)
                nextKey = "items"
            }
            else -> return op.copy(estimatedRemainingSeconds = null)
        }
        if (key != nextKey || count < lastCount) {
            key = nextKey
            lastCount = count
            lastSampleNanos = now
            lastAdvanceNanos = now
            rate = 0.0
        }
        val elapsed = (now - lastSampleNanos) / 1_000_000_000.0
        if (count > lastCount && elapsed >= 0.25) {
            val measured = (count - lastCount) / elapsed
            rate = if (rate <= 0) measured else rate * 0.7 + measured * 0.3
            lastCount = count
            lastSampleNanos = now
            lastAdvanceNanos = now
        }
        if (nextKey == "bytes" && op.bytesPerSecond > 0) {
            val reported = op.bytesPerSecond.toDouble()
            rate = if (rate <= 0) reported else rate * 0.7 + reported * 0.3
        }
        val seconds = when {
            remaining == 0L || op.indeterminate && !pieces -> null // Publication/preparation is not timed by byte totals.
            now - lastAdvanceNanos > 5_000_000_000L -> null
            rate > 0 -> ceil(remaining / rate).toLong().coerceAtLeast(1)
            else -> null
        }
        return op.copy(estimatedRemainingSeconds = seconds, etaIsVerification = phase,
            bytesPerSecond = if (nextKey == "bytes" && op.bytesPerSecond == 0L &&
                now - lastAdvanceNanos <= 5_000_000_000L) rate.toLong().coerceAtLeast(0L) else op.bytesPerSecond)
    }
}
