package com.infinifolder.ops

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean

enum class OperationState { QUEUED, RUNNING, COMPLETE, FAILED, CANCELLED }

/** A user declined a required operation choice; this is not a storage failure. */
class OperationDeclinedException(message: String) : InterruptedException(message)

/** Data-only replay description: no Activity or finished worker closure is retained. */
data class TransferRestartRequest(
    val entries: List<com.infinifolder.model.ResourceEntry>,
    val destination: String,
    val move: Boolean,
    val createLinks: Boolean = false,
    val partialRecovery: Boolean = false
)

data class OperationUpdate(
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
    val itemsProcessed: Int = 0,
    val totalItems: Int = 0,
    val currentItem: String? = null,
    val bytesPerSecond: Long = 0L,
    val message: String? = null,
    val indeterminate: Boolean? = null,
    val currentItemBytes: Long? = null,
    val currentItemTotalBytes: Long? = null,
    val resumable: Boolean? = null,
    val remainingBytes: Long? = null,
    val torrentPiecesChecked: Int? = null,
    val torrentPiecesTotal: Int? = null,
    val warningCount: Int = 0,
    val destination: String? = null,
    val torrentStats: com.infinifolder.storage.TorrentTransferStats? = null,
    val resumeDestination: com.infinifolder.model.ResourceRef? = null
)

data class OperationSnapshot(
    val id: String,
    val label: String,
    val state: OperationState,
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
    val itemsProcessed: Int = 0,
    val totalItems: Int = 0,
    val currentItem: String? = null,
    val bytesPerSecond: Long = 0L,
    val startTimeMillis: Long = System.currentTimeMillis(),
    val message: String? = null,
    val indeterminate: Boolean = false,
    val currentItemBytes: Long? = null,
    val currentItemTotalBytes: Long? = null,
    val resumable: Boolean = false,
    val remainingBytes: Long? = null,
    val torrentPiecesChecked: Int? = null,
    val torrentPiecesTotal: Int? = null,
    val warningCount: Int = 0,
    val estimatedRemainingSeconds: Long? = null,
    val etaIsVerification: Boolean = false,
    val displayName: String? = null,
    val destination: String? = null,
    val torrentStats: com.infinifolder.storage.TorrentTransferStats? = null,
    val resumeDestination: com.infinifolder.model.ResourceRef? = null,
    val lastUpdateMillis: Long = System.currentTimeMillis(),
    val restartTransfer: TransferRestartRequest? = null,
    val pausedByUser: Boolean = false,
    /** Restored status history must not trigger another completion notification. */
    val restoredFromHistory: Boolean = false
) {
    /** Overall totals stay visible during per-file preparation/verification phases. */
    val overallProgressPercent: Int
        get() = when {
            state == OperationState.COMPLETE -> 100
            totalBytes != null && totalBytes > 0 -> (bytes.toDouble() / totalBytes * 100).toInt().coerceIn(0, 99)
            totalItems > 0 -> (itemsProcessed.toDouble() / totalItems * 100).toInt().coerceIn(0, 99)
            else -> -1
        }
    val currentItemProgressPercent: Int
        get() {
            val done = currentItemBytes ?: return -1
            val total = currentItemTotalBytes ?: return -1
            return if (total > 0) (done.toDouble() / total * 100).toInt().coerceIn(0, 100)
                else if (total == 0L) 100 else -1
        }
    val progressPercent: Int
        get() = when {
            indeterminate -> -1
            torrentPiecesTotal != null && torrentPiecesTotal > 0 ->
                ((torrentPiecesChecked ?: 0).toDouble() / torrentPiecesTotal * 100).toInt().coerceIn(0, 100)
            totalBytes != null && totalBytes > 0 -> ((bytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            totalItems > 0 -> ((itemsProcessed * 100) / totalItems).coerceIn(0, 100)
            else -> -1
        }

    val canFinishLater: Boolean
        get() = resumable || label.startsWith("Resume torrent:")
    val stopActionLabel: String get() = if (canFinishLater) "Pause" else "Cancel"
    val canRestart: Boolean get() = state in setOf(OperationState.FAILED, OperationState.CANCELLED) &&
        (resumeDestination != null || restartTransfer != null)
    val restartActionLabel: String get() = if (resumeDestination != null) {
        if (state == OperationState.CANCELLED) "Resume" else "Retry"
    } else "Restart"
    val canRemoveFromHistory: Boolean
        get() = !id.startsWith("playback_") && state in setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)
    val displayState: String
        get() = if (state == OperationState.CANCELLED && pausedByUser) "paused" else state.name.lowercase()


    fun formatEta(nowMillis: Long = System.currentTimeMillis()): String {
        if (state == OperationState.QUEUED) return "ETA: waiting in queue"
        if (state != OperationState.RUNNING) return ""
        val prefix = if (etaIsVerification && message?.startsWith("Restoring saved progress") == true) "Resume ETA"
            else if (etaIsVerification) "Verification ETA" else "ETA"
        if (nowMillis - lastUpdateMillis >= 5000) return "$prefix: waiting for progress"
        val seconds = estimatedRemainingSeconds ?: return "$prefix: estimating…"
        return "$prefix: ~${formatEtaDuration(seconds)}"
    }

    fun formatRate(): String = formatTransferRate(bytesPerSecond)
}

fun formatTransferRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
    bytesPerSecond > 0 -> "$bytesPerSecond B/s"
    else -> ""
}

