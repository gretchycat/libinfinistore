package com.infinifolder.storage

import com.infinifolder.model.*
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Recovery is explicit: never replay a pending write automatically over a potentially changed source. */
object SaveRecovery {
    val activeSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    data class Record(val directory: File, val ref: ResourceRef, val state: String, val revision: ContentRevision) {
        val original get() = File(directory, "original.bin")
    }

    fun record(directory: File, ref: ResourceRef, state: String, revision: ContentRevision) {
        val properties = Properties().apply {
            setProperty("provider", ref.providerId)
            setProperty("resource", ref.documentId)
            setProperty("state", state)
            setProperty("size", revision.size.toString())
            setProperty("sha256", revision.sha256)
        }
        val temp = File(directory, "resource.properties.tmp")
        temp.outputStream().use { output -> properties.store(output, "Document recovery"); output.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), File(directory, "resource.properties").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun pending(root: File): List<Record> = root.listFiles().orEmpty().filter { it.isDirectory && it.name !in activeSessions }.mapNotNull { directory ->
        runCatching {
            val properties = Properties().apply { File(directory, "resource.properties").inputStream().use(::load) }
            val original = File(directory, "original.bin")
            if (!original.isFile) return@runCatching null
            val actual = original.inputStream().use(ContentRevision::read)
            val expected = properties.getProperty("sha256")
            // Support recovery copies made by the previous version, which recorded identity only.
            if (expected != null && (actual.sha256 != expected || actual.size.toString() != properties.getProperty("size"))) return@runCatching null
            Record(directory, ResourceRef(properties.getProperty("provider") ?: return@runCatching null,
                properties.getProperty("resource") ?: return@runCatching null), properties.getProperty("state", "interrupted"), actual)
        }.getOrNull()
    }.sortedBy { it.directory.lastModified() }

    fun restore(provider: StorageProvider, record: Record, expectedCurrent: ContentRevision, root: File): ProviderResult<Unit> {
        if (record.original.inputStream().use(ContentRevision::read) != record.revision) return ProviderResult.Failure("Recovery copy failed verification")
        val result = ContentReplacement.replace(provider, record.ref, record.original.readBytes(), root, expectedCurrent)
        if (result is ProviderResult.Success) record.directory.deleteRecursively()
        return result
    }
}
