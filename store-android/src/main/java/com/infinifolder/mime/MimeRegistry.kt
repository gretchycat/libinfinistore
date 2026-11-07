package com.infinifolder.mime

import java.util.Locale
import io.github.gretchycat.infinistream.FormatSupport

/** Application identity and its packaging are separate parts of one classification. */
object MimeRegistry {
    data class PackageFormat(val mime: String, val container: String)
    val packages = mapOf(
        "wsz" to PackageFormat("application/x-winamp-skin", "zip"),
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
        "docm" to PackageFormat("application/vnd.ms-word.document.macroenabled.12", "zip"),
        "dotx" to PackageFormat("application/vnd.openxmlformats-officedocument.wordprocessingml.template", "zip"),
        "dotm" to PackageFormat("application/vnd.ms-word.template.macroenabled.12", "zip"),
        "xlsm" to PackageFormat("application/vnd.ms-excel.sheet.macroenabled.12", "zip"),
        "xltx" to PackageFormat("application/vnd.openxmlformats-officedocument.spreadsheetml.template", "zip"),
        "xltm" to PackageFormat("application/vnd.ms-excel.template.macroenabled.12", "zip"),
        "pptm" to PackageFormat("application/vnd.ms-powerpoint.presentation.macroenabled.12", "zip"),
        "ppsx" to PackageFormat("application/vnd.openxmlformats-officedocument.presentationml.slideshow", "zip"),
        "potx" to PackageFormat("application/vnd.openxmlformats-officedocument.presentationml.template", "zip"),
        "ppsm" to PackageFormat("application/vnd.ms-powerpoint.slideshow.macroenabled.12", "zip"),
        "potm" to PackageFormat("application/vnd.ms-powerpoint.template.macroenabled.12", "zip"),
        "ott" to PackageFormat("application/vnd.oasis.opendocument.text-template", "zip"),
        "ots" to PackageFormat("application/vnd.oasis.opendocument.spreadsheet-template", "zip"),
        "otp" to PackageFormat("application/vnd.oasis.opendocument.presentation-template", "zip"),
        "odt" to PackageFormat("application/vnd.oasis.opendocument.text", "zip"),
        "ods" to PackageFormat("application/vnd.oasis.opendocument.spreadsheet", "zip"),
        "odp" to PackageFormat("application/vnd.oasis.opendocument.presentation", "zip")
    )
    private val aliases = mapOf("application/x-mobi8-ebook" to "application/vnd.amazon.mobi8-ebook", "application/x-fictionbook" to "application/x-fictionbook+xml", "application/x-java-archive" to "application/java-archive", "application/x-zip-compressed" to "application/zip",
        "application/iflink" to "application/vnd.infinifolder.shortcut+json", "application/x-shortcut" to "application/vnd.infinifolder.shortcut+json",
        "application/x-gzip" to "application/gzip", "text/xml" to "application/xml", "text/x-markdown" to "text/markdown",
        // Keep established app identities while accepting common provider spellings.
        "text/x-c" to "text/x-csrc", "text/x-c++" to "text/x-c++src",
        "text/javascript" to "application/javascript", "application/typescript" to "text/typescript",
        "text/x-sh" to "application/x-sh", "text/x-shellscript" to "application/x-sh",
        "application/x-shellscript" to "application/x-sh", "text/x-perl" to "application/x-perl",
        "text/x-ruby" to "application/x-ruby", "text/x-tcl" to "text/tcl",
        "text/x-msdos-batch" to "application/x-bat", "application/bat" to "application/x-bat",
        "application/x-font-ttf" to "font/ttf", "application/x-font-truetype" to "font/ttf",
        "application/x-font-otf" to "font/otf", "application/x-font-opentype" to "font/otf",
        "application/vnd.ms-opentype" to "font/otf", "application/x-font-ttc" to "font/collection",
        "video/x-flc" to "video/x-fli", "video/fli" to "video/x-fli", "video/flc" to "video/x-fli",
        "image/fli" to "video/x-fli", "image/flc" to "video/x-fli", "image/x-fli" to "video/x-fli",
        "image/x-flc" to "video/x-fli", "image/x-flic" to "video/x-fli", "video/x-flic" to "video/x-fli",
        "image/mng" to "video/x-mng", "application/x-mng" to "video/x-mng", "video/mng" to "video/x-mng",
        "image/x-mng" to "video/x-mng")
    private val extensions = mapOf(
        "ttf" to "font/ttf", "otf" to "font/otf", "ttc" to "font/collection", "otc" to "font/collection",
        "incomplete" to "application/vnd.infinifolder.torrent-partial",
        "diz" to "text/plain", "asc" to "text/plain", "nfo" to "text/plain", "ansi" to "image/x-ansi", "txt" to "text/plain", "log" to "text/plain", "cfg" to "text/plain", "conf" to "text/plain", "ini" to "text/plain", "properties" to "text/plain",
        "md" to "text/markdown", "markdown" to "text/markdown", "mdown" to "text/markdown", "mkd" to "text/markdown", "mkdn" to "text/markdown",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values", "html" to "text/html", "htm" to "text/html", "xhtml" to "application/xhtml+xml",
        "xml" to "application/xml", "json" to "application/json", "yaml" to "application/yaml", "yml" to "application/yaml",
        // Source identities must not depend on the device's MimeTypeMap. These
        // conventional types still open as text; an association does not execute code.
        "c" to "text/x-csrc", "h" to "text/x-chdr",
        "cpp" to "text/x-c++src", "cc" to "text/x-c++src", "cxx" to "text/x-c++src",
        "hpp" to "text/x-c++hdr", "hh" to "text/x-c++hdr", "hxx" to "text/x-c++hdr", "hp" to "text/x-c++hdr",
        "ipp" to "text/x-c++hdr", "tcc" to "text/x-c++src", "inl" to "text/x-c++hdr",
        "pas" to "text/x-pascal", "pp" to "text/x-pascal",
        "ada" to "text/x-adasrc", "adb" to "text/x-adasrc", "ads" to "text/x-adasrc",
        "bas" to "text/x-basic", "bi" to "text/x-basic", "vb" to "text/x-vb", "vbs" to "text/vbscript",
        "bat" to "application/x-bat", "cmd" to "application/x-bat", "ps1" to "application/x-powershell",
        "java" to "text/x-java", "kt" to "text/x-kotlin", "kts" to "text/x-kotlin",
        "cs" to "text/x-csharp", "scala" to "text/x-scala", "sc" to "text/x-scala",
        "go" to "text/x-go", "rs" to "text/rust", "swift" to "text/x-swift", "dart" to "application/vnd.dart",
        "py" to "text/x-python", "pyw" to "text/x-python", "pyi" to "text/x-python", "py3" to "text/x-python3", "py2" to "text/x-python2",
        "rb" to "application/x-ruby", "pl" to "application/x-perl", "pm" to "application/x-perl", "perl" to "application/x-perl",
        "php" to "application/x-php", "php3" to "application/x-php", "php4" to "application/x-php", "php5" to "application/x-php", "phps" to "application/x-php",
        "js" to "application/javascript", "mjs" to "application/javascript", "cjs" to "application/javascript", "jsx" to "application/javascript",
        "ts" to "text/typescript", "tsx" to "text/typescript", "mts" to "text/typescript", "cts" to "text/typescript",
        "sh" to "application/x-sh", "bash" to "application/x-sh", "zsh" to "application/x-sh", "ksh" to "application/x-sh",
        "csh" to "application/x-csh", "tcsh" to "application/x-csh", "fish" to "application/x-fishscript",
        "lua" to "text/x-lua", "tcl" to "text/tcl", "tk" to "text/tcl", "r" to "text/x-r-source",
        "f" to "text/x-fortran", "for" to "text/x-fortran", "f90" to "text/x-fortran", "f95" to "text/x-fortran", "f03" to "text/x-fortran", "f08" to "text/x-fortran",
        "cob" to "text/x-cobol", "cbl" to "text/x-cobol", "asm" to "text/x-asm", "s" to "text/x-asm",
        "lisp" to "text/x-common-lisp", "lsp" to "text/x-common-lisp", "el" to "text/x-emacs-lisp",
        "scm" to "text/x-scheme", "ss" to "text/x-scheme", "clj" to "text/x-clojure", "cljs" to "text/x-clojure", "cljc" to "text/x-clojure",
        "hs" to "text/x-haskell", "lhs" to "text/x-literate-haskell", "erl" to "text/x-erlang", "hrl" to "text/x-erlang",
        "ex" to "text/x-elixir", "exs" to "text/x-elixir", "fs" to "text/x-fsharp", "fsx" to "text/x-fsharp",
        "jl" to "text/julia", "nim" to "text/x-nim", "nims" to "text/x-nimscript",
        "css" to "text/css", "scss" to "text/x-scss", "sass" to "text/x-sass", "less" to "text/x-less",
        "sql" to "application/sql", "toml" to "application/toml", "jsonc" to "application/json",
        "xsd" to "application/xml", "xsl" to "application/xml", "xslt" to "application/xml", "plist" to "application/xml",
        "mindnodetouch" to "application/x-mindnode-touch",
        "diff" to "text/x-patch", "patch" to "text/x-patch", "mk" to "text/x-makefile", "mak" to "text/x-makefile",
        "cmake" to "text/x-cmake", "gradle" to "text/x-groovy", "groovy" to "text/x-groovy",
        "mobi" to "application/x-mobipocket-ebook", "azw" to "application/vnd.amazon.ebook", "azw3" to "application/vnd.amazon.mobi8-ebook", "prc" to "application/x-mobipocket-ebook",
        "fb2" to "application/x-fictionbook+xml", "rtf" to "application/rtf",
        "xls" to "application/vnd.ms-excel", "xlt" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint", "pot" to "application/vnd.ms-powerpoint", "pps" to "application/vnd.ms-powerpoint", "dot" to "application/msword",
        "fodt" to "application/vnd.oasis.opendocument.text-flat-xml", "fods" to "application/vnd.oasis.opendocument.spreadsheet-flat-xml", "fodp" to "application/vnd.oasis.opendocument.presentation-flat-xml",
        "doc" to "application/msword", "pdf" to "application/pdf", "png" to "image/png", "apng" to "image/apng", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "rip" to "image/x-rip", "svg" to "image/svg+xml", "svgz" to "image/svg+xml", "bmp" to "image/bmp", "dib" to "image/bmp", "wbmp" to "image/vnd.wap.wbmp", "ico" to "image/x-icon", "cur" to "image/x-win-bitmap",
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
        "fli" to "video/x-fli", "flc" to "video/x-fli", "flic" to "video/x-fli", "mng" to "video/x-mng",
        "neo" to "image/x-neochrome", "pi1" to "image/x-degas", "pi2" to "image/x-degas", "pi3" to "image/x-degas", "pc1" to "image/x-degas", "pc2" to "image/x-degas", "pc3" to "image/x-degas",
        "scr" to "image/x-zx-spectrum-scr", "kla" to "image/x-koala", "koa" to "image/x-koala",
        "icns" to "image/icns", "pntg" to "image/x-macpaint", "mac" to "image/x-macpaint", "pct" to "image/x-pict", "pict" to "image/x-pict",
        "ras" to "image/x-cmu-raster", "sun" to "image/x-cmu-raster", "sgi" to "image/x-sgi", "rgb" to "image/x-rgb", "bw" to "image/x-sgi", "rgba" to "image/x-sgi",
        "pdb" to "image/x-palm-pdb", "otb" to "image/x-ota-bitmap", "sixel" to "image/x-sixel", "six" to "image/x-sixel",
        "hdr" to "image/vnd.radiance", "exr" to "image/x-exr", "dds" to "image/vnd.ms-dds", "ktx" to "image/ktx",
        "fits" to "image/fits", "fit" to "image/fits", "fts" to "image/fits", "dcm" to "image/dicom", "dicom" to "image/dicom",
        "wmf" to "image/wmf", "wmz" to "image/wmf", "ps" to "image/x-eps", "eps" to "image/x-eps", "ept" to "image/x-eps", "ans" to "image/x-ansi",
        "mp3" to "audio/mpeg", "mp2" to "audio/mpeg", "mp1" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "oga" to "audio/ogg", "flac" to "audio/flac", "opus" to "audio/opus", "m4a" to "audio/mp4",
        "aac" to "audio/aac", "ape" to "audio/x-ape", "ac3" to "audio/ac3", "a52" to "audio/ac3", "eac3" to "audio/eac3", "dts" to "audio/vnd.dts",
        "wma" to "audio/x-ms-wma", "alac" to "audio/alac", "m4b" to "audio/mp4", "m4r" to "audio/mp4", "3ga" to "audio/3gpp",
        "mka" to "audio/x-matroska", "weba" to "audio/webm",
        "au" to "audio/basic", "snd" to "audio/basic",
        "aiff" to "audio/x-aiff", "aif" to "audio/x-aiff", "aifc" to "audio/x-aiff",
        "voc" to "audio/x-voc", "8svx" to "audio/x-8svx", "svx" to "audio/x-8svx", "16sv" to "audio/x-16sv", "maud" to "audio/x-maud",
        "w64" to "audio/x-wave64", "rf64" to "audio/x-rf64", "caf" to "audio/x-caf",
        "avr" to "audio/x-avr", "ircam" to "audio/x-ircam", "sph" to "audio/x-nist-sphere", "pvf" to "audio/x-pvf", "wve" to "audio/x-wve", "htk" to "audio/x-htk",
        "adx" to "audio/x-adx", "amr" to "audio/amr", "awb" to "audio/amr-wb", "gsm" to "audio/x-gsm", "g722" to "audio/x-g722",
        "mmf" to "application/vnd.smaf", "smaf" to "application/vnd.smaf", "gp3" to "application/x-guitar-pro",
        "mid" to "audio/midi", "midi" to "audio/midi", "kar" to "audio/midi", "rmi" to "audio/midi",
        "sf2" to "audio/x-soundfont", "sfz" to "audio/x-sfz", "pat" to "audio/x-gus-patch",
        "mod" to "audio/x-mod", "s3m" to "audio/x-s3m", "xm" to "audio/x-xm", "it" to "audio/x-it",
        "dro" to "audio/x-dro", "imf" to "audio/x-imf", "vgm" to "audio/x-vgm", "vgz" to "audio/x-vgm", "ym" to "audio/x-ym",
        "nsf" to "audio/x-nsf", "spc" to "audio/x-spc", "sid" to "audio/x-sid",
        "dsf" to "audio/x-dsf", "dff" to "audio/x-dff", "wv" to "audio/x-wavpack", "tta" to "audio/x-tta", "ra" to "audio/x-pn-realaudio",
        "mlp" to "audio/x-mlp", "thd" to "audio/x-truehd", "cdda" to "audio/x-cdda",
        "m3u" to "audio/x-mpegurl", "m3u8" to "audio/x-mpegurl", "pls" to "audio/x-scpls", "xspf" to "application/xspf+xml", "cue" to "application/x-cue",
        "rm" to "application/vnd.rn-realmedia", "rmvb" to "application/vnd.rn-realmedia-vbr",
        "3gp" to "video/3gpp", "3gpp" to "video/3gpp", "3g2" to "video/3gpp2",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "zip" to "application/zip", "cbz" to "application/zip", "tar" to "application/x-tar", "cbt" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2", "tbz" to "application/x-bzip2", "tbz2" to "application/x-bzip2", "xz" to "application/x-xz", "txz" to "application/x-xz",
        "7z" to "application/x-7z-compressed", "cb7" to "application/x-7z-compressed", "cpio" to "application/x-cpio", "ar" to "application/x-archive", "deb" to "application/vnd.debian.binary-package",
        // GEN commonly holds a Sega Genesis ROM. A specific non-audio identity
        // prevents raw ROM vectors from being mistaken for an MPEG frame sync.
        "gen" to "application/x-genesis-rom",
        // BIN is generic binary data; actual disc/filesystem signatures may refine it.
        "bin" to "application/octet-stream",
        "iso" to "application/x-iso9660-image", "cdr" to "application/x-iso9660-image", "img" to "application/x-raw-disk-image", "raw" to "application/x-raw-disk-image",
        "vdi" to "application/x-virtualbox-vdi", "qcow2" to "application/x-qemu-disk", "vmdk" to "application/x-vmdk", "vhd" to "application/x-vhd", "vhdx" to "application/x-vhdx",
        "ufs" to "application/x-raw-disk-image", "ufs1" to "application/x-raw-disk-image", "ufs2" to "application/x-raw-disk-image", "ffs" to "application/x-raw-disk-image",
        "zst" to "application/zstd", "tzst" to "application/zstd",
        "cab" to "application/vnd.ms-cab-compressed",
        "rar" to "application/vnd.rar", "cbr" to "application/vnd.rar", "arj" to "application/x-arj",
        "ntfs" to "application/x-raw-disk-image",
        "hfs" to "application/x-raw-disk-image", "hfsplus" to "application/x-raw-disk-image", "hfsx" to "application/x-raw-disk-image",
        "taz" to "application/x-compress", "z" to "application/x-compress",
        "lnk" to "application/x-ms-shortcut", "iflink" to "application/vnd.infinifolder.shortcut+json",
        "torrent" to "application/x-bittorrent"
    )