fun formatEtaDuration(seconds: Long): String = when {
    seconds >= 86400 -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

/** Independent work runs concurrently; conflict keys serialize access to the same files. */
class OperationCoordinator(
    private val concurrency: Int? = null
) {
    private fun failureReason(error: Exception): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val reasons = causes.mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        return if (reasons.isEmpty()) "Operation failed (${causes.last().javaClass.simpleName})"
            else reasons.take(4).joinToString("\nCaused by: ")
    }

    private val executor = if (concurrency == null) Executors.newCachedThreadPool()
        else Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))
    private val cancelled = mutableMapOf<String, AtomicBoolean>()
    private val records = LinkedHashMap<String, OperationSnapshot>()
    private val removedHistoryIds = mutableSetOf<String>()
    private val futures = mutableMapOf<String, Future<*>>()
    private val resourceLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val etaEstimators = mutableMapOf<String, OperationEtaEstimator>()
    private val overallTransferEta = OverallTransferEtaEstimator()
    private val progressLoggedAt = mutableMapOf<String, Long>()
    private val listeners = mutableListOf<(List<OperationSnapshot>) -> Unit>()

    @Synchronized fun snapshots(): List<OperationSnapshot> = records.values.toList()

    internal fun restoreHistory(history: List<OperationSnapshot>) {
        synchronized(this) {
            history.filter { it.canRemoveFromHistory && it.id !in removedHistoryIds }.forEach {
                records.putIfAbsent(it.id, it.copy(restoredFromHistory = true))
            }
            val ordered = records.values.sortedBy { it.startTimeMillis }
            records.clear()
            ordered.forEach { records[it.id] = it }
        }
        notifyListeners()
    }

    @Synchronized fun activitySummary(): OperationActivitySummary {
        val current = records.values.toList()
        val eta = overallTransferEta.observe(current)
        return OperationActivitySummary.from(current).copy(averageBytesPerSecond = eta.averageBytesPerSecond,
            estimatedRemainingSeconds = eta.seconds, etaStatus = eta.status)
    }

    /** Remove display/history records only; never cancel work or remove transfer data. */
    fun clearCompleted(id: String? = null): Int = clearHistory(id, completedOnly = true)

    /** Dismiss completed, failed or stopped records; partial files and resume sessions remain. */
    fun clearFinished(id: String? = null): Int = clearHistory(id, completedOnly = false)

    private fun clearHistory(id: String?, completedOnly: Boolean): Int {
        val removed = synchronized(this) {
            val ids = records.values.filter {
                it.canRemoveFromHistory && (!completedOnly || it.state == OperationState.COMPLETE) &&
                    (id == null || it.id == id)
            }.map { it.id }
            ids.forEach { key ->
                removedHistoryIds.add(key)
                records.remove(key)
                futures.remove(key)
                cancelled.remove(key)
                etaEstimators.remove(key)
                progressLoggedAt.remove(key)
            }
            ids.size
        }
        if (removed > 0) {
            com.infinifolder.storage.DiagnosticLog.record("operation.history.cleared", "removed=$removed completedOnly=$completedOnly")
            notifyListeners()
        }
        return removed
    }

    @Synchronized fun activeSnapshots(): List<OperationSnapshot> =
        records.values.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }

    @Synchronized fun addListener(listener: (List<OperationSnapshot>) -> Unit) {
        listeners.add(listener)
        listener(snapshots())
    }

    @Synchronized fun removeListener(listener: (List<OperationSnapshot>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val (currentSnapshots, copy) = synchronized(this) {
            val current = records.values.toList()
            overallTransferEta.observe(current)
            current to listeners.toList()
        }
        copy.forEach { it(currentSnapshots) }
    }

    @Synchronized fun submit(
        label: String,
        totalBytes: Long? = null,
        totalItems: Int = 0,
        conflictKeys: Set<String> = emptySet(),
        displayName: String? = null,
        destination: String? = null,
        resumeDestination: com.infinifolder.model.ResourceRef? = null,
        restartTransfer: TransferRestartRequest? = null,
        work: (update: (OperationUpdate) -> Unit, isCancelled: () -> Boolean) -> Unit
    ): String {
        val id = UUID.randomUUID().toString()
        val token = AtomicBoolean(false)
        cancelled[id] = token
        records[id] = OperationSnapshot(
            id = id,
            label = label,
            state = OperationState.QUEUED,
            bytes = 0L,
            totalBytes = totalBytes,
            totalItems = totalItems,
            displayName = displayName,
            destination = destination,
            resumeDestination = resumeDestination,
            restartTransfer = restartTransfer,
            startTimeMillis = System.currentTimeMillis()
        )
        notifyListeners()
        futures[id] = executor.submit {
            val heldLocks = mutableListOf<ReentrantLock>()
            try {
                conflictKeys.sorted().forEach { key ->
                    val lock = resourceLocks.computeIfAbsent(key) { ReentrantLock() }
                    if (!lock.tryLock()) {
                        update(id) { it.copy(message = "Waiting for another operation using the same file") }
                        com.infinifolder.storage.DiagnosticLog.record("operation.conflict-wait", "id=$id")
                        lock.lockInterruptibly()
                    }
                    heldLocks += lock
                }
                if (token.get()) throw InterruptedException("Operation cancelled before start")
                update(id) { it.copy(state = OperationState.RUNNING, message = "Starting…") }
                work({ u ->
                    update(id) {
                        it.copy(
                            destination = u.destination ?: it.destination,
                            torrentStats = u.torrentStats,
                            resumeDestination = u.resumeDestination,
                            bytes = u.bytes,
                            totalBytes = u.totalBytes ?: it.totalBytes,
                            itemsProcessed = u.itemsProcessed,
                            totalItems = if (u.totalItems > 0) u.totalItems else it.totalItems,
                            currentItem = u.currentItem ?: it.currentItem,
                            bytesPerSecond = u.bytesPerSecond,
                            message = u.message ?: it.message,
                            indeterminate = u.indeterminate ?: it.indeterminate,
                            currentItemBytes = u.currentItemBytes,
                            currentItemTotalBytes = u.currentItemTotalBytes,
                            resumable = u.resumable ?: it.resumable,
                            remainingBytes = u.remainingBytes,
                            torrentPiecesChecked = u.torrentPiecesChecked,
                            torrentPiecesTotal = u.torrentPiecesTotal,
                            warningCount = maxOf(it.warningCount, u.warningCount)
                        )
                    }
                }, { token.get() })
                update(id) { it.copy(state = if (token.get()) OperationState.CANCELLED else OperationState.COMPLETE, indeterminate = false,
                    message = if (token.get()) { if (it.pausedByUser) "Paused; download retained" else "Cancelled" } else if (it.warningCount > 0) "Completed with ${it.warningCount} warnings" else "Completed") }
            } catch (e: Exception) {
                val stopped = token.get() || e is OperationDeclinedException
                com.infinifolder.storage.DiagnosticLog.record(if (stopped) "operation.cancelled" else "operation.failed", label, e)
                update(id) {
                    it.copy(
                        state = if (stopped) OperationState.CANCELLED else OperationState.FAILED,
                        indeterminate = false,
                        message = if (e is OperationDeclinedException) e.message else if (token.get()) { if (it.pausedByUser) "Paused; download retained" else "Cancelled" } else failureReason(e)
                    )
                }
            } finally { heldLocks.asReversed().forEach { it.unlock() } }
        }
        return id
    }

    @Synchronized fun submit(
        label: String,
        totalBytes: Long? = null,
        work: (progress: (Long) -> Unit, isCancelled: () -> Boolean) -> Unit
    ): String = submit(label, totalBytes, 0) { update, isCancelled ->
        work({ bytes -> update(OperationUpdate(bytes = bytes)) }, isCancelled)
    }

    var onCancelExternal: ((String) -> Unit)? = null

    @Synchronized fun cancel(id: String, reason: String = "unspecified", pause: Boolean? = null) {
        val before = records[id]
        if (before == null || before.state !in setOf(OperationState.RUNNING, OperationState.QUEUED)) return
        val pausing = (pause ?: before.canFinishLater) && before.canFinishLater
        com.infinifolder.storage.DiagnosticLog.record(
            "operation.cancel.requested",
            "id=$id label=${before?.label ?: "unknown"} state=${before?.state ?: "unknown"} reason=$reason thread=${Thread.currentThread().name}"
        )
        cancelled[id]?.set(true)
        update(id) { it.copy(state = OperationState.CANCELLED, pausedByUser = pausing,
            message = if (pausing) "Paused; download retained" else "Cancelled") }
        futures[id]?.cancel(true)
        onCancelExternal?.invoke(id)
    }

    @Synchronized fun registerExternal(
        id: String,
        label: String,
        totalBytes: Long? = null,
        currentItem: String? = null
    ) {
        records[id] = OperationSnapshot(
            id = id,
            label = label,
            state = OperationState.RUNNING,
            bytes = 0L,
            totalBytes = totalBytes,
            currentItem = currentItem,
            startTimeMillis = System.currentTimeMillis()
        )
        notifyListeners()
    }

    @Synchronized fun updateExternal(
        id: String,
        bytes: Long,
        totalBytes: Long? = null,
        currentItem: String? = null,
        message: String? = null
    ) {
        records[id]?.let {
            val next = it.copy(
                bytes = bytes,
                totalBytes = totalBytes ?: it.totalBytes,
                currentItem = currentItem ?: it.currentItem,
                message = message ?: it.message,
                lastUpdateMillis = System.currentTimeMillis()
            )
            records[id] = etaEstimators.getOrPut(id) { OperationEtaEstimator() }.update(next)
            notifyListeners()
        }
    }

    @Synchronized fun completeExternal(id: String) {
        records.remove(id)?.let {
            etaEstimators.remove(id)
            notifyListeners()
        }
    }

    private fun update(id: String, transform: (OperationSnapshot) -> OperationSnapshot) {
        synchronized(this) {
            val record = records[id] ?: return
            val changed = transform(record).copy(lastUpdateMillis = System.currentTimeMillis())
            val next = etaEstimators.getOrPut(id) { OperationEtaEstimator() }.update(changed)
            if (next.state !in setOf(OperationState.RUNNING, OperationState.QUEUED)) etaEstimators.remove(id)
            records[id] = next
            val now = System.currentTimeMillis()
            if (next.state == OperationState.RUNNING && now - (progressLoggedAt[id] ?: 0L) >= 10_000L) {
                progressLoggedAt[id] = now
                com.infinifolder.storage.DiagnosticLog.record("operation.progress",
                    "id=$id bytes=${next.bytes} totalBytes=${next.totalBytes} remainingBytes=${next.remainingBytes} " +
                        "rate=${next.bytesPerSecond} etaSeconds=${next.estimatedRemainingSeconds} verification=${next.etaIsVerification} " +
                        "pieces=${next.torrentPiecesChecked}/${next.torrentPiecesTotal} peers=${next.torrentStats?.connectedPeers} " +
                        "serving=${next.torrentStats?.servingPeers} uploadRate=${next.torrentStats?.uploadBytesPerSecond} uploaded=${next.torrentStats?.uploadedBytes}")
            } else if (next.state !in setOf(OperationState.RUNNING, OperationState.QUEUED)) progressLoggedAt.remove(id)
            if (record.state != next.state) com.infinifolder.storage.DiagnosticLog.record("operation.state", "id=$id label=${next.label} state=${next.state} bytes=${next.bytes} warnings=${next.warningCount}")
        }
        // Notification/listener work can perform Binder calls. Never hold the
        // coordinator lock while UI callers are waiting to read snapshots.
        notifyListeners()
    }
}

object AppOperations {
    val coordinator = OperationCoordinator()
    private var history: OperationHistoryStore? = null

    @Synchronized fun initialize(context: android.content.Context) {
        if (history == null) history = OperationHistoryStore(context.applicationContext, coordinator).also { it.start() }
    }
}
