package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Bounded app-private diagnostics with an optional user-granted document destination. */
object DiagnosticLog {
    /** Host injects its routed graph lazily; Store never constructs application/Vault runtime. */
    @Volatile var providerResolver: ((Context) -> StorageProvider)? = null
    private val lock = Any()
    private val fileLock = Any()
    private val pending = java.util.ArrayDeque<String>()
    private var pendingChars = 0
    private var dropped = 0L
    private var drainQueued = false
    private val logWriter = Executors.newSingleThreadExecutor { task -> Thread(task, "InfiniFolderLogWriter").apply { isDaemon = true } }
    private val muted = ThreadLocal.withInitial { false }
    private val gestureTrace = java.util.ArrayDeque<String>()
    private var gestureTraceChars = 0
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val publishLock = Any()
    private var pendingPublish: ScheduledFuture<*>? = null
    private var publishGeneration = 0L
    @Volatile private var app: Context? = null
    @Volatile var lastExportError: String? = null
        private set
    private fun file(context: Context) = File(context.filesDir, "diagnostics/session.log").apply { parentFile?.mkdirs() }
    fun init(context: Context) {
        if (app != null) return
        synchronized(lock) {
            if (app != null) return
            app = context.applicationContext
        }
        record("session", "started sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }
    private val credentialPattern = Regex("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@")
    private val secretPattern = Regex("(?i)(password|passwd|passphrase|token|secret|authorization)([\\s:=]+)([^\\s&,;]+)")
    private val bearerPattern = Regex("(?i)Bearer\\s+[^\\s]+")
    fun sanitize(message: String): String = message
        .replace(credentialPattern, "$1<redacted>@")
        .replace(secretPattern, "$1$2<redacted>")
        .replace(bearerPattern, "Bearer <redacted>")
    fun record(event: String, detail: String = "", error: Throwable? = null) {
        val context = app ?: return
        if (muted.get() == true) return
        runCatching {
            val description = buildString {
                append(Instant.now()); append(" ["); append(Thread.currentThread().name); append("] ")
                append(event); append(' '); append(detail)
                if (error != null) {
                    var cause: Throwable? = error
                    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                    var depth = 0
                    while (cause != null && depth < 8 && seen.add(cause)) {
                        append(if (depth == 0) " " else "\nCaused by: ")
                        append(cause.javaClass.name); append(": "); append(cause.message)
                        cause.stackTrace.take(20).forEach { append("\n    at "); append(it) }
                        cause = cause.cause
                        depth++
                    }
                    if (cause != null) append("\n    (cause chain truncated)")
                }
                append('\n')
            }
            val safeDescription = sanitize(description).take(64 * 1024)
            synchronized(lock) {
                // Preserve recent touch decisions even when storage diagnostics
                // rotate the main log. This ring adds no extra disk write per event.
                if (event.startsWith("browser.touch.") || event == "browser.pane.swipe" ||
                    event.startsWith("browser.back.") || event.startsWith("navigation.back.") || event.startsWith("ui.")) {
                    gestureTrace.addLast(safeDescription)
                    gestureTraceChars += safeDescription.length
                    while (gestureTrace.size > 1024 || gestureTraceChars > 256 * 1024) {
                        gestureTraceChars -= gestureTrace.removeFirst().length
                    }
                }
                pending.addLast(safeDescription)
                pendingChars += safeDescription.length
                while (pendingChars > 2 * 1024 * 1024 && pending.size > 1) {
                    pendingChars -= pending.removeFirst().length
                    dropped++
                }
                if (!drainQueued) {
                    drainQueued = true
                    logWriter.execute { drainPending(context) }
                }
            }
            // Only the fatal-crash path waits for persistence; touch/UI callers
            // enqueue memory records and never wait for filesystem writes.
            if (event == "uncaught") runCatching {
                logWriter.submit { drainPending(context) }.get(1500, TimeUnit.MILLISECONDS)
            }
        }
    }
    private fun drainPending(context: Context) {
        val batch = synchronized(lock) {
            drainQueued = false
            buildString {
                if (dropped > 0) { append("diagnostics.queue dropped=$dropped records\n"); dropped = 0 }
                while (pending.isNotEmpty()) append(pending.removeFirst())
                pendingChars = 0
            }
        }
        if (batch.isEmpty()) return
        runCatching {
            synchronized(fileLock) {
                val log = file(context)
                if (log.length() > 1024 * 1024) log.writeText(log.readText().takeLast(512 * 1024))
                log.appendText(batch)
            }
        }
        publishAsync()
    }
    fun snapshot(context: Context): String {
        val disk = runCatching { logWriter.submit<String> {
            drainPending(context)
            synchronized(fileLock) { file(context).readText() }
        }.get() }.getOrDefault("")
        return synchronized(lock) {
        buildString {
            append(disk)
            if (gestureTrace.isNotEmpty()) {
                append("\n--- Retained gesture trace (current process; may duplicate main log events) ---\n")
                gestureTrace.forEach { append(it) }
            }
        }
        }
    }
    fun destination(context: Context): Uri? = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        .getString("output_uri", null)?.let(Uri::parse)
    fun setDestination(context: Context, uri: Uri) {
        init(context)
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().putString("output_uri", uri.toString()).apply()
        record("diagnostics", "output folder selected")
    }
    fun writeNow(context: Context): Result<Unit> {
        synchronized(publishLock) {
            publishGeneration++
            pendingPublish?.cancel(false)
            pendingPublish = null
        }
        return runCatching {
        val uri = destination(context) ?: error("Choose a log folder first")
        val bytes = snapshot(context).toByteArray(Charsets.UTF_8)
        muted.set(true)
        try {
            val provider = providerResolver?.invoke(context) ?: error("No diagnostic storage provider was configured by the host")
            val ref = provider.refForUri(uri) ?: error("The selected log destination is unsupported")
            when (val result = provider.openWrite(ref)) {
                is com.infinifolder.model.ProviderResult.Success -> result.value.use { it.write(bytes); it.flush() }
                is com.infinifolder.model.ProviderResult.Failure -> error(result.message)
                is com.infinifolder.model.ProviderResult.Unsupported -> error("The selected log file is not writable")
            }
        } finally { muted.set(false) }
        lastExportError = null
    }.onFailure { lastExportError = sanitize(it.message ?: "Could not write diagnostic log") }
    }
    private fun publishAsync() {
        val context = app ?: return
        if (destination(context) == null) return
        synchronized(publishLock) {
            pendingPublish?.cancel(false)
            val generation = ++publishGeneration
            // Export once the current burst of diagnostic events has settled. Copying
            // the entire log to SAF after every event can dominate storage operations.
            pendingPublish = worker.schedule({
                val shouldPublish = synchronized(publishLock) {
                    if (publishGeneration != generation) false
                    else { pendingPublish = null; true }
                }
                if (!shouldPublish) return@schedule
                writeNow(context)
            }, 5, TimeUnit.SECONDS)
        }
    }
}
