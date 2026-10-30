package com.infinifolder.storage

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Shared disposable disk cache. Working edits, transfer scratch and recovery copies do not live here. */
class CacheCapacityException(message: String) : IOException(message)

class DiskCache private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("disk_cache_policy", Context.MODE_PRIVATE)
    val directory = File(context.applicationContext.cacheDir, "managed-cache-v1").apply { mkdirs() }
    internal class Entry(val dir: File, var bytes: Long, var used: Long, var pins: Int = 0) {
        var key: String? = File(dir, ".key").takeIf { it.isFile }?.readText()
    }
    private val entries = mutableListOf<Entry>()
    val budgetBytes: Long get() = prefs.getLong("budget", DEFAULT_BUDGET)
    // Independent of the aggregate budget; zero means no additional per-file restriction.
    val individualLimitBytes: Long get() = prefs.getLong("individual", 0L)
    val idleExpiryMillis: Long get() = prefs.getLong("idle_expiry", 6L * 60 * 60 * 1000)

    init {
        directory.listFiles()?.filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }?.forEach { dir ->
            if (!File(dir, ".ready").isFile) dir.deleteRecursively()
            else entries.add(Entry(dir, size(dir), File(dir, ".used").lastModified()))
        }
        // Adopt legacy disposable internal read caches without copying their payloads.
        // Transfer/edit/recovery directories are deliberately excluded.
        val legacy = listOf("media_staging", "sftp_cache", "ftp_cache", "smb_cache", "app_icons")
            .map { File(context.applicationContext.cacheDir, it) }
        val disc = File(context.applicationContext.cacheDir, "archive_temp").listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("disc_cache_") && it.extension == "raw" }
        val oldFiles = legacy.flatMap { dir -> if (dir.isDirectory) dir.walkTopDown().filter {
            it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath())
        }.toList() else emptyList() } + disc
        oldFiles.sortedBy { it.lastModified() }.forEach { file ->
            val dir = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
            val payload = File(dir, "legacy-${file.name.takeLast(140)}")
            val used = file.lastModified()
            if (file.renameTo(payload)) {
                payload.setReadOnly()
                File(dir, ".ready").writeText("ready")
                File(dir, ".name").writeText(payload.name)
                File(dir, ".used").apply { writeText(""); setLastModified(used) }
                entries.add(Entry(dir, payload.length(), used))
            } else dir.deleteRecursively()
        }
        synchronized(this) { makeRoom(0) }
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "IdleCacheExpiry").apply { isDaemon = true }
        }.scheduleWithFixedDelay({ runCatching { synchronized(this) { makeRoom(0) } } }, 15, 15, java.util.concurrent.TimeUnit.MINUTES)
    }

    fun isManaged(file: File): Boolean = file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())

    @Synchronized fun usage(): Pair<Long, Long> = entries.sumOf { it.bytes } to entries.filter { it.pins > 0 }.sumOf { it.bytes }

    @Synchronized fun configure(budget: Long, individual: Long, expiryMillis: Long = idleExpiryMillis) {
        require(budget > 0 && individual >= 0 && expiryMillis >= 0)
        prefs.edit().putLong("budget", budget).putLong("individual", individual).putLong("idle_expiry", expiryMillis).apply()
        makeRoom(0) // Active leases may temporarily exceed a newly reduced budget.
    }

    private fun size(dir: File): Long = dir.walkTopDown().onEnter {
        !java.nio.file.Files.isSymbolicLink(it.toPath())
    }.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
        !(it.parentFile == dir && it.name in setOf(".ready", ".name", ".key", ".used")) }.sumOf { it.length() }

    private fun touch(entry: Entry) {
        entry.used = System.currentTimeMillis()
        File(entry.dir, ".used").apply { if (!exists()) createNewFile(); setLastModified(entry.used) }
    }

    private fun makeRoom(extra: Long): Boolean {
        entries.removeAll { entry ->
            if (!entry.dir.exists()) true
            else if (File(entry.dir, ".ready").isFile && File(entry.dir, ".name").isFile &&
                !File(entry.dir, File(entry.dir, ".name").readText()).exists()) {
                entry.dir.deleteRecursively(); true
            } else false
        }
        val now = System.currentTimeMillis()
        val expiry = idleExpiryMillis
        for (entry in entries.filter { it.pins == 0 && expiry > 0 && now - it.used >= expiry }) {
            if (entry.dir.deleteRecursively()) {
                DiagnosticLog.record("cache.expire", "bytes=${entry.bytes} idleMs=${now - entry.used}")
                entries.remove(entry)
            }
        }
        var total = entries.sumOf { it.bytes }
        for (entry in entries.filter { it.pins == 0 }.sortedBy { it.used }) {
            if (extra <= budgetBytes && total <= budgetBytes - extra) break
            if (entry.dir.deleteRecursively()) {
                DiagnosticLog.record("cache.evict", "bytes=${entry.bytes} lastUsed=${entry.used}")
                total -= entry.bytes; entries.remove(entry)
            }
        }
        return extra <= budgetBytes && total <= budgetBytes - extra
    }

    private fun rejectSize(bytes: Long) {
        if (bytes > budgetBytes) throw CacheCapacityException("This file needs more than the ${budgetBytes / MIB} MB total cache budget. Copy it to a storage folder instead.")
        if (individualLimitBytes > 0 && bytes > individualLimitBytes)
            throw CacheCapacityException("This file exceeds the separate ${individualLimitBytes / MIB} MB individual cache-file limit. Copy it to a storage folder instead.")
    }

    @Synchronized fun allocate(name: String, expectedBytes: Long? = null): Lease {
        val expected = (expectedBytes ?: 0).coerceAtLeast(0)
        // Always try idle-entry eviction before presenting either capacity choice.
        val fits = makeRoom(expected)
        rejectSize(expected)
        if (!fits) throw CacheCapacityException("The cache is occupied by files still in use. Close a viewer or copy this file to a storage folder instead.")
        val dir = File(directory, UUID.randomUUID().toString()).apply { if (!mkdir()) throw IOException("Cannot create cache entry") }
        val entry = Entry(dir, expected, System.currentTimeMillis(), 1)
        entries.add(entry)
        touch(entry)
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .takeLast(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "cached-file"
        return Lease(entry, File(dir, if (safeName.startsWith(".")) "file$safeName" else safeName))
    }

    @Synchronized fun acquire(key: String): Lease? {
        makeRoom(0)
        val entry = entries.firstOrNull { it.key == key && File(it.dir, ".ready").isFile } ?: return null
        val name = File(entry.dir, ".name").readText()
        val file = File(entry.dir, name)
        if (!file.isFile) return null
        rejectSize(file.length())
        entry.pins++
        touch(entry)
        return Lease(entry, file)
    }

    /** Pin a managed source while a provider/device accesses it. Unmanaged user files need no lease. */
    @Synchronized fun pin(file: File): java.io.Closeable? {
        val path = file.canonicalFile.toPath()
        val cacheRoot = directory.canonicalFile.toPath()
        // Ordinary source files have no cache lease. Avoid canonicalizing every
        // managed entry for every tiny sibling PNG/link opened outside the cache.
        if (!path.startsWith(cacheRoot)) return null
        val relative = cacheRoot.relativize(path)
        if (relative.nameCount < 2) return null
        val entryName = relative.getName(0).toString()
        val entry = entries.firstOrNull { it.dir.name == entryName } ?: return null
        entry.pins++
        touch(entry)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return java.io.Closeable { if (released.compareAndSet(false, true)) synchronized(this) {
            entry.pins = (entry.pins - 1).coerceAtLeast(0); if (entry.dir.exists()) touch(entry); makeRoom(0)
        } }
    }

    inner class Lease internal constructor(private val entry: Entry, val file: File) : java.io.Closeable {
        private var closed = false
        private var completed = File(entry.dir, ".ready").isFile
        private var written = if (completed) entry.bytes else 0L
        fun reserve(bytes: Long) = synchronized(this@DiskCache) {
            check(!closed)
            val extra = (bytes - entry.bytes).coerceAtLeast(0)
            val fits = makeRoom(extra)
            rejectSize(bytes)
            if (!fits) throw CacheCapacityException("The cache is occupied by files in use. Copy this file to storage or raise the cache limit.")
            entry.bytes += extra
        }
        /** All expanded resources in this entry share its reservation and lifetime. */
        fun output(target: File = file): OutputStream {
            require(target.canonicalFile.toPath().startsWith(entry.dir.canonicalFile.toPath()))
            target.parentFile?.mkdirs()
            val out = target.outputStream()
            var fileBytes = 0L
            return object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    synchronized(this@DiskCache) {
                        check(!closed)
                        val next = Math.addExact(written, count.toLong())
                        val extra = (next - entry.bytes).coerceAtLeast(0)
                        val fits = extra == 0L || makeRoom(extra)
                        rejectSize(fileBytes + count)
                        if (next > budgetBytes) throw CacheCapacityException("This cache entry exceeds the total cache budget. Copy it to storage or raise the cache limit.")
                        if (!fits) throw CacheCapacityException("The cache is full of files in use. Copy this file to a storage folder instead.")
                        entry.bytes += extra
                        out.write(bytes, offset, count)
                        written = next
                        fileBytes += count
                    }
                }
                override fun flush() = out.flush()
                override fun close() = out.close()
            }
        }
        fun complete(key: String? = null) = synchronized(this@DiskCache) {
            check(!closed)
            entry.bytes = size(entry.dir)
            // Cached payloads are immutable. Editors must save to the original resource/recovery store.
            entry.dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { it.setReadOnly() }
            File(entry.dir, ".name").writeText(file.name)
            if (key != null) { File(entry.dir, ".key").writeText(key); entry.key = key }
            File(entry.dir, ".ready").writeText("ready")
            completed = true
            touch(entry)
        }
        override fun close() = synchronized(this@DiskCache) {
            if (closed) return@synchronized
            closed = true
            entry.pins = (entry.pins - 1).coerceAtLeast(0)
            if (!completed && entry.pins == 0) { entry.dir.deleteRecursively(); entries.remove(entry) }
            else { touch(entry); makeRoom(0) }
        }
    }

    fun stage(name: String, expectedBytes: Long? = null, key: String? = null, input: () -> java.io.InputStream): Lease {
        if (key != null) acquire(key)?.let { return it }
        val lease = allocate(name, expectedBytes)
        try {
            input().use { source -> lease.output().use { target ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    com.infinifolder.archive.RecoveryCancellation.check()
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Caching cancelled")
                    val count = source.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                }
            } }
            if (expectedBytes != null && lease.file.length() != expectedBytes) throw IOException("Source length changed while caching")
            lease.complete(key)
            return lease
        } catch (error: Throwable) { lease.close(); throw error }
    }

    companion object {
        const val MIB = 1024L * 1024L
        const val DEFAULT_BUDGET = 200L * MIB
        @Volatile private var instance: DiskCache? = null
        fun get(context: Context): DiskCache = instance ?: synchronized(this) {
            instance ?: DiskCache(context.applicationContext).also { instance = it }
        }
        fun capacityError(error: Throwable): CacheCapacityException? =
            generateSequence(error) { it.cause }.take(24).filterIsInstance<CacheCapacityException>().firstOrNull()
    }
}
