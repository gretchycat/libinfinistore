package com.infinifolder.mime

import java.util.Locale

/** Application identity and its packaging are separate parts of one classification. */
object MimeRegistry {
    data class PackageFormat(val mime: String, val container: String)
    val packages = mapOf(
        "apk" to PackageFormat("application/vnd.android.package-archive", "zip"),
        "xapk" to PackageFormat("application/x-xapk", "zip"),
        "apkm" to PackageFormat("application/vnd.apkm", "zip"),
        "aab" to PackageFormat("application/x-android-app-bundle", "zip"),
        "jar" to PackageFormat("application/java-archive", "zip"),
        "war" to PackageFormat("application/java-archive", "zip"),
        "ear" to PackageFormat("application/java-archive", "zip"),
        "aar" to PackageFormat("application/java-archive", "zip"),
        "love" to PackageFormat("application/x-love-game", "zip"),
        "ipa" to PackageFormat("application/x-ios-app", "zip"),
        "epub" to PackageFormat("application/epub+zip", "zip"),
        "docx" to PackageFormat("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "zip"),
        "xlsx" to PackageFormat("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "zip"),
        "pptx" to PackageFormat("application/vnd.openxmlformats-officedocument.presentationml.presentation", "zip"),
        "odt" to PackageFormat("application/vnd.oasis.opendocument.text", "zip"),
        "ods" to PackageFormat("application/vnd.oasis.opendocument.spreadsheet", "zip"),
        "odp" to PackageFormat("application/vnd.oasis.opendocument.presentation", "zip")
    )
    private val aliases = mapOf("application/x-java-archive" to "application/java-archive", "application/x-zip-compressed" to "application/zip",
        "application/x-gzip" to "application/gzip", "text/xml" to "application/xml", "text/x-markdown" to "text/markdown")
    private val extensions = mapOf(
        "incomplete" to "application/vnd.infinifolder.torrent-partial",
        "txt" to "text/plain", "log" to "text/plain", "cfg" to "text/plain", "conf" to "text/plain", "ini" to "text/plain", "properties" to "text/plain",
        "md" to "text/markdown", "markdown" to "text/markdown", "mdown" to "text/markdown", "mkd" to "text/markdown", "mkdn" to "text/markdown",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values", "html" to "text/html", "htm" to "text/html", "xhtml" to "application/xhtml+xml",
        "xml" to "application/xml", "json" to "application/json", "yaml" to "application/yaml", "yml" to "application/yaml",
        "ts" to "text/typescript", "sh" to "application/x-sh", "py" to "text/x-python", "kt" to "text/x-kotlin", "kts" to "text/x-kotlin", "lua" to "text/x-lua", "js" to "application/javascript",
        "pdf" to "application/pdf", "png" to "image/png", "apng" to "image/apng", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "svg" to "image/svg+xml", "svgz" to "image/svg+xml", "bmp" to "image/bmp", "dib" to "image/bmp", "wbmp" to "image/vnd.wap.wbmp", "ico" to "image/x-icon", "cur" to "image/x-win-bitmap",
        "jxl" to "image/jxl", "jp2" to "image/jp2", "j2k" to "image/jp2", "jpf" to "image/jpx", "jpx" to "image/jpx", "j2c" to "image/jp2",
        "tga" to "image/x-tga", "icb" to "image/x-tga", "vda" to "image/x-tga", "vst" to "image/x-tga",
        "avif" to "image/avif", "avifs" to "image/avif", "heic" to "image/heic", "heif" to "image/heif", "heics" to "image/heic", "heifs" to "image/heif", "hif" to "image/heif",
        "dng" to "image/x-adobe-dng", "tif" to "image/tiff", "tiff" to "image/tiff",
        "qoi" to "image/x-qoi", "ff" to "image/x-farbfeld",
        "pbm" to "image/x-portable-bitmap", "pgm" to "image/x-portable-graymap", "ppm" to "image/x-portable-pixmap", "pnm" to "image/x-portable-anymap", "pam" to "image/x-portable-arbitrarymap", "pfm" to "image/x-portable-floatmap",
        "xbm" to "image/x-xbitmap", "xpm" to "image/x-xpixmap", "xwd" to "image/x-xwindowdump",
        "pcx" to "image/vnd.zbrush.pcx", "dcx" to "image/x-dcx",
        "ora" to "image/openraster", "kra" to "image/x-krita", "psd" to "image/vnd.adobe.photoshop", "psb" to "image/vnd.adobe.photoshop", "xcf" to "image/x-xcf", "ase" to "image/x-aseprite", "aseprite" to "image/x-aseprite",
        "iff" to "image/x-ilbm", "ilbm" to "image/x-ilbm", "lbm" to "image/x-ilbm",
        "neo" to "image/x-neochrome", "pi1" to "image/x-degas", "pi2" to "image/x-degas", "pi3" to "image/x-degas", "pc1" to "image/x-degas", "pc2" to "image/x-degas", "pc3" to "image/x-degas",
        "scr" to "image/x-zx-spectrum-scr", "kla" to "image/x-koala", "koa" to "image/x-koala",
        "icns" to "image/icns", "pntg" to "image/x-macpaint", "mac" to "image/x-macpaint", "pct" to "image/x-pict", "pict" to "image/x-pict",
        "ras" to "image/x-cmu-raster", "sun" to "image/x-cmu-raster", "sgi" to "image/x-sgi", "rgb" to "image/x-rgb", "bw" to "image/x-sgi", "rgba" to "image/x-sgi",
        "pdb" to "image/x-palm-pdb", "otb" to "image/x-ota-bitmap", "sixel" to "image/x-sixel", "six" to "image/x-sixel",
        "hdr" to "image/vnd.radiance", "exr" to "image/x-exr", "dds" to "image/vnd.ms-dds", "ktx" to "image/ktx",
        "fits" to "image/fits", "fit" to "image/fits", "fts" to "image/fits", "dcm" to "image/dicom", "dicom" to "image/dicom",
        "wmf" to "image/wmf", "wmz" to "image/wmf", "ps" to "image/x-eps", "eps" to "image/x-eps", "ept" to "image/x-eps", "ans" to "image/x-ansi",
        "mp3" to "audio/mpeg", "mp2" to "audio/mpeg", "mp1" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "oga" to "audio/ogg", "flac" to "audio/flac", "opus" to "audio/opus", "m4a" to "audio/mp4",
        "aac" to "audio/aac", "ac3" to "audio/ac3", "eac3" to "audio/eac3", "dts" to "audio/vnd.dts",
        "wma" to "audio/x-ms-wma", "alac" to "audio/alac", "m4b" to "audio/mp4", "m4r" to "audio/mp4", "3ga" to "audio/3gpp",
        "mka" to "audio/x-matroska", "weba" to "audio/webm",
        "au" to "audio/basic", "snd" to "audio/basic",
        "aiff" to "audio/x-aiff", "aif" to "audio/x-aiff", "aifc" to "audio/x-aiff",
        "voc" to "audio/x-voc", "8svx" to "audio/x-8svx", "svx" to "audio/x-8svx", "16sv" to "audio/x-16sv", "maud" to "audio/x-maud",
        "w64" to "audio/x-wave64", "rf64" to "audio/x-rf64", "caf" to "audio/x-caf",
        "avr" to "audio/x-avr", "ircam" to "audio/x-ircam", "sph" to "audio/x-nist-sphere", "pvf" to "audio/x-pvf", "wve" to "audio/x-wve", "htk" to "audio/x-htk",
        "adx" to "audio/x-adx", "amr" to "audio/amr", "awb" to "audio/amr-wb", "gsm" to "audio/x-gsm", "g722" to "audio/x-g722",
        "mid" to "audio/midi", "midi" to "audio/midi", "kar" to "audio/midi", "rmi" to "audio/midi",
        "sf2" to "audio/x-soundfont", "sfz" to "audio/x-sfz", "pat" to "audio/x-gus-patch",
        "mod" to "audio/x-mod", "s3m" to "audio/x-s3m", "xm" to "audio/x-xm", "it" to "audio/x-it",
        "dro" to "audio/x-dro", "imf" to "audio/x-imf", "vgm" to "audio/x-vgm", "vgz" to "audio/x-vgm", "ym" to "audio/x-ym",
        "nsf" to "audio/x-nsf", "spc" to "audio/x-spc", "sid" to "audio/x-sid",
        "dsf" to "audio/x-dsf", "dff" to "audio/x-dff", "wv" to "audio/x-wavpack", "tta" to "audio/x-tta", "ra" to "audio/x-pn-realaudio",
        "mlp" to "audio/x-mlp", "thd" to "audio/x-truehd", "cdda" to "audio/x-cdda",
        "m3u" to "audio/x-mpegurl", "m3u8" to "audio/x-mpegurl", "pls" to "audio/x-scpls", "xspf" to "application/xspf+xml", "cue" to "application/x-cue",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "zip" to "application/zip", "cbz" to "application/zip", "tar" to "application/x-tar", "cbt" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2", "tbz" to "application/x-bzip2", "tbz2" to "application/x-bzip2", "xz" to "application/x-xz", "txz" to "application/x-xz",
        "7z" to "application/x-7z-compressed", "cb7" to "application/x-7z-compressed", "cpio" to "application/x-cpio", "ar" to "application/x-archive", "deb" to "application/vnd.debian.binary-package",
        "iso" to "application/x-iso9660-image", "cdr" to "application/x-iso9660-image", "img" to "application/x-raw-disk-image", "raw" to "application/x-raw-disk-image", "bin" to "application/x-raw-disk-image",
        "vdi" to "application/x-virtualbox-vdi", "qcow2" to "application/x-qemu-disk", "vmdk" to "application/x-vmdk", "vhd" to "application/x-vhd", "vhdx" to "application/x-vhdx",
        "ufs" to "application/x-raw-disk-image", "ufs1" to "application/x-raw-disk-image", "ufs2" to "application/x-raw-disk-image", "ffs" to "application/x-raw-disk-image",
        "zst" to "application/zstd", "tzst" to "application/zstd",
        "cab" to "application/vnd.ms-cab-compressed",
        "rar" to "application/vnd.rar", "cbr" to "application/vnd.rar", "arj" to "application/x-arj",
        "ntfs" to "application/x-raw-disk-image",
        "hfs" to "application/x-raw-disk-image", "hfsplus" to "application/x-raw-disk-image", "hfsx" to "application/x-raw-disk-image",
        "taz" to "application/x-compress", "z" to "application/x-compress",
        "lnk" to "application/x-ms-shortcut", "iflink" to "application/vnd.infinifolder.shortcut+json"
    )
    fun normalize(mime: String?): String {
        val value = mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return aliases[value] ?: value.ifEmpty { "application/octet-stream" }
    }
    fun packageFormat(name: String, mime: String? = null): PackageFormat? {
        if (normalize(mime) == "vnd.android.document/directory") return null
        return packages[name.substringAfterLast('.', "").lowercase(Locale.ROOT)]
            ?: packages.values.firstOrNull { it.mime == normalize(mime) }
    }
    fun guess(name: String, directory: Boolean = false): String {
        if (directory) return "vnd.android.document/directory"
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.length == 3 && extension.drop(1).all(Char::isDigit)) {
            if (extension[0] == 'r') return "application/vnd.rar"
            if (extension[0] == 'a') return "application/x-arj"
        }
        return packages[extension]?.mime ?: extensions[extension] ?: runCatching {
            android.webkit.MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension)
        }.getOrNull()?.let(::normalize) ?: "application/octet-stream"
    }
    fun effective(name: String, supplied: String?, detected: String? = null): String {
        if (normalize(supplied) == "vnd.android.document/directory") return "vnd.android.document/directory"
        if (name.endsWith(".incomplete", true)) return "application/vnd.infinifolder.torrent-partial"
        if (normalize(supplied) == "application/vnd.infinifolder.partial-download") return normalize(supplied)
        packageFormat(name, supplied)?.let { return it.mime }
        val stated = normalize(supplied)
        val sniffed = normalize(detected)
        if (sniffed != "application/octet-stream") return sniffed
        val inferred = guess(name)
        if (stated in setOf("application/octet-stream", "text/plain", "application/x-archive", "application/x-compressed")) {
            if (inferred != "application/octet-stream") return inferred
        }
        return stated
    }
    fun fallbackIcon(mimeType: String): String = when (val mime = normalize(mimeType)) {
        "application/vnd.infinifolder.partial-download", "application/vnd.infinifolder.torrent-partial" -> "⬇"
        "vnd.android.document/directory" -> "📁"
        "application/vnd.android.package-archive" -> "🤖"
        "application/x-love-game" -> "🎮"
        "application/java-archive" -> "☕"
        "application/epub+zip" -> "📖"
        "application/pdf" -> "▤"
        "text/csv", "text/tab-separated-values" -> "▦"
        else -> when {
            mime.startsWith("image/") -> "🖼"
            mime.startsWith("audio/") -> "♫"
            mime.startsWith("video/") -> "▶"
            mime.startsWith("text/") -> "▤"
            packageFormat("", mime) != null || mime in setOf("application/vnd.ms-cab-compressed", "application/x-cabinet", "application/zstd", "application/vnd.rar", "application/x-rar-compressed", "application/x-arj", "application/zip", "application/x-tar", "application/gzip", "application/x-xz", "application/x-bzip2", "application/x-7z-compressed", "application/x-archive", "application/x-cpio") -> "📦"
            else -> "▧"
        }
    }