    /**
     * Explicit filename identities owned by Store, exposed through its runtime
     * capability provider so host Android filters do not copy this map.
     */
    fun declaredFormats(): List<FormatSupport> {
        val byMime = linkedMapOf<String, MutableSet<String>>()
        fun add(extension: String, mime: String) {
            byMime.getOrPut(normalize(mime)) { linkedSetOf() }.add(extension.lowercase(Locale.ROOT))
        }
        extensions.forEach { (extension, mime) -> add(extension, mime) }
        packages.forEach { (extension, format) -> add(extension, format.mime) }
        // Numbered split volumes are handled by the same RAR/ARJ routes as
        // their base archive extension.
        (0..99).forEach { volume ->
            add("r${volume.toString().padStart(2, '0')}", "application/vnd.rar")
            add("a${volume.toString().padStart(2, '0')}", "application/x-arj")
        }
        return byMime.entries.sortedBy { it.key }.map { (mime, fileExtensions) ->
            FormatSupport(mimeTypes = setOf(mime), extensions = fileExtensions.toSet())
        }
    }

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
        val registered=runtimeAssociations().entries.filter { name.lowercase(Locale.ROOT).endsWith(".${it.key}") }
            .sortedByDescending { it.key.length }.firstOrNull()?.value?.singleOrNull()
        return packages[extension]?.mime ?: extensions[extension] ?: registered ?: runCatching {
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
    /** Text/source MIME aliases outside the top-level text category. */
    private fun isTextMime(mime: String): Boolean = mime.startsWith("text/") || mime in setOf(
        "application/json", "application/ld+json", "application/xml", "application/xhtml+xml",
        "application/yaml", "application/x-yaml", "application/toml", "application/javascript",
        "application/ecmascript", "application/x-javascript", "application/x-sh", "application/x-shellscript",
        "application/x-python", "application/x-perl", "application/x-ruby", "application/sql",
        "application/x-csh", "application/x-bat", "application/x-powershell", "application/x-fishscript",
        "application/x-php", "application/vnd.dart"
    ) || mime.endsWith("+json") || mime.endsWith("+xml")

    /** Immutable seed associations for editable host registries; aliases remain canonicalized here. */
    fun associations(): Map<String, String> = runtimeAssociations().mapNotNull { (ext,values) -> values.singleOrNull()?.let { ext to it } }.toMap() + extensions.mapValues { normalize(it.value) } +
        packages.mapValues { normalize(it.value.mime) }

    private data class RuntimeAssociations(val revision:Long,val entries:Map<String,Set<String>>)
    @Volatile private var runtimeAssociationCache:RuntimeAssociations?=null
    /** Recognition facts only; execution availability belongs to the capability registry. */
    fun runtimeAssociations():Map<String,Set<String>> {
        val registry=io.github.gretchycat.infinistream.RuntimeCapabilities.installed()
        runtimeAssociationCache?.takeIf { it.revision==registry.revision }?.let {return it.entries}
        val entries=linkedMapOf<String,MutableSet<String>>()
        registry.descriptors().flatMap {it.formats}.forEach { format ->
            val types=format.mimeTypes.filter { !it.contains('*') }.map(::normalize)
            format.extensions.forEach { ext ->entries.getOrPut(ext){linkedSetOf()}.addAll(types) }
        }
        val result=entries.mapValues { it.value.toSet() }.filterValues {it.isNotEmpty()}
        runtimeAssociationCache=RuntimeAssociations(registry.revision,result)
        return result
    }

    fun fallbackIcon(mimeType: String): String = when (val mime = normalize(mimeType)) {
        "application/vnd.infinifolder.partial-download", "application/vnd.infinifolder.torrent-partial" -> "⬇"
        "vnd.android.document/directory" -> "📁"
        "application/vnd.android.package-archive" -> "🤖"
        "application/x-love-game" -> "🎮"
        "application/java-archive" -> "☕"
        "application/epub+zip" -> "📖"
        "application/vnd.smaf", "application/x-smaf", "application/x-guitar-pro", "application/x-gtp" -> "♫"
        "application/pdf" -> "📕"
        "text/csv", "text/tab-separated-values" -> "📊"
        else -> when {
            mime.startsWith("font/") || mime == "application/font-sfnt" -> "Aa"
            mime.startsWith("image/") -> "🖼"
            mime.startsWith("audio/") -> "♫"
            mime.startsWith("video/") -> "▶"
            isTextMime(mime) -> "📄"
            mime == "application/msword" || mime.contains("wordprocessingml") || mime == "application/vnd.oasis.opendocument.text" -> "📝"
            mime.contains("spreadsheet") || mime == "application/vnd.ms-excel" -> "📊"
            mime.contains("presentation") || mime == "application/vnd.ms-powerpoint" -> "📽"
            packageFormat("", mime) != null || mime in setOf("application/vnd.ms-cab-compressed", "application/x-cabinet", "application/zstd", "application/vnd.rar", "application/x-rar-compressed", "application/x-arj", "application/zip", "application/x-tar", "application/gzip", "application/x-xz", "application/x-bzip2", "application/x-7z-compressed", "application/x-archive", "application/x-cpio") -> "📦"
            else -> "📄"
        }
    }

    fun iconCandidates(mimeType: String): List<String> {
        val mime = normalize(mimeType)
        val candidates = mutableListOf(mime.replace('/', '-'))
        when {
            mime == "application/vnd.android.package-archive" -> candidates.addAll(listOf("android-package-archive", "package-x-apk", "package-x-generic"))
            mime == "application/java-archive" -> candidates.addAll(listOf("application-x-java-archive", "java", "package-x-generic"))
            mime == "application/x-love-game" -> candidates.addAll(listOf("love", "package-x-generic"))
            mime in setOf("application/vnd.smaf", "application/x-smaf", "application/x-guitar-pro", "application/x-gtp") -> candidates.add("audio-x-generic")
            mime == "application/pdf" -> candidates.addAll(listOf("application-pdf", "x-office-document"))
            mime == "application/msword" || mime.contains("wordprocessingml") || mime == "application/vnd.oasis.opendocument.text" -> candidates.add("x-office-document")
            mime.contains("spreadsheet") -> candidates.add("x-office-spreadsheet")
            mime.contains("presentation") -> candidates.add("x-office-presentation")
            mime.contains("disk") || mime.contains("iso9660") || mime.contains("vmdk") -> candidates.addAll(listOf("media-optical", "drive-harddisk"))
            mime in setOf("application/vnd.ms-cab-compressed", "application/x-cabinet", "application/zstd", "application/vnd.rar", "application/x-rar-compressed", "application/x-arj", "application/zip", "application/x-tar", "application/gzip", "application/x-xz", "application/x-bzip2", "application/x-7z-compressed") -> candidates.add("package-x-generic")
        }
        if (packageFormat("", mime) != null) candidates.add("package-x-generic")
        if (mime == "application/font-sfnt") candidates.add("font-x-generic")
        if (isTextMime(mime)) candidates.add("text-x-generic")
        val category = mime.substringBefore('/')
        if (category in setOf("text", "image", "video", "audio", "font")) candidates.add("$category-x-generic")
        candidates.addAll(listOf("application-octet-stream", "unknown"))
        return candidates.distinct()
    }
}
