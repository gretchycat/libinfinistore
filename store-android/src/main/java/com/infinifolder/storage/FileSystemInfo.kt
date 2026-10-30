package com.infinifolder.storage

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.util.Locale

/** Unknown limits are distinct from unlimited storage. No capacity-destructive probing is performed. */
enum class FileNameRules { VFAT, DOS_8_3 }

data class FileSystemInfo(val type: String?, val maxFileSizeBytes: Long?, val evidence: String,
    val nameRules: FileNameRules? = null, val caseSensitive: Boolean? = null,
    val availableBytes: Long? = null, val readOnly: Boolean? = null,
    val allocationUnitBytes: Long? = null) {
    val displayName: String get() = when (type?.lowercase(Locale.ROOT)) {
        "vfat", "fat" -> "FAT (VFAT/FAT32 family)"
        "fat32" -> "FAT32"
        "msdos" -> "FAT (DOS short names)"
        "exfat" -> "exFAT"
        null -> "Unknown"
        else -> type ?: "Unknown"
    }
    val limitDescription: String get() = if (maxFileSizeBytes == FAT_MAX_FILE_SIZE) {
        "4 GiB minus 1 byte (4,294,967,295 bytes) per file"
    } else maxFileSizeBytes?.let { "${java.text.NumberFormat.getIntegerInstance().format(it)} bytes per file" }
        ?: "Not reported"

    val nameDescription: String get() = when (nameRules) {
        FileNameRules.VFAT -> "VFAT long names: up to 255 UTF-16 units per component; case-insensitive"
        FileNameRules.DOS_8_3 -> "DOS short names: 8-character base plus optional 3-character extension"
        null -> "Not reported"
    }

    /** Convert prohibited characters only when the destination reports its naming rules. */
    fun compatibleName(name: String): String {
        val rules = nameRules ?: return name
        val lastDot = name.lastIndexOf('.')
        val dosAllowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$%'-_@~!(){}^#&"
        val converted = name.mapIndexed { index, character ->
            when {
                character.code < 32 || character in "\"*/:<>?\\|" -> '_'
                rules == FileNameRules.DOS_8_3 && character == '.' ->
                    if (index == lastDot && index > 0 && index < name.lastIndex) '.' else '_'
                rules == FileNameRules.DOS_8_3 && character.uppercaseChar() !in dosAllowed -> '_'
                else -> character
            }
        }.toCharArray()
        // Trailing dots/spaces are prohibited too; preserve their positions as underscores.
        for (index in converted.indices.reversed()) {
            if (converted[index] != ' ' && converted[index] != '.') break
            converted[index] = '_'
        }
        return String(converted)
    }

    /** Add a collision suffix that itself satisfies the destination's naming rules. */
    fun collisionName(name: String, index: Int): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        if (nameRules == FileNameRules.DOS_8_3) {
            val suffix = "~$index"
            if (suffix.length >= 8) throw IOException("No unique DOS filename is available for '$name'")
            return stem.take(8 - suffix.length) + suffix + extension
        }
        val suffix = " ($index)"
        val stemLimit = if (nameRules == FileNameRules.VFAT) 255 - suffix.length - extension.length else stem.length
        if (stemLimit < 1) throw IOException("Cannot add a unique filename suffix to '$name' on $displayName")
        var shortened = stem.take(stemLimit)
        if (shortened.lastOrNull()?.isHighSurrogate() == true) shortened = shortened.dropLast(1)
        if (shortened.isEmpty()) throw IOException("Cannot add a unique filename suffix to '$name' on $displayName")
        return shortened + suffix + extension
    }

    fun requireName(name: String) {
        val rules = nameRules ?: return
        val reason = when {
            name.isEmpty() || name == "." || name == ".." -> "an empty or reserved path component"
            name.any { it.code < 32 || it in "\"*/:<>?\\|" } -> "a character prohibited by FAT filename rules"
            name.endsWith(' ') || name.endsWith('.') -> "a trailing space or period"
            rules == FileNameRules.VFAT && name.length > 255 -> "more than 255 UTF-16 units in one filename"
            rules == FileNameRules.DOS_8_3 && !isDosName(name) -> "a name outside DOS 8.3 format"
            else -> null
        }
        if (reason != null) throw IOException("Cannot create '$name' on $displayName: $reason. $nameDescription.")
    }
    fun requirePath(path: String) {
        path.removePrefix("/").split('/').forEach(::requireName)
    }
    private fun isDosName(name: String): Boolean {
        val parts = name.split('.')
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$%'-_@~!(){}^#&"
        return parts.size <= 2 && parts[0].length in 1..8 &&
            (parts.size == 1 || parts[1].length in 1..3) &&
            parts.all { part -> part.uppercase(Locale.ROOT).all { it in allowed } }
    }

    fun requireFileFits(name: String, sizeBytes: Long?) {
        val limit = maxFileSizeBytes ?: return
        if (sizeBytes != null && sizeBytes > limit) {
            val maximum = if (limit == FAT_MAX_FILE_SIZE) "4 GiB minus 1 byte" else "about ${StorageMessages.approximateSize(limit)}"
            throw IOException("'$name' is too large for $displayName: about ${StorageMessages.approximateSize(sizeBytes)}. " +
                "The maximum file size is $maximum. Original retained.")
        }
    }
    companion object { const val FAT_MAX_FILE_SIZE = 0xffffffffL }
}