    fun iconCandidates(mimeType: String): List<String> {
        val mime = normalize(mimeType)
        val candidates = mutableListOf(mime.replace('/', '-'))
        when {
            mime == "application/vnd.android.package-archive" -> candidates.addAll(listOf("android-package-archive", "package-x-apk", "package-x-generic"))
            mime == "application/java-archive" -> candidates.addAll(listOf("application-x-java-archive", "java", "package-x-generic"))
            mime == "application/x-love-game" -> candidates.addAll(listOf("love", "package-x-generic"))
            mime == "application/pdf" -> candidates.add("application-pdf")
            mime.contains("wordprocessingml") || mime == "application/vnd.oasis.opendocument.text" -> candidates.add("x-office-document")
            mime.contains("spreadsheet") -> candidates.add("x-office-spreadsheet")
            mime.contains("presentation") -> candidates.add("x-office-presentation")
            mime.contains("disk") || mime.contains("iso9660") || mime.contains("vmdk") -> candidates.addAll(listOf("media-optical", "drive-harddisk"))
            mime in setOf("application/vnd.ms-cab-compressed", "application/x-cabinet", "application/zstd", "application/vnd.rar", "application/x-rar-compressed", "application/x-arj", "application/zip", "application/x-tar", "application/gzip", "application/x-xz", "application/x-bzip2", "application/x-7z-compressed") -> candidates.add("package-x-generic")
        }
        if (packageFormat("", mime) != null) candidates.add("package-x-generic")
        val category = mime.substringBefore('/')
        if (category in setOf("text", "image", "video", "audio", "font")) candidates.add("$category-x-generic")
        candidates.addAll(listOf("application-octet-stream", "unknown"))
        return candidates.distinct()
    }
}
