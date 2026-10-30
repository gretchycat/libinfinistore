package com.infinifolder.ops

import java.util.ArrayDeque
import kotlin.math.ceil

/** Time-weighted reported transfer throughput over the last 60 monotonic seconds.
 * Owned by the coordinator, so hiding UI or replacing an Activity preserves history. */
internal class OverallTransferEtaEstimator {
    data class Estimate(val averageBytesPerSecond: Long, val seconds: Long?, val status: String)
    private data class Rate(val stamp: Long, val value: Long, val expires: Long)
    private data class Interval(var start: Long, var end: Long, val rate: Double)
    private val rates = mutableMapOf<String, Rate>()
    private val intervals = ArrayDeque<Interval>()
    private var started: Long? = null
    private var last: Long? = null
    private val window = 60_000_000_000L

    fun observe(snapshots: List<OperationSnapshot>, wallMillis: Long = System.currentTimeMillis(), now: Long = System.nanoTime()): Estimate {
        val active = snapshots.filter { !it.id.startsWith("playback_") &&
            (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) }
        if (active.isEmpty()) {
            rates.clear(); intervals.clear(); started = null; last = null
            return Estimate(0, null, "estimating…")
        }
        if (started == null) { started = now; last = now }
        advance(now)
        val ids = active.mapTo(hashSetOf()) { it.id }
        rates.keys.retainAll(ids)
        active.forEach { op ->
            val verifying = op.etaIsVerification || op.torrentPiecesTotal != null || op.message?.let { message ->
                listOf("Verifying", "Verified", "Checking", "Restoring saved progress").any { message.startsWith(it, true) }
            } == true
            // A verification reread does not reduce remaining payload bytes.
            val rate = if (op.state == OperationState.RUNNING && !verifying &&
                (op.totalBytes != null || op.remainingBytes != null))
                (if (op.canFinishLater) op.torrentStats?.downloadBytesPerSecond ?: 0L else op.bytesPerSecond).coerceAtLeast(0)
                else 0L
            val previous = rates[op.id]
            if (previous == null || previous.stamp != op.lastUpdateMillis || previous.value != rate) {
                val ageMillis = (wallMillis - op.lastUpdateMillis).coerceIn(0, 5000)
                rates[op.id] = Rate(op.lastUpdateMillis, rate, now + (5000 - ageMillis) * 1_000_000L)
            }
        }
        val elapsed = minOf(window, (now - started!!).coerceAtLeast(0))
        val amount = intervals.sumOf { (it.end - it.start) / 1_000_000_000.0 * it.rate }
        val average = if (elapsed > 0) amount / (elapsed / 1_000_000_000.0) else 0.0
        val averageLong = average.coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
        val remaining = active.map { op ->
            op.remainingBytes?.coerceAtLeast(0)?.toDouble()
                ?: op.totalBytes?.let { total -> (total.coerceAtLeast(0) - op.bytes.coerceIn(0, total.coerceAtLeast(0))).toDouble() }
        }
        if (remaining.any { it == null }) return Estimate(averageLong, null, "estimating…")
        val left = remaining.filterNotNull().sum()
        if (left <= 0) return Estimate(averageLong, null, "finishing…")
        if (elapsed < 2_000_000_000L) return Estimate(averageLong, null, "estimating…")
        if (average <= 0) return Estimate(0, null, "waiting…")
        val seconds = ceil(left / average).coerceIn(1.0, Long.MAX_VALUE.toDouble()).toLong()
        return Estimate(averageLong, seconds, "")
    }

    private fun advance(now: Long) {
        var position = last ?: now
        // Split at individual expiry times, even if no UI or provider callback ran
        // during the stall. This prevents a stale rate being counted for minutes.
        val boundaries = rates.values.map { it.expires }.filter { it > position && it < now }.distinct().sorted() + now
        boundaries.forEach { end ->
            if (end > position) {
                val rate = rates.values.filter { it.expires > position }.sumOf { it.value.toDouble() }
                val previous = intervals.peekLast()
                if (previous != null && previous.end == position && previous.rate == rate) previous.end = end
                else intervals.addLast(Interval(position, end, rate))
            }
            position = end
        }
        last = now
        val cutoff = now - window
        while (intervals.peekFirst()?.let { it.end <= cutoff } == true) intervals.removeFirst()
        intervals.peekFirst()?.let { if (it.start < cutoff) it.start = cutoff }
    }
}