/** Match the real volume mount when Android presents its public path through FUSE. */
object StorageFileSystems {
    data class Mount(val path: String, val type: String, val readOnly: Boolean? = null)
    private fun decode(value: String): String = Regex("\\\\([0-7]{3})").replace(value) {
        it.groupValues[1].toInt(8).toChar().toString()
    }
    fun parseMountInfo(text: String): List<Mount> = text.lineSequence().mapNotNull { line ->
        val fields = line.split(' ')
        val separator = fields.indexOf("-")
        if (separator < 6 || fields.size <= separator + 1) null
        else Mount(decode(fields[4]), fields[separator + 1].lowercase(Locale.ROOT),
            "ro" in fields[5].split(',') || "ro" in fields.getOrNull(separator + 3).orEmpty().split(','))
    }.toList()
    private fun mounts(): List<Mount> = runCatching { parseMountInfo(File("/proc/self/mountinfo").readText()) }.getOrDefault(emptyList())
    private fun info(mount: Mount): FileSystemInfo = FileSystemInfo(mount.type,
        if (mount.type in setOf("vfat", "msdos", "fat", "fat32")) FileSystemInfo.FAT_MAX_FILE_SIZE else null,
        "mountinfo:${mount.path}", nameRules = when (mount.type) {
            "msdos" -> FileNameRules.DOS_8_3
            "vfat", "fat", "fat32" -> FileNameRules.VFAT
            else -> null
        }, caseSensitive = if (mount.type in setOf("vfat", "msdos", "fat", "fat32", "exfat")) false else null,
        availableBytes = runCatching { android.os.StatFs(mount.path).availableBytes }.getOrNull(), readOnly = mount.readOnly)
    private fun volumeMount(volume: String, mounts: List<Mount>): Mount? {
        if (volume == "primary" || !volume.matches(Regex("[A-Za-z0-9_-]+"))) return null
        val paths = setOf("/mnt/media_rw/$volume", "/storage/$volume", "/mnt/runtime/default/$volume",
            "/mnt/runtime/read/$volume", "/mnt/runtime/write/$volume", "/mnt/runtime/full/$volume")
        return mounts.filter { it.path in paths && it.type !in setOf("fuse", "sdcardfs", "tmpfs") }
            .firstOrNull()
    }
    fun forExternalDocument(uri: Uri): FileSystemInfo {
        // Only this platform provider defines volume-prefixed document IDs. Other SAF IDs remain opaque.
        if (uri.authority != "com.android.externalstorage.documents")
            return FileSystemInfo(null, null, "Document provider does not report filesystem type")
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrElse {
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        } ?: return FileSystemInfo(null, null, "No external-storage volume identity")
        val volume = documentId.substringBefore(':')
        return volumeMount(volume, mounts())?.let(::info)
            ?: FileSystemInfo(null, null, "Underlying mount not visible for volume $volume")
    }
    fun forFile(file: File): FileSystemInfo {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val mounts = mounts()
        val direct = mounts.filter { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }
            .maxByOrNull { it.path.length }
        if (path.startsWith("/storage/")) {
            val volume = path.removePrefix("/storage/").substringBefore('/')
            volumeMount(volume, mounts)?.let { return info(it) }
        }
        if (direct != null && direct.type !in setOf("fuse", "sdcardfs", "tmpfs")) return info(direct)
        val type = runCatching { java.nio.file.Files.getFileStore(file.toPath()).type() }.getOrNull()
        if (type != null && type.lowercase(Locale.ROOT) !in setOf("fuse", "sdcardfs", "tmpfs")) {
            return FileSystemInfo(type, if (type.lowercase(Locale.ROOT) in setOf("vfat", "msdos", "fat", "fat32"))
                FileSystemInfo.FAT_MAX_FILE_SIZE else null, "FileStore", nameRules = when (type.lowercase(Locale.ROOT)) {
                    "msdos" -> FileNameRules.DOS_8_3
                    "vfat", "fat", "fat32" -> FileNameRules.VFAT
                    else -> null
                })
        }
        return FileSystemInfo(null, null, "Underlying filesystem not exposed by Android")
    }
}
