package com.infinifolder.storage

import io.github.gretchycat.infinistream.*

/** Immutable evidence from bounded content reads; this does not open a parser or execute a pipeline. */
data class StreamIdentification(val metadata: StreamMetadata, val candidates: List<FormatCandidate>,
    val legacyResult: FileMagicResult?)

object StreamFormatProbe {
    /** Borrows a positional probe. Offset/footer reads require known bounds; no shared cursor moves. */
    fun inspect(reader: ProbeReader): StreamIdentification {
        val size = reader.metadata.length
        fun at(position: Long, count: Int): ByteArray? =
            if (size != null && size >= position + count) reader.bytes(position, count) else null
        val header = reader.bytes(0, minOf(size ?: 2048L, 2048L).toInt())
        val ext = at(1080, 2)
        val iso = at(32769, 5)
        val footer = size?.takeIf { it >= 1024 }?.let { reader.bytes(it - 512, 512) }
        val ufs = listOf(65536L, 8192L, 0L, 262144L).firstNotNullOfOrNull { offset ->
            at(offset + 1372, 4)?.let { bytes -> FileMagicDetector.ufsVersion(bytes, 0) }
        }
        return identify(reader.metadata, FileMagicDetector.detectFromBytes(header, ext, iso, footer, ufs))
    }
    /** Already buffered prefix for sequential sources; callers retain/replay that prefix themselves. */
    fun inspectPrefix(metadata: StreamMetadata, prefix: ByteArray): StreamIdentification {
        require(prefix.size <= 128 * 1024)
        return identify(metadata, FileMagicDetector.detectFromBytes(prefix))
    }
    private fun identify(input: StreamMetadata, result: FileMagicResult?): StreamIdentification {
        if (result == null) return StreamIdentification(input, emptyList(), null)
        val content = ContentType(if (result.mimeType.startsWith("audio/")) DataKind.ENCODED_AUDIO else DataKind.BYTES,
            result.mimeType, "mime:${result.mimeType}")
        val mismatch = input.content.mimeType?.takeIf { it != "application/octet-stream" && it != result.mimeType }
        val metadata = input.copy(content = content, verification = Verification.PROVISIONAL,
            warnings = input.warnings + if (mismatch != null) listOf("Content header disagrees with source MIME hint: $mismatch") else emptyList())
        val confidence = if (result.mimeType == "text/plain") 0.3 else 0.8
        return StreamIdentification(metadata, listOf(FormatCandidate(content, confidence,
            "Bounded header/offset evidence: ${result.description}; parser validation still required")), result)
    }
}
