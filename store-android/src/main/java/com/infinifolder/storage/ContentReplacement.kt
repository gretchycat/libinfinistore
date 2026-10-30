package com.infinifolder.storage

import com.infinifolder.model.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Native atomic replacement where supported; durable original and rollback for streaming backends. */
object ContentReplacement {
    fun replace(provider: StorageProvider, ref: ResourceRef, data: ByteArray, recoveryRoot: File, expectedRevision: ContentRevision? = null): ProviderResult<Unit> =
        replaceWith(provider, ref, recoveryRoot, expectedRevision, ContentRevision.of(data),
            { provider.replaceContent(ref, data) }, { it.write(data) })

    fun replaceFromFile(provider: StorageProvider, ref: ResourceRef, file: File, recoveryRoot: File,
        expectedRevision: ContentRevision? = null, preCommitCheck: (() -> Unit)? = null): ProviderResult<Unit> = try {
        val info = (provider.fileSystemInfo(ref) as? ProviderResult.Success)?.value
        info?.requireFileFits("updated container", file.length())
        info?.availableBytes?.let { available ->
            if (file.length() > available) throw IOException("Not enough space to save the container. " +
                "About ${StorageMessages.approximateSize(file.length())} needed; ${StorageMessages.approximateSize(available)} available. Original retained.")
        }
        val expected = file.inputStream().use(ContentRevision::read)
        replaceWith(provider, ref, recoveryRoot, expectedRevision, expected,
            { provider.replaceContentFromFile(ref, file) }, { output -> file.inputStream().use { it.copyTo(output) } }, preCommitCheck)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot prepare replacement", e) }

    @Synchronized private fun replaceWith(provider: StorageProvider, ref: ResourceRef, recoveryRoot: File,
        expectedRevision: ContentRevision?, expectedContent: ContentRevision,
        nativeReplace: () -> ProviderResult<Unit>, write: (java.io.OutputStream) -> Unit,
        preCommitCheck: (() -> Unit)? = null): ProviderResult<Unit> {
        if (expectedRevision != null) {
            val current = try { openRead(provider, ref).use(ContentRevision::read) }
                catch (e: Exception) { return ProviderResult.Failure("Cannot verify original: ${e.message}", e) }
            if (current != expectedRevision) {
                DiagnosticLog.record("save.conflict", "provider=${ref.providerId}")
                return ProviderResult.Failure(RevisionConflictException().message!!, RevisionConflictException())
            }
        }
        try { preCommitCheck?.invoke() }
        catch (error: Exception) { return ProviderResult.Failure(error.message ?: "Source changed before save", error) }
        val native = nativeReplace()
        if (native !is ProviderResult.Unsupported) return native
        val session = File(recoveryRoot, UUID.randomUUID().toString())
        val original = File(session, "original.bin")
        var writeStarted = false
        SaveRecovery.activeSessions.add(session.name)
        return try {
            if (!session.mkdirs()) throw IOException("Cannot create recovery copy")
            val originalSize = (provider.stat(ref) as? ProviderResult.Success)?.value?.metadata?.sizeBytes
            if (originalSize != null && originalSize > session.usableSpace)
                throw IOException("Not enough space to preserve the original container for recovery. Original retained.")
            openRead(provider, ref).use { input ->
                original.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        if (session.usableSpace < n.toLong() + 1024 * 1024)
                            throw IOException("Not enough space for the original container recovery copy. Original retained.")
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            val preserved = original.inputStream().use(ContentRevision::read)
            SaveRecovery.record(session, ref, "prepared", preserved)
            if ((expectedRevision != null && preserved != expectedRevision) || openRead(provider, ref).use(ContentRevision::read) != preserved) {
                throw RevisionConflictException()
            }
            preCommitCheck?.invoke()
            SaveRecovery.record(session, ref, "writing", preserved)
            writeStarted = true
            openWrite(provider, ref).use { output -> write(output); output.flush() }
            val actual = openRead(provider, ref).use(ContentRevision::read)
            if (actual != expectedContent) throw IOException("Saved content failed verification")
            session.deleteRecursively()
            DiagnosticLog.record("save.verified", "provider=${ref.providerId} bytes=${expectedContent.size}")
            ProviderResult.Success(Unit)
        } catch (e: Exception) {
            DiagnosticLog.record("save.failed", "provider=${ref.providerId} recovery=${session.name}", e)
            if (!writeStarted) {
                session.deleteRecursively()
                ProviderResult.Failure(e.message ?: "Cannot preserve original", e)
            } else {
                val restored = runCatching {
                    original.inputStream().use { input -> openWrite(provider, ref).use { output -> input.copyTo(output); output.flush() } }
                    val expected = original.inputStream().use { hash(it) }
                    check(openRead(provider, ref).use { hash(it) }.contentEquals(expected))
                }.isSuccess
                runCatching { SaveRecovery.record(session, ref, if (restored) "restored" else "interrupted", original.inputStream().use(ContentRevision::read)) }
                ProviderResult.Failure("Save failed. " + (if (restored) "Original restored. " else "Original could not be restored. ") +
                    "Recovery copy retained at ${original.absolutePath}: ${e.message}", e)
            }
        } finally { SaveRecovery.activeSessions.remove(session.name) }
    }

    private fun openRead(provider: StorageProvider, ref: ResourceRef) = when (val result = provider.openRead(ref)) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Failure -> throw IOException(result.message, result.cause)
        is ProviderResult.Unsupported -> throw IOException("Original cannot be read for recovery")
    }
    private fun openWrite(provider: StorageProvider, ref: ResourceRef) = when (val result = provider.openWrite(ref)) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Failure -> throw IOException(result.message, result.cause)
        is ProviderResult.Unsupported -> throw IOException("Resource is read-only")
    }
    private fun hash(input: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest()
    }
}
