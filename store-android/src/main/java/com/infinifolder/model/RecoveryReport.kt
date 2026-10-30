package com.infinifolder.model

import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/** Grades describe original-byte confidence, not merely whether the storage read succeeded. */
enum class RecoveryGrade { GOOD, POSSIBLY_GOOD, POSSIBLY_BAD, OVERWRITTEN, UNAVAILABLE }
enum class RecoveryDisposition { COPIED_UNVERIFIED, ZERO_FILLED }
data class RecoveryRange(val offset: Long, val length: Long, val grade: RecoveryGrade,
    val disposition: RecoveryDisposition, val reason: String)

data class RecoveryReport(
    val candidate: String,
    val declaredSize: Long,
    val fileSystem: String,
    val ranges: List<RecoveryRange>,
    val sha256: String,
    val note: String,
    val magicMime: String? = null,
    val magicDescription: String? = null,
    val expectedMime: String? = null,
    val expectedContainerMime: String? = null,
    val magicMismatch: Boolean? = null,
    val sourceImage: String? = null,
    val partitionOffset: Long = 0,
    val ioRetryUnitBytes: Int = 512,
    val magicInspectedBytes: Int = 0
) {
    init {
        require(declaredSize >= 0) { "Invalid recovery size" }
        var next = 0L
        for (range in ranges) {
            require(range.offset == next && range.length > 0 && range.length <= declaredSize - next) { "Recovery ranges must cover the output exactly once" }
            next += range.length
        }
        require(next == declaredSize) { "Recovery report has missing byte ranges" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid recovery output digest" }
    }
    fun toJson(outputName: String): String = JSONObject().apply {
        put("format", "infinifolder-recovery-report"); put("version", 1)
        put("output_file", outputName); put("candidate", candidate); put("declared_size_bytes", declaredSize)
        put("filesystem", fileSystem); put("source_image", sourceImage ?: JSONObject.NULL)
        put("partition_offset_bytes", partitionOffset); put("output_sha256", sha256)
        put("offset_unit", "bytes"); put("range_convention", "offset inclusive, end_exclusive exclusive")
        put("zero_fill_byte", 0); put("io_retry_unit_bytes", ioRetryUnitBytes); put("original_contents_verified", false)
        put("allocation_assumption", "First cluster comes from the deleted record; later clusters assume contiguous allocation. Original fragmentation is unknown.")
        put("note", note)
        put("grade_definitions", JSONObject().apply {
            put("good", "Would require independent original-content evidence; this FAT-only recovery cannot assign it.")
            put("possibly_good", "Readable, stable bytes in the first recorded cluster; original contents are not verified.")
            put("possibly_bad", "Later bytes assume contiguous allocation, or original bytes may have been replaced by a current allocation. See disposition and reason.")
            put("overwritten", "Would require independent evidence of original-byte replacement. Allocation alone does not prove overwrite; this recovery does not assign it.")
            put("unavailable", "Unreadable or outside the validated volume; original contents unknown.")
        })
        put("byte_counts_by_grade", JSONObject().apply { RecoveryGrade.entries.forEach { grade ->
            put(grade.name.lowercase(java.util.Locale.ROOT), ranges.filter { it.grade == grade }.sumOf { it.length })
        } })
        put("copied_bytes", ranges.filter { it.disposition == RecoveryDisposition.COPIED_UNVERIFIED }.sumOf { it.length })
        put("zero_filled_bytes", ranges.filter { it.disposition == RecoveryDisposition.ZERO_FILLED }.sumOf { it.length })
        put("file_type_hint", JSONObject().apply {
            put("inspected_copied_prefix_bytes", magicInspectedBytes)
            put("detected_mime", magicMime ?: JSONObject.NULL); put("description", magicDescription ?: JSONObject.NULL)
            put("filename_mime", expectedMime ?: JSONObject.NULL)
            put("filename_container_mime", expectedContainerMime ?: JSONObject.NULL); put("mime_mismatch", magicMismatch ?: JSONObject.NULL)
            put("interpretation", "Shared magic/type detection is a header heuristic, not a content check. A match cannot verify the payload; no confidence grade is upgraded.")
        })
        put("ranges", JSONArray().apply { ranges.forEach { range -> put(JSONObject().apply {
            put("offset", range.offset); put("length", range.length); put("end_exclusive", range.offset + range.length)
            put("grade", range.grade.name.lowercase(java.util.Locale.ROOT)); put("disposition", range.disposition.name.lowercase(java.util.Locale.ROOT)); put("reason", range.reason)
        }) } })
    }.toString(2)
}

/** A privately staged payload. Closing it deletes the staging file, including on failed publication. */
data class RecoveryExport(val input: InputStream, val report: RecoveryReport) : java.io.Closeable {
    override fun close() = input.close()
}
