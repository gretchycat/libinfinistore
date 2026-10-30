package com.infinifolder.archive

import java.util.Locale

object ArchiveFormats {
    private val DISC_BASE_EXTENSIONS = setOf(
        "iso", "cdr",
        "img", "raw", "ima", "flp",
        "fat", "vfat", "exfat",
        "ext2", "ext3", "ext4",
        "ufs", "ufs1", "ufs2", "ffs",
        "ntfs", "hfs", "hfsplus", "hfsx",
        "vdi", "qcow2", "vmdk", "vhd", "vhdx",
        "esp", "efi",
        "mbr", "gpt", "apm", "dd", "dsk", "dump", "bin"
    )

    private val COMPRESSION_SUFFIXES = listOf(
        "gz", "bz2", "bz", "xz", "zst", "lzma", "z"
    )

    private val DISC_COMPOUND_EXTENSIONS: List<String> = DISC_BASE_EXTENSIONS.flatMap { base ->
        COMPRESSION_SUFFIXES.map { comp -> ".$base.$comp" }
    }

    private val TAR_COMPOUND_EXTENSIONS = listOf(
        ".tar.gz", ".tar.bz2", ".tar.bz", ".tar.xz", ".tar.zst", ".tar.lzma", ".tar.z"
    )

    private val COMPOUND_EXTENSIONS = TAR_COMPOUND_EXTENSIONS + DISC_COMPOUND_EXTENSIONS

    val PLAYLIST_EXTENSIONS = setOf(
        "m3u", "m3u8", "pls", "xspf", "cue"
    )

    private val SIMPLE_EXTENSIONS = setOf(
        "zip", "jar", "war", "ear", "apk", "xapk", "apkm", "aab", "aar", "love", "ipa", "cbz",
        "tar", "tgz", "tbz", "tbz2", "txz", "tzst", "tlz", "taz", "cbt",
        "7z", "cb7", "rar", "cbr", "arj", "cab", "zst",
        "cpio",
        "ar", "deb"
    ) + DISC_BASE_EXTENSIONS + PLAYLIST_EXTENSIONS

    fun isPlaylist(name: String, mimeType: String = ""): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in PLAYLIST_EXTENSIONS
    }

    private val DISC_MIME_TYPES = setOf(
        "application/x-iso9660-image",
        "application/x-cd-image",
        "application/x-raw-disk-image",
        "application/x-raw-disk-image-xz-compressed",
        "application/vnd.efi.iso",
        "application/vnd.efi.img",
        "application/x-virtualbox-vdi",
        "application/x-qemu-disk",
        "application/x-vmdk",
        "application/x-vhd",
        "application/x-vhdx"
    )

    private val ARCHIVE_MIME_TYPES = setOf(
        "application/zip",
        "application/vnd.rar", "application/x-rar-compressed", "application/x-arj",
        "application/vnd.ms-cab-compressed", "application/x-cabinet",
        "application/x-zip-compressed",
        "application/java-archive",
        "application/vnd.android.package-archive",
        "application/x-tar",
        "application/tar",
        "application/x-gtar",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip",
        "application/x-bzip2",
        "application/x-xz",
        "application/x-compress", "application/zstd",
        "application/x-7z-compressed",
        "application/x-cpio",
        "application/x-archive",
        "application/x-debian-package", "application/vnd.debian.binary-package"
    ) + DISC_MIME_TYPES

    fun isArchive(name: String, mimeType: String = ""): Boolean {
        if (com.infinifolder.mime.MimeRegistry.normalize(mimeType) == "vnd.android.document/directory") return false
        if (NativeArchiveNames.isSupported(name)) return true
        if (com.infinifolder.mime.MimeRegistry.packageFormat(name, mimeType) != null) return true
        val lowerName = name.lowercase(Locale.ROOT)
        if (COMPOUND_EXTENSIONS.any { lowerName.endsWith(it) }) return true
        val ext = lowerName.substringAfterLast('.', "")
        if (SIMPLE_EXTENSIONS.contains(ext)) return true
        if (mimeType.isNotEmpty() && ARCHIVE_MIME_TYPES.contains(com.infinifolder.mime.MimeRegistry.normalize(mimeType))) return true
        return false
    }

    fun isPackagedFormat(name: String, mimeType: String = ""): Boolean =
        com.infinifolder.mime.MimeRegistry.packageFormat(name, mimeType) != null

    fun packageMimeType(name: String): String? = com.infinifolder.mime.MimeRegistry.packageFormat(name)?.mime

    fun containerExtension(name: String, mimeType: String = ""): String =
        com.infinifolder.mime.MimeRegistry.packageFormat(name, mimeType)?.container ?: archiveExtension(name)

    fun isDefaultBrowsableArchive(name: String, mimeType: String = ""): Boolean {
        return isArchive(name, mimeType) && !isPackagedFormat(name, mimeType) && !isPlaylist(name, mimeType)
    }

    fun isDiscImage(name: String, mimeType: String = ""): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        if (DISC_COMPOUND_EXTENSIONS.any { lowerName.endsWith(it) }) return true
        val ext = lowerName.substringAfterLast('.', "")
        if (DISC_BASE_EXTENSIONS.contains(ext)) return true
        if (mimeType.isNotEmpty() && DISC_MIME_TYPES.contains(com.infinifolder.mime.MimeRegistry.normalize(mimeType))) return true
        return false
    }

    fun archiveExtension(name: String): String {
        val lowerName = name.lowercase(Locale.ROOT)
        for (compound in COMPOUND_EXTENSIONS) {
            if (lowerName.endsWith(compound)) return compound.removePrefix(".")
        }
        return lowerName.substringAfterLast('.', "")
    }
}
