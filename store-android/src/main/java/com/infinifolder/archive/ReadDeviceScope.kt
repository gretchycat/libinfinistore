package com.infinifolder.archive

import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceRef
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.storage.StorageProvider
import java.io.IOException

/** Short-lived read-only source reuse for one copy operation, never a global device cache. */
object ReadDeviceScope {
    private class Source(val size: Long, val modified: Long, val device: BlockDevice) {
        var leases = 0
        var reuses = 0L
        var stale = false
        var closed = false
    }
    private class Scope(val onWarning: (String) -> Unit) {
        val sources = LinkedHashMap<ResourceRef, Source>(4, 0.75f, true)
        var liveWarningSent = false
    }
    private val current = ThreadLocal<Scope?>()

    fun <T> withScope(enabled: Boolean, onWarning: (String) -> Unit = {}, work: () -> T): T {
        if (!enabled) {
            val previous = current.get()
            current.remove()
            return try { work() } finally { if (previous != null) current.set(previous) }
        }
        if (current.get() != null) return work()
        val scope = Scope(onWarning)
        current.set(scope)
        return try { work() } finally {
            current.remove()
            scope.sources.values.forEach(::closeSource)
        }
    }

    private fun closeSource(source: Source) {
        if (source.closed) return
        source.closed = true
        runCatching { source.device.close() }.onFailure {
            DiagnosticLog.record("image.read-session.close-failed", "reuses=${source.reuses}", it)
        }
        DiagnosticLog.record("image.read-session.closed", "reuses=${source.reuses} outstandingLeases=${source.leases}")
    }

    fun open(provider: StorageProvider, ref: ResourceRef, create: () -> BlockDevice?): BlockDevice? {
        val scope = current.get() ?: return create()
        // Nested archive adapters have their own ownership graph. Cache their
        // primitive backing resource instead, through its normal resolver call.
        if (ref.providerId == "archive") return create()
        val meta = when (val result = provider.stat(ref)) {
            is ProviderResult.Success -> result.value.metadata
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> return create()
        }
        val size = meta.sizeBytes ?: return create()
        val modified = meta.modifiedAtMillis?.takeIf { it > 0 } ?: return create()
        var source = scope.sources[ref]
        if (source != null && (source.stale || source.size != size || source.modified != modified)) {
            DiagnosticLog.record("image.read-session.refresh",
                "provider=${ref.providerId} oldBytes=${source.size} newBytes=$size oldModified=${source.modified} newModified=$modified activeLeases=${source.leases}")
            source.stale = true
            if (!scope.liveWarningSent) {
                scope.liveWarningSent = true
                scope.onWarning("The source image changed while copying. Continuing with refreshed data; " +
                    "files may reflect different moments and the copy may be inconsistent.")
            }
            // Do not close a device that an outer/nested reader is still using.
            // New callers get a fresh uncached device until the old lease ends.
            if (source.leases > 0) return create()
            scope.sources.remove(ref)
            closeSource(source)
            source = null
        }
        if (source == null) {
            // Four backend devices bound the range-cache footprint (normally
            // 16 MiB each). Active leases cannot be evicted by nested reads.
            if (scope.sources.size >= 4) {
                val evict = scope.sources.entries.firstOrNull { it.value.leases == 0 }
                if (evict == null) return create()
                scope.sources.remove(evict.key)
                closeSource(evict.value)
            }
            val device = create() ?: return null
            if (!device.isReadOnly) return device
            source = Source(size, modified, device)
            scope.sources[ref] = source
            DiagnosticLog.record("image.read-session.open", "provider=${ref.providerId} bytes=$size")
        } else {
            source.reuses++
            if (source.reuses == 1L || source.reuses % 100L == 0L)
                DiagnosticLog.record("image.read-session.reuse", "provider=${ref.providerId} reuses=${source.reuses}")
        }
        val leased = source
        leased.leases++
        return object : BlockDevice by leased.device {
            private var released = false
            override fun close() {
                if (!released) {
                    released = true
                    leased.leases--
                    if (leased.stale && leased.leases == 0) {
                        scope.sources.remove(ref, leased)
                        closeSource(leased)
                    }
                }
            }
        }
    }
}
