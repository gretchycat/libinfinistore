package com.infinifolder.storage

import android.util.LruCache
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

data class FileMagicResult(
    val mimeType: String,
    val description: String,
    val isArchiveOrDiscImage: Boolean = false
)

object FileMagicDetector {

    private val AMBIGUOUS_EXTENSIONS = setOf(
        "", "bin", "dat", "raw", "img", "ima", "flp", "rom", "pak",
        "out", "tmp", "bak", "old", "part", "0", "1", "dmp", "dump",
        "gz", "bz2", "bz", "xz", "lzma", "zst"
    )

    private val fileCache = LruCache<String, FileMagicResult>(512)

    fun isAmbiguousOrUnknown(fileName: String, currentMimeType: String): Boolean {
        if (com.infinifolder.archive.ArchiveFormats.isPackagedFormat(fileName, currentMimeType)) {
            return false
        }
        val archiveExt = com.infinifolder.archive.ArchiveFormats.archiveExtension(fileName)
        if (archiveExt.contains('.')) return false
        val cleanMime = currentMimeType.lowercase(Locale.ROOT).substringBefore(';').trim()
        if (com.infinifolder.mime.MimeRegistry.isUnspecified(cleanMime)) {
            return true
        }
        val dotIdx = fileName.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == fileName.length - 1) return true
        val ext = fileName.substring(dotIdx + 1).lowercase(Locale.ROOT)
        return ext in AMBIGUOUS_EXTENSIONS
    }

    /** Resolve an extension-derived candidate only after content probing. Unknown binary bytes stay generic. */
    fun validatedExtensionMime(fileName: String, candidateMime: String, detected: FileMagicResult?): String {
        val candidate = com.infinifolder.mime.MimeRegistry.normalize(candidateMime)
        val actual = detected?.mimeType?.let(com.infinifolder.mime.MimeRegistry::normalize)
        if (actual == null) return "application/octet-stream"
        if (actual == candidate) return candidate
        if (actual == "application/zip") {
            if (candidate in PARSER_VALIDATED_ZIP_DOCUMENT_MIMES) return actual
            if (com.infinifolder.archive.ArchiveFormats.packageMimeType(fileName)?.let(com.infinifolder.mime.MimeRegistry::normalize) == candidate)
                return candidate
        }
        if (actual == "application/x-ole-storage" && candidate in LEGACY_OFFICE_MIMES) return candidate
        if ((actual == "image/tiff" && candidate == "image/x-adobe-dng") ||
            (actual == "image/jp2" && candidate == "image/jpx") ||
            (actual == "image/heic" && candidate == "image/heif") ||
            (actual == "image/heif" && candidate == "image/heic") ||
            (actual == "image/x-sgi" && candidate == "image/x-rgb")) return candidate

        // Plain-text detection is the final fallback for source/document names
        // whose bytes carry no stronger identity. Structured text and text-art
        // detections remain authoritative over a generic .txt association.
        if (actual == "text/plain" && com.infinifolder.mime.MimeRegistry.isTextBased(candidate)) return candidate
        if (actual == "application/json" && candidate.endsWith("+json")) return candidate
        if (actual == "application/xml" && candidate.endsWith("+xml")) return candidate
        return actual
    }

    private val LEGACY_OFFICE_MIMES = setOf("application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint")
    private val PARSER_VALIDATED_ZIP_DOCUMENT_MIMES = setOf(
        "application/epub+zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.ms-word.document.macroenabled.12", "application/vnd.ms-word.template.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "application/vnd.ms-excel.sheet.macroenabled.12", "application/vnd.ms-excel.template.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
        "application/vnd.ms-powerpoint.presentation.macroenabled.12",
        "application/vnd.ms-powerpoint.slideshow.macroenabled.12", "application/vnd.ms-powerpoint.template.macroenabled.12",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template",
        "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template",
        "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.presentation-template"
    )

    fun detect(file: File, peekCompressedPayload: Boolean = false): FileMagicResult? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val len = file.length()
        if (len <= 0L) return null

        val cacheKey = "${file.absolutePath}:$len:${file.lastModified()}:$peekCompressedPayload:${io.github.gretchycat.infinistream.RuntimeCapabilities.installed().revision}"
        synchronized(fileCache) {
            fileCache.get(cacheKey)?.let { return it }
        }

        val detected = try {
            RandomAccessFile(file, "r").use { raf ->
                // Read only the prefix required by the candidate's validators. A few
                // disk/ROM formats need a later header; ordinary extensions need 4 KiB.
                val headLen = len.coerceAtMost(probePrefixLength(file.name,
                    com.infinifolder.mime.MimeRegistry.guess(file.name)).toLong()).toInt()
                val header = ByteArray(headLen)
                raf.readFully(header)

                val extMagic = if (len >= 1082L) {
                    val b = ByteArray(2)
                    raf.seek(1080L)
                    raf.readFully(b)
                    b
                } else null

                val isoMagic = if (len >= 32774L) {
                    val b = ByteArray(5)
                    raf.seek(32769L)
                    raf.readFully(b)
                    b
                } else null

                val vhdFooter = if (len >= 1024L) {
                    val b = ByteArray(512)
                    raf.seek(len - 512L)
                    raf.readFully(b)
                    b
                } else null
                val ufsMagic = listOf(65536L, 8192L, 0L, 262144L).firstNotNullOfOrNull { offset ->
                    if (len < offset + 1376) null else {
                        val bytes = ByteArray(4)
                        raf.seek(offset + 1372); raf.readFully(bytes)
                        ufsVersion(bytes, 0)
                    }
                }

                // Check if outer wrapper is compressed (.gz, .bz2, .xz, .zst)
                val compressor = detectOuterCompressor(header)
                if (compressor != null) {
                    val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val innerResult = if (peekCompressedPayload || extension == "vgz") {
                        try {
                            file.inputStream().use { rawIn ->
                                openCompressedStream(rawIn, compressor).use { decIn ->
                                    val innerBuf = readUpTo(decIn, 69632)
                                    detectFromBytes(innerBuf, null, null, null)
                                }
                            }
                        } catch (_: Exception) {
                            null
                        }
                    } else null

                    val audioWrapper = extension == "vgz" && innerResult?.mimeType == "audio/x-vgm"
                    if (innerResult != null && (innerResult.isArchiveOrDiscImage || audioWrapper)) {
                        FileMagicResult(
                            mimeType = innerResult.mimeType,
                            description = "${compressor.label}-compressed ${innerResult.description}",
                            isArchiveOrDiscImage = innerResult.isArchiveOrDiscImage
                        )
                    } else {
                        FileMagicResult(
                            mimeType = compressor.mimeType,
                            description = if (innerResult != null) {
                                "${compressor.label}-compressed ${innerResult.description}"
                            } else {
                                "${compressor.label} Compressed Data"
                            },
                            isArchiveOrDiscImage = false
                        )
                    }
                } else {
                    val expectedMime = com.infinifolder.mime.MimeRegistry.guess(file.name)
                    val positionedIdentity = if (requiresPositionalProbe(file.name, expectedMime)) runCatching {
                        detectFromSource(file.name, expectedMime, io.github.gretchycat.infinistream.FileSourceResource(
                            file, io.github.gretchycat.infinistream.ContentType(mimeType = expectedMime)))
                    }.getOrNull() else null
                    positionedIdentity ?: detectMpegAfterLargeId3(raf, header, len) ?: detectFromBytes(header, extMagic, isoMagic, vhdFooter, ufsMagic,
                        fileNameHint = file.name, expectedMimeType = expectedMime, resourceLength = len)
                }
            }
        } catch (_: Exception) {
            null
        }

        if (detected != null) {
            synchronized(fileCache) {
                fileCache.put(cacheKey, detected)
            }
            return detected
        }
        return detected
    }

    fun detectFromStream(openStream: () -> InputStream): FileMagicResult? = detectFromStream(null, null, null, openStream)

    /** Bounded detection with a filename/MIME hint for format-specific validators. */
    fun detectFromStream(fileNameHint: String?, expectedMimeType: String?, resourceLength: Long? = null,
                         openStream: () -> InputStream): FileMagicResult? {
        return try {
            openStream().use { source ->
                val prefixLength = fileNameHint?.let { probePrefixLength(it, expectedMimeType) } ?: 69632
                val prefix = readUpTo(source, prefixLength)
                if (prefix.isEmpty()) return null
                val compressor = detectOuterCompressor(prefix)
                if (compressor != null) {
                    val innerResult = try {
                        openCompressedStream(prefix.inputStream(), compressor).use { decIn ->
                            val innerBuf = readUpTo(decIn, 69632)
                            detectFromBytes(innerBuf, null, null)
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    val audioWrapper = fileNameHint?.substringAfterLast('.', "")?.equals("vgz", true) == true &&
                        innerResult?.mimeType == "audio/x-vgm"
                    if (innerResult != null && (innerResult.isArchiveOrDiscImage || audioWrapper)) {
                        return FileMagicResult(
                            mimeType = innerResult.mimeType,
                            description = "${compressor.label}-compressed ${innerResult.description}",
                            isArchiveOrDiscImage = innerResult.isArchiveOrDiscImage
                        )
                    }
                    return FileMagicResult(
                        mimeType = compressor.mimeType,
                        description = if (innerResult != null) {
                            "${compressor.label}-compressed ${innerResult.description}"
                        } else {
                            "${compressor.label} Compressed Data"
                        },
                        isArchiveOrDiscImage = false
                    )
                }
                detectMpegAfterLargeId3(source, prefix, resourceLength) ?: detectFromBytes(prefix, null, null,
                    fileNameHint = fileNameHint, expectedMimeType = expectedMimeType, resourceLength = resourceLength)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Bounded detection that lets declared validators use a seekable source when their format needs it. */
    fun detectFromSource(fileNameHint: String, expectedMimeType: String?, source: io.github.gretchycat.infinistream.SourceResource): FileMagicResult? {
        return try {
            val length = source.metadata.length
            source.open().use { input ->
                val prefix = readUpTo(input, probePrefixLength(fileNameHint, expectedMimeType))
                if (prefix.isEmpty()) return null
                detectFromBytesInternal(prefix, null, null, null, null, fileNameHint,
                    expectedMimeType, length, source)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Whether a matching identify handler explicitly requires positional reads. */
    fun requiresPositionalProbe(fileNameHint: String, expectedMimeType: String?): Boolean {
        val normalized = expectedMimeType?.let(com.infinifolder.mime.MimeRegistry::normalize)
            ?: com.infinifolder.mime.MimeRegistry.guess(fileNameHint)
        val extension = fileNameHint.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return runCatching {
            io.github.gretchycat.infinistream.RuntimeCapabilities.installed().descriptors().any { descriptor ->
                io.github.gretchycat.infinistream.Operations.IDENTIFY in descriptor.operations &&
                    descriptor.attributes["probeRequiresPositionalRead"] == "true" &&
                    descriptor.formats.any { format ->
                        normalized in format.mimeTypes.map(com.infinifolder.mime.MimeRegistry::normalize) ||
                            extension.isNotEmpty() && format.extensions.any { it.equals(extension, true) }
                    }
            }
        }.getOrDefault(false)
    }

    fun detectFromBytes(
        header: ByteArray,
        extMagicAt1080: ByteArray? = null,
        isoMagicAt32769: ByteArray? = null,
        vhdFooterAtEof: ByteArray? = null,
        ufsMagicAtSuperblock: Int? = null,
        fileNameHint: String? = null,
        expectedMimeType: String? = null,
        resourceLength: Long? = null
    ): FileMagicResult? = detectFromBytesInternal(header, extMagicAt1080, isoMagicAt32769,
        vhdFooterAtEof, ufsMagicAtSuperblock, fileNameHint, expectedMimeType, resourceLength, null)

    private fun detectFromBytesInternal(
        header: ByteArray,
        extMagicAt1080: ByteArray?,
        isoMagicAt32769: ByteArray?,
        vhdFooterAtEof: ByteArray?,
        ufsMagicAtSuperblock: Int?,
        fileNameHint: String?,
        expectedMimeType: String?,
        resourceLength: Long?,
        probeSource: io.github.gretchycat.infinistream.SourceResource?
    ): FileMagicResult? {
        if (header.isEmpty()) return null
        registeredIdentity(header, fileNameHint, expectedMimeType, resourceLength, probeSource)?.let{return it}
        if(header.size>=128 && u8(header,5)==0xaf && u8(header,4) in setOf(0x11,0x12)){
            fun word(at:Int)=u8(header,at) or (u8(header,at+1) shl 8)
            val sourceLength=resourceLength?:header.size.toLong()
            val declared=u32le(header,0)
            val validFrame=header.size>=144 && u32le(header,128) in 16L..(sourceLength-128) &&
                word(132)==0xf1fa && word(134) in 0..10000
            val unclosed=header.take(4).all{it==0.toByte()} && word(6)==0 && validFrame
            val regular=declared in 128L..sourceLength && word(6) in 1..10000 && word(8) in 1..65535 &&
                word(10) in 1..65535 && word(8).toLong()*word(10)<=4L*1024*1024
            if((regular || unclosed) && word(12) in setOf(0,8))
                return FileMagicResult("video/x-fli","Autodesk Animator FLI/FLC Animation")
        }
        if(header.size>=44 && header.take(8).map{it.toInt() and 255}==listOf(138,77,78,71,13,10,26,10) &&
            u32be(header,8)==28L && String(header,12,4,StandardCharsets.US_ASCII)=="MHDR" &&
            u32be(header,16) in 1L..40000L && u32be(header,20) in 1L..40000L)
            return FileMagicResult("video/x-mng","MNG Animation")
        if (header.size >= 12) {
            val signature = String(header, 0, 4, StandardCharsets.ISO_8859_1)
            val tables = (u8(header, 4) shl 8) or u8(header, 5)
            if (tables in 1..4096) {
                if (signature == "OTTO") return FileMagicResult("font/otf", "OpenType Font")
                if (signature == "\u0000\u0001\u0000\u0000" || signature == "true")
                    return FileMagicResult("font/ttf", "TrueType Font")
            }
            if (signature == "ttcf") {
                val version = (4..7).fold(0L) { value, i -> (value shl 8) or u8(header, i).toLong() }
                val faces = (8..11).fold(0L) { value, i -> (value shl 8) or u8(header, i).toLong() }
                if (version in setOf(0x00010000L, 0x00020000L) && faces in 1..256)
                    return FileMagicResult("font/collection", "OpenType / TrueType Font Collection")
            }
        }
        if (header.size >= 16 && String(header, 0, 4, StandardCharsets.US_ASCII) == "MMMD") {
            val size = (4..7).fold(0L) { n, i -> (n shl 8) or u8(header,i).toLong() }
            if (size >= 8 && (resourceLength == null || size + 8 <= resourceLength))
                return FileMagicResult("application/vnd.smaf", "Yamaha SMAF Ringtone")
        }
        val guitarPro3Version = "FICHIER GUITAR PRO v3.00"
        if (header.size >= 31 && u8(header,0) == guitarPro3Version.length &&
            String(header,1,guitarPro3Version.length,StandardCharsets.US_ASCII) == guitarPro3Version)
            return FileMagicResult("application/x-guitar-pro", "Guitar Pro 3 Score")
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "MSCF")
            return FileMagicResult("application/vnd.ms-cab-compressed", "Microsoft Cabinet Archive", true)
        if (header.size >= 4 && header[0] == 0x60.toByte() && header[1] == 0xea.toByte())
            return FileMagicResult("application/x-arj", "ARJ Archive", true)
        if (header.size >= 1028) {
            val signature = ((header[1024].toInt() and 255) shl 8) or (header[1025].toInt() and 255)
            val version = ((header[1026].toInt() and 255) shl 8) or (header[1027].toInt() and 255)
            val label = when {
                signature == 0x4244 -> "HFS"
                signature == 0x482b && version == 4 -> "HFS+"
                signature == 0x4858 && version == 5 -> "HFSX"
                else -> null
            }
            if (label != null) return FileMagicResult("application/x-raw-disk-image", "$label Filesystem Image", true)
        }
        if (header.size >= 512 && String(header, 3, 8, StandardCharsets.US_ASCII) == "NTFS    " && u16le(header, 510) == 0xaa55)
            return FileMagicResult("application/x-raw-disk-image", "NTFS Filesystem Image", true)

        val ufs = ufsMagicAtSuperblock ?: listOf(65536, 8192, 0, 262144).firstNotNullOfOrNull {
            if (header.size >= it + 1376) ufsVersion(header, it + 1372) else null
        }
        if (ufs != null) return FileMagicResult("application/x-raw-disk-image", "BSD UFS$ufs Filesystem Image", true)

        // 1. Virtual Machine Disk Images
        // QCOW2: 51 46 49 FB ("QFI\xfb")
        if (header.size >= 4 && u8(header, 0) == 0x51 && u8(header, 1) == 0x46 &&
            u8(header, 2) == 0x49 && u8(header, 3) == 0xFB
        ) {
            return FileMagicResult("application/x-qemu-disk", "QEMU QCOW2 Disk Image", isArchiveOrDiscImage = true)
        }

        // VDI: signature 0xBEDA107F at offset 0x40 = 64
        if (header.size >= 68 && u32le(header, 64) == 0xBEDA107FL) {
            return FileMagicResult("application/x-virtualbox-vdi", "VirtualBox VDI Disk Image", isArchiveOrDiscImage = true)
        }

        // VMDK (Sparse "KDMV" or text "# Disk DescriptorFile")
        if (header.size >= 4 && header[0] == 'K'.code.toByte() && header[1] == 'D'.code.toByte() &&
            header[2] == 'M'.code.toByte() && header[3] == 'V'.code.toByte()
        ) {
            return FileMagicResult("application/x-vmdk", "VMware VMDK Disk Image", isArchiveOrDiscImage = true)
        }
        if (header.size >= 22 && String(header, 0, 22.coerceAtMost(header.size), StandardCharsets.US_ASCII).startsWith("# Disk DescriptorFile")) {
            return FileMagicResult("application/x-vmdk", "VMware VMDK Disk Image", isArchiveOrDiscImage = true)
        }

        // VHDX ("vhdxfile")
        if (header.size >= 8 && String(header, 0, 8, StandardCharsets.US_ASCII) == "vhdxfile") {
            return FileMagicResult("application/x-vhdx", "Hyper-V VHDX Disk Image", isArchiveOrDiscImage = true)
        }

        // VHD ("conectix" at offset 0 or footer, or dynamic header "cxsparse")
        if (header.size >= 8 && (String(header, 0, 8, StandardCharsets.US_ASCII) == "conectix" || String(header, 0, 8, StandardCharsets.US_ASCII) == "cxsparse")) {
            return FileMagicResult("application/x-vhd", "Virtual Hard Disk (VHD)", isArchiveOrDiscImage = true)
        }
        if (header.size >= 520 && String(header, 512, 8, StandardCharsets.US_ASCII) == "cxsparse") {
            return FileMagicResult("application/x-vhd", "Virtual Hard Disk (VHD)", isArchiveOrDiscImage = true)
        }
        if (vhdFooterAtEof != null && vhdFooterAtEof.size >= 8 &&
            String(vhdFooterAtEof, 0, 8, StandardCharsets.US_ASCII) == "conectix"
        ) {
            return FileMagicResult("application/x-vhd", "Virtual Hard Disk (VHD)", isArchiveOrDiscImage = true)
        }

        // 2. Check ISO 9660 ("CD001" at 0x8001 = 32769)
        val hasIsoMagic = when {
            isoMagicAt32769 != null && isoMagicAt32769.size >= 5 ->
                String(isoMagicAt32769, 0, 5, StandardCharsets.US_ASCII) == "CD001"
            header.size >= 32774 ->
                String(header, 32769, 5, StandardCharsets.US_ASCII) == "CD001"
            else -> false
        }
        if (hasIsoMagic) {
            return FileMagicResult("application/x-iso9660-image", "ISO 9660 Disc Image", isArchiveOrDiscImage = true)
        }

        // 2. Check ext2 / ext3 / ext4 superblock magic (0xEF53 at offset 1080)
        val hasExtMagic = when {
            extMagicAt1080 != null && extMagicAt1080.size >= 2 ->
                u16le(extMagicAt1080, 0) == 0xEF53
            header.size >= 1082 ->
                u16le(header, 1080) == 0xEF53
            else -> false
        }
        if (hasExtMagic) {
            return FileMagicResult("application/x-raw-disk-image", "ext2/ext3/ext4 Filesystem Image", isArchiveOrDiscImage = true)
        }

        // 3. Check exFAT ("EXFAT   " at offset 3)
        if (header.size >= 11 && String(header, 3, 8, StandardCharsets.US_ASCII) == "EXFAT   ") {
            return FileMagicResult("application/x-raw-disk-image", "exFAT Filesystem Image", isArchiveOrDiscImage = true)
        }

        // 4. Check FAT12 / FAT16 / FAT32 / VFAT BPB
        if (header.size >= 512 && isFatBpb(header)) {
            return FileMagicResult("application/x-raw-disk-image", "FAT/VFAT Filesystem Image", isArchiveOrDiscImage = true)
        }

        // 5. Check GPT Partition Table ("EFI PART" at offset 512)
        if (header.size >= 520 && String(header, 512, 8, StandardCharsets.US_ASCII) == "EFI PART") {
            return FileMagicResult("application/x-raw-disk-image", "GPT Partitioned Disk Image", isArchiveOrDiscImage = true)
        }

        // 5b. Check LVM2 Physical Volume ("LABELONE" / "LVM2 001")
        for (sec in 0..3) {
            val off = sec * 512
            if (header.size >= off + 32 &&
                String(header, off, 8, StandardCharsets.US_ASCII) == "LABELONE" &&
                String(header, off + 24, 8, StandardCharsets.US_ASCII) == "LVM2 001"
            ) {
                return FileMagicResult("application/x-raw-disk-image", "LVM2 Physical Volume", isArchiveOrDiscImage = true)
            }
        }

        // 5c. Check Apple Partition Map ("PM" at offset 512)
        if (header.size >= 514 && header[512] == 'P'.code.toByte() && header[513] == 'M'.code.toByte()) {
            return FileMagicResult("application/x-raw-disk-image", "Apple Partition Map (APM) Disk Image", isArchiveOrDiscImage = true)
        }

        // 5d. Check MBR Partition Table (0x55AA at 510..511 and valid partition entry)
        if (header.size >= 512 && u8(header, 510) == 0x55 && u8(header, 511) == 0xAA) {
            var hasValidPart = false
            for (i in 0 until 4) {
                val pos = 446 + i * 16
                val status = u8(header, pos)
                val type = u8(header, pos + 4)
                val numSectors = u32le(header, pos + 12)
                if ((status == 0x00 || status == 0x80) && type != 0x00 && numSectors > 0L) {
                    hasValidPart = true
                    break
                }
            }
            if (hasValidPart) {
                return FileMagicResult("application/x-raw-disk-image", "MBR Partitioned Disk Image", isArchiveOrDiscImage = true)
            }
        }

        // 6. Archives
        // ZIP / JAR / APK: 50 4B 03 04 or 50 4B 05 06
        if (header.size >= 4 && u8(header, 0) == 0x50 && u8(header, 1) == 0x4B &&
            ((u8(header, 2) == 0x03 && u8(header, 3) == 0x04) || (u8(header, 2) == 0x05 && u8(header, 3) == 0x06))
        ) {
            return FileMagicResult("application/zip", "ZIP Archive", isArchiveOrDiscImage = true)
        }

        // 7-Zip: 37 7A BC AF 27 1C
        if (header.size >= 6 && u8(header, 0) == 0x37 && u8(header, 1) == 0x7A &&
            u8(header, 2) == 0xBC && u8(header, 3) == 0xAF && u8(header, 4) == 0x27 && u8(header, 5) == 0x1C
        ) {
            return FileMagicResult("application/x-7z-compressed", "7-Zip Archive", isArchiveOrDiscImage = true)
        }

        // RAR: 52 61 72 21 1A 07
        if (header.size >= 6 && u8(header, 0) == 0x52 && u8(header, 1) == 0x61 &&
            u8(header, 2) == 0x72 && u8(header, 3) == 0x21 && u8(header, 4) == 0x1A && u8(header, 5) == 0x07
        ) {
            return FileMagicResult("application/vnd.rar", "RAR Archive", isArchiveOrDiscImage = true)
        }

        // TAR ("ustar" at offset 257)
        if (header.size >= 262 && String(header, 257, 5, StandardCharsets.US_ASCII) == "ustar") {
            return FileMagicResult("application/x-tar", "POSIX TAR Archive", isArchiveOrDiscImage = true)
        }

        // CPIO ("070701", "070702", "070707", or binary 0x71C7)
        if (header.size >= 6) {
            val cpioAscii = String(header, 0, 6, StandardCharsets.US_ASCII)
            if (cpioAscii == "070701" || cpioAscii == "070702" || cpioAscii == "070707" ||
                (u8(header, 0) == 0xC7 && u8(header, 1) == 0x71) ||
                (u8(header, 0) == 0x71 && u8(header, 1) == 0xC7)
            ) {
                return FileMagicResult("application/x-cpio", "CPIO Archive", isArchiveOrDiscImage = true)
            }
        }

        // AR / DEB ("!<arch>\n")
        if (header.size >= 8 && String(header, 0, 8, StandardCharsets.US_ASCII) == "!<arch>\n") {
            return FileMagicResult("application/x-archive", "Unix Archiver (ar/deb) Package", isArchiveOrDiscImage = true)
        }

        // 7. Images
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (header.size >= 8 && u8(header, 0) == 0x89 && u8(header, 1) == 0x50 &&
            u8(header, 2) == 0x4E && u8(header, 3) == 0x47 && u8(header, 4) == 0x0D &&
            u8(header, 5) == 0x0A && u8(header, 6) == 0x1A && u8(header, 7) == 0x0A
        ) {
            return FileMagicResult("image/png", "PNG Image")
        }

        // JPEG: FF D8 FF
        if (header.size >= 3 && u8(header, 0) == 0xFF && u8(header, 1) == 0xD8 && u8(header, 2) == 0xFF) {
            return FileMagicResult("image/jpeg", "JPEG Image")
        }

        // GIF: GIF87a or GIF89a
        if (header.size >= 6) {
            val gifSig = String(header, 0, 6, StandardCharsets.US_ASCII)
            if (gifSig == "GIF87a" || gifSig == "GIF89a") {
                return FileMagicResult("image/gif", "GIF Image")
            }
        }

        // RIFF containers (WebP, WAV, AVI)
        if (header.size >= 12 && String(header, 0, 4, StandardCharsets.US_ASCII) == "RIFF") {
            return when (String(header, 8, 4, StandardCharsets.US_ASCII)) {
                "WEBP" -> FileMagicResult("image/webp", "WebP Image")
                "WAVE" -> FileMagicResult("audio/wav", "WAV Audio")
                "AVI " -> FileMagicResult("video/x-msvideo", "AVI Video")
                else -> null
            }
        }

        // RF64 audio
        if (header.size >= 12 && String(header, 0, 4, StandardCharsets.US_ASCII) == "RF64") {
            return FileMagicResult("audio/x-rf64", "Broadcast RF64 Audio")
        }

        // Sony Wave64: "riff" GUID
        if (header.size >= 16 && String(header, 0, 4, StandardCharsets.US_ASCII) == "riff") {
            return FileMagicResult("audio/x-wave64", "Sony Wave64 Audio")
        }

        // IFF FORM (AIFF, 8SVX)
        if (header.size >= 12 && String(header, 0, 4, StandardCharsets.US_ASCII) == "FORM") {
            return when (String(header, 8, 4, StandardCharsets.US_ASCII)) {
                "AIFF", "AIFC" -> FileMagicResult("audio/x-aiff", "AIFF Audio")
                "8SVX" -> FileMagicResult("audio/x-8svx", "Amiga 8SVX Audio")
                "ILBM", "PBM " -> if (isValidIlbmHeader(header, resourceLength))
                    FileMagicResult("image/x-ilbm", "Amiga IFF Bitmap") else null
                else -> null
            }
        }

        // Sun / NeXT AU: ".snd"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == ".snd") {
            return FileMagicResult("audio/basic", "Sun/NeXT AU Audio")
        }

        // Creative Voice: "Creative Voice File"
        if (header.size >= 19 && String(header, 0, 19, StandardCharsets.US_ASCII) == "Creative Voice File") {
            return FileMagicResult("audio/x-voc", "Creative Voice File")
        }

        // Apple Core Audio Format: "caff"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "caff") {
            return FileMagicResult("audio/x-caf", "Core Audio Format")
        }

        // Standard MIDI: validate the fixed header, track count and division.
        if (header.size >= 14 && String(header, 0, 4, StandardCharsets.US_ASCII) == "MThd" &&
            u32be(header,4)==6L && u16be(header,8) in 0..2 && u16be(header,10) in 1..4096 &&
            u16be(header,12)!=0 && (resourceLength==null||resourceLength>=14)) {
            return FileMagicResult("audio/midi", "Standard MIDI")
        }

        // FastTracker II: "Extended Module: "
        if (header.size >= 17 && String(header, 0, 16, StandardCharsets.US_ASCII) == "Extended Module:") {
            return FileMagicResult("audio/x-xm", "FastTracker II Module")
        }

        // Impulse Tracker: "IMPM"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "IMPM") {
            return FileMagicResult("audio/x-it", "Impulse Tracker Module")
        }

        // Scream Tracker 3: "SCRM" at offset 44
        if (header.size >= 48 && String(header, 44, 4, StandardCharsets.US_ASCII) == "SCRM") {
            return FileMagicResult("audio/x-s3m", "Scream Tracker 3 Module")
        }

        // DSD / DSF: "DSD "
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "DSD ") {
            return FileMagicResult("audio/x-dsf", "Direct Stream Digital")
        }

        // BMP: "BM" + reserved 0 at bytes 6..9
        if (header.size >= 14 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte() &&
            u16le(header, 6) == 0 && u16le(header, 8) == 0
        ) {
            return FileMagicResult("image/bmp", "BMP Image")
        }

        // ICO: 00 00 01 00
        if (header.size >= 6 && u8(header, 0) == 0 && u8(header, 1) == 0 &&
            u8(header, 2) == 1 && u8(header, 3) == 0 && u16le(header, 4) in 1..64
        ) {
            return FileMagicResult("image/x-icon", "ICO Icon Image")
        }

        // JPEG XL
        if ((header.size >= 2 && u8(header, 0) == 0xFF && u8(header, 1) == 0x0A) ||
            (header.size >= 12 && u8(header, 0) == 0x00 && u8(header, 1) == 0x00 &&
                u8(header, 2) == 0x00 && u8(header, 3) == 0x0C &&
                String(header, 4, 4, StandardCharsets.US_ASCII) == "JXL ")
        ) {
            return FileMagicResult("image/jxl", "JPEG XL Image")
        }

        // QOI (Quite OK Image): "qoif"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "qoif") {
            return FileMagicResult("image/x-qoi", "QOI Image")
        }
        if (header.size >= 16 && String(header, 0, 8, StandardCharsets.US_ASCII) == "farbfeld") {
            val width = u32be(header, 8)
            val height = u32be(header, 12)
            if (width in 1L..32768L && height in 1L..32768L &&
                (resourceLength == null || 16L + width * height * 8L <= resourceLength))
                return FileMagicResult("image/x-farbfeld", "Farbfeld Image")
        }

        // TIFF byte order and classic TIFF marker. BigTIFF has a different
        // marker and is deliberately not claimed unless its header is valid.
        if (header.size >= 8 &&
            ((u8(header, 0) == 0x49 && u8(header, 1) == 0x49 && u8(header, 2) == 0x2A && u8(header, 3) == 0x00) ||
                (u8(header, 0) == 0x4D && u8(header, 1) == 0x4D && u8(header, 2) == 0x00 && u8(header, 3) == 0x2A))) {
            return FileMagicResult("image/tiff", "TIFF Image")
        }

        // Common self-identifying legacy bitmap formats.
        if (header.size >= 8 && String(header, 0, 8, StandardCharsets.US_ASCII) == "gimp xcf") {
            return FileMagicResult("image/x-xcf", "GIMP XCF Image")
        }
        if (header.size >= 8 && String(header, 0, 4, StandardCharsets.US_ASCII) == "icns") {
            val declared = u32be(header, 4)
            if (declared >= 8L && (resourceLength == null || declared <= resourceLength))
                return FileMagicResult("image/icns", "Apple Icon Image")
        }
        if (header.size >= 12 && u16be(header, 0) == 0x01DA) {
            val storage = u8(header, 2)
            val bytesPerChannel = u8(header, 3)
            val dimension = u16be(header, 4)
            val width = u16be(header, 6)
            val height = u16be(header, 8)
            val channels = u16be(header, 10)
            if (storage in 0..1 && bytesPerChannel in 1..2 && dimension in 1..3 &&
                width in 1..32768 && height in 1..32768 && channels in 1..4)
                return FileMagicResult("image/x-sgi", "Silicon Graphics RGB Image")
        }
        if (header.size >= 32 && u32be(header, 0) == 0x59A66A95L) {
            val width = u32be(header, 4)
            val height = u32be(header, 8)
            val depth = u32be(header, 12)
            val imageLength = u32be(header, 16)
            val type = u32be(header, 20)
            val mapType = u32be(header, 24)
            if (width in 1L..32768L && height in 1L..32768L && depth in setOf(1L, 8L, 24L, 32L) &&
                imageLength <= (resourceLength ?: Long.MAX_VALUE) && type in 0L..3L && mapType in 0L..2L)
                return FileMagicResult("image/x-cmu-raster", "Sun Raster Image")
        }
        if (header.size >= 68 && u8(header, 0) == 0x0A && u8(header, 1) in setOf(0, 2, 3, 4, 5) &&
            u8(header, 2) == 1 && u8(header, 3) in setOf(1, 2, 4, 8)) {
            val xmin = u16le(header, 4)
            val ymin = u16le(header, 6)
            val xmax = u16le(header, 8)
            val ymax = u16le(header, 10)
            val planes = u8(header, 65)
            val bytesPerLine = u16le(header, 66)
            if (xmax >= xmin && ymax >= ymin && planes in 1..4 && bytesPerLine > 0)
                return FileMagicResult("image/vnd.zbrush.pcx", "ZSoft PCX Image")
        }
        if (fileNameHint?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in setOf("tga", "icb", "vda", "vst") &&
            isPlausibleTga(header, resourceLength)) {
            return FileMagicResult("image/x-tga", "Truevision TGA Image")
        }
        detectRawRetroImage(header, fileNameHint, resourceLength)?.let { return it }
        if (header.size >= 86 && String(header, 60, 8, StandardCharsets.US_ASCII) == "vIMGView") {
            val records = u16be(header, 76)
            val firstRecord = u32be(header, 78)
            if (records > 0 && firstRecord >= 86L && (resourceLength == null || firstRecord < resourceLength))
                return FileMagicResult("image/x-palm-pdb", "Palm ImageViewer Database")
        }

        // JP2 codestreams and boxed JP2 files use different signatures.
        if (header.size >= 12 && String(header, 4, 4, StandardCharsets.US_ASCII) == "jP  " &&
            header[0] == 0.toByte() && header[1] == 0.toByte() && header[2] == 0.toByte() && header[3] == 12.toByte()) {
            return FileMagicResult("image/jp2", "JPEG 2000 Image")
        }
        if (header.size >= 2 && u8(header, 0) == 0xFF && u8(header, 1) == 0x4F &&
            header.size >= 4 && u8(header, 2) == 0xFF && u8(header, 3) == 0x51) {
            return FileMagicResult("image/jp2", "JPEG 2000 Codestream")
        }

        // PSD: "8BPS"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "8BPS") {
            return FileMagicResult("image/vnd.adobe.photoshop", "Adobe Photoshop Image")
        }

        // OpenEXR: 76 2F 31 01
        if (header.size >= 4 && u8(header, 0) == 0x76 && u8(header, 1) == 0x2F && u8(header, 2) == 0x31 && u8(header, 3) == 0x01) {
            return FileMagicResult("image/x-exr", "OpenEXR Image")
        }

        // 8. Documents & Databases
        // Palm database type/creator, not the ambiguous .pdb extension.
        if(header.size>=68 && String(header,60,8,StandardCharsets.US_ASCII)=="BOOKMOBI")
            return FileMagicResult("application/x-mobipocket-ebook","Mobipocket/Kindle Book")
        if(header.size>=5 && String(header,0,5,StandardCharsets.US_ASCII)=="{\\rtf")
            return FileMagicResult("application/rtf","Rich Text Document")

        // PDF: "%PDF-"
        if (header.size >= 5 && String(header, 0, 5, StandardCharsets.US_ASCII) == "%PDF-") {
            return FileMagicResult("application/pdf", "PDF Document")
        }

        // SQLite 3: "SQLite format 3\0"
        if (header.size >= 16 && String(header, 0, 15, StandardCharsets.US_ASCII) == "SQLite format 3" && header[15] == 0.toByte()) {
            return FileMagicResult("application/vnd.sqlite3", "SQLite 3 Database")
        }

        // Compound File Binary is shared by legacy Office formats. The extension
        // selects the subtype only after the container signature is confirmed.
        if (header.size >= 8 && header.take(8).map { it.toInt() and 0xff } ==
            listOf(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) {
            val extension = fileNameHint?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            val officeMime = when (extension) {
                "doc", "dot" -> "application/msword"
                "xls", "xlt" -> "application/vnd.ms-excel"
                "ppt", "pot", "pps" -> "application/vnd.ms-powerpoint"
                else -> null
            }
            return FileMagicResult(officeMime ?: "application/x-ole-storage",
                if (officeMime != null) "Legacy Microsoft Office Compound Document" else "OLE Compound Document")
        }

        // 9. Executables & Bytecode
        // ELF: 7F 'E' 'L' 'F'
        if (header.size >= 5 && u8(header, 0) == 0x7F && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        ) {
            val bits = if (u8(header, 4) == 2) "64-bit" else "32-bit"
            return FileMagicResult("application/x-elf", "ELF $bits Binary")
        }

        // Android DEX: "dex\n"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "dex\n") {
            return FileMagicResult("application/vnd.android.dex", "Android Dalvik Executable (DEX)")
        }

        // Java Class: CA FE BA BE
        if (header.size >= 4 && u8(header, 0) == 0xCA && u8(header, 1) == 0xFE &&
            u8(header, 2) == 0xBA && u8(header, 3) == 0xBE
        ) {
            return FileMagicResult("application/java-vm", "Java Class Bytecode")
        }

        // WebAssembly: 00 61 73 6D
        if (header.size >= 4 && u8(header, 0) == 0x00 && header[1] == 'a'.code.toByte() &&
            header[2] == 's'.code.toByte() && header[3] == 'm'.code.toByte()
        ) {
            return FileMagicResult("application/wasm", "WebAssembly Binary")
        }

        // 10. Audio & Video
        // FLAC: signature plus the mandatory STREAMINFO metadata block.
        if (header.size >= 42 && String(header, 0, 4, StandardCharsets.US_ASCII) == "fLaC" &&
            (u8(header,4) and 0x7f)==0 && ((u8(header,5) shl 16) or (u8(header,6) shl 8) or u8(header,7))==34) {
            var packed=0L;for(i in 18..25)packed=(packed shl 8)or u8(header,i).toLong()
            val sampleRate=(packed ushr 44).toInt();val channels=((packed ushr 41).toInt()and 7)+1
            val depth=((packed ushr 36).toInt()and 31)+1;val total=packed and 0xfffffffffL
            if(sampleRate in 1..655350 && channels in 1..8 && depth in 4..32 && (total==0L||total<=0xfffffffffL))
                return FileMagicResult("audio/flac", "FLAC Audio")
        }

        // Ogg container header plus the codec identification packet.
        if (header.size >= 28 && String(header, 0, 4, StandardCharsets.US_ASCII) == "OggS" && u8(header,4)==0) {
            val segments=u8(header,26);val payloadAt=27+segments
            val payload=if(payloadAt<header.size)String(header,payloadAt,minOf(header.size-payloadAt,32),StandardCharsets.ISO_8859_1)else ""
            val codec=when {
                payload.startsWith("\u0001vorbis")||payload.startsWith("OpusHead")||payload.startsWith("Speex  ")||payload.startsWith("\u007fFLAC") -> "audio/ogg"
                payload.startsWith("\u0080theora") -> "video/ogg"
                else -> null
            }
            if(codec!=null)return FileMagicResult(codec,if(codec.startsWith("audio/"))"Ogg Audio"else"Ogg Video")
        }

        // Validate the tag/header fields; erased ROMs beginning FF FF are not MPEG audio.
        if (header.size >= 10 && String(header, 0, 3, StandardCharsets.US_ASCII) == "ID3" &&
            u8(header,3) in 2..4 && u8(header,4)!=255 && (6..9).all{u8(header,it)<128} &&
            (u8(header,5) and when(u8(header,3)){2->0x3f;3->0x1f;else->0x0f})==0) {
            val tagSize = (u8(header,6).toLong() shl 21) or (u8(header,7).toLong() shl 14) or
                (u8(header,8).toLong() shl 7) or u8(header,9).toLong()
            val audioAt = 10L + tagSize + if ((u8(header,5) and 0x10) != 0) 10L else 0L
            if (audioAt < header.size && hasMpegFrameHeader(header, audioAt.toInt(), resourceLength))
                return FileMagicResult("audio/mpeg", "MPEG Audio")
        }
        // MPEG field constraints: FFmpeg libavcodec/mpegaudiodecheader.h (format facts).
        if (header.size >= 4 && u8(header, 0) == 0xFF && (u8(header, 1) and 0xE0) == 0xE0 &&
            (u8(header,1) ushr 3 and 3)!=1 && (u8(header,2) ushr 4) in 1..14 &&
            (u8(header,2) ushr 2 and 3)!=3 && (u8(header,3) and 3)!=2) {
            val layer = (u8(header, 1) shr 1) and 0x03
            if (layer in 1..3) {
                val layerName = when (layer) { 3 -> "Layer I"; 2 -> "Layer II"; else -> "MP3" }
                return FileMagicResult("audio/mpeg", "MPEG $layerName Audio")
            }
        }

        // ASF / WMA: 30 26 B2 75 8E 66 CF 11 A6 D9 00 AA 00 62 CE 6C
        if (header.size >= 30 &&
            u8(header, 0) == 0x30 && u8(header, 1) == 0x26 && u8(header, 2) == 0xB2 && u8(header, 3) == 0x75 &&
            u8(header, 4) == 0x8E && u8(header, 5) == 0x66 && u8(header, 6) == 0xCF && u8(header, 7) == 0x11 &&
            u8(header, 8) == 0xA6 && u8(header, 9) == 0xD9 && u8(header, 10) == 0x00 && u8(header, 11) == 0xAA &&
            u8(header, 12) == 0x00 && u8(header, 13) == 0x62 && u8(header, 14) == 0xCE && u8(header, 15) == 0x6C
        ) {
            var objectSize=0L;for(i in 23 downTo 16)objectSize=(objectSize shl 8)or u8(header,i).toLong()
            val objects=u32le(header,24)
            if(objectSize<30||objects !in 1L..4096L||resourceLength!=null&&objectSize>resourceLength) return null
            val extension=fileNameHint?.substringAfterLast('.',"")?.lowercase(Locale.ROOT)
            return when(extension){
                "wma"->FileMagicResult("audio/x-ms-wma","Windows Media Audio (WMA)")
                "wmv"->FileMagicResult("video/x-ms-wmv","Windows Media Video (WMV)")
                else->FileMagicResult("application/vnd.ms-asf","Advanced Systems Format")
            }
        }

        // GSM 06.10: 33-byte frames with 0xD0 upper nibble
        if (header.size >= 33 && (u8(header, 0) and 0xF0) == 0xD0 &&
            (header.size < 66 || (u8(header, 33) and 0xF0) == 0xD0)
        ) {
            return FileMagicResult("audio/x-gsm", "GSM 06.10 Audio")
        }

        // MP4 / M4A / MOV ("ftyp" at offset 4)
        if (header.size >= 12 && String(header, 4, 4, StandardCharsets.US_ASCII) == "ftyp") {
            val brand = String(header, 8, 4, StandardCharsets.US_ASCII)
            return if (brand.startsWith("3gp") || brand.startsWith("3gr") || brand.startsWith("3gs")) {
                FileMagicResult("video/3gpp", "3GPP Media ($brand)")
            } else if (brand.startsWith("3g2")) {
                FileMagicResult("video/3gpp2", "3GPP2 Media ($brand)")
            } else if (brand in setOf("avif", "avis")) {
                FileMagicResult("image/avif", "AVIF Image ($brand)")
            } else if (brand in setOf("heic", "heix", "hevc", "hevx")) {
                FileMagicResult("image/heic", "HEIF Image ($brand)")
            } else if (brand in setOf("mif1", "msf1")) {
                FileMagicResult("image/heif", "HEIF Image ($brand)")
            } else if (brand == "M4A " || brand == "M4B ") {
                FileMagicResult("audio/mp4", "MPEG-4 Audio ($brand)")
            } else {
                FileMagicResult("video/mp4", "MPEG-4 Video ($brand)")
            }
        }

        // MKV / WebM: 1A 45 DF A3
        if (header.size >= 4 && u8(header, 0) == 0x1A && u8(header, 1) == 0x45 &&
            u8(header, 2) == 0xDF && u8(header, 3) == 0xA3
        ) {
            val ascii = String(header, 0, header.size.coerceAtMost(64), StandardCharsets.ISO_8859_1).lowercase(Locale.ROOT)
            return if (ascii.contains("webm")) {
                FileMagicResult("video/webm", "WebM Video")
            } else {
                FileMagicResult("video/x-matroska", "Matroska (MKV) Video")
            }
        }

        // 11. MBR Partitioned Disk Image check (if 512 bytes with valid partition table and 55 AA)
        if (header.size >= 512 && isMbrPartitionTable(header)) {
            return FileMagicResult("application/x-raw-disk-image", "MBR Partitioned Disk Image", isArchiveOrDiscImage = true)
        }

        // Bencode has no fixed signature. For .torrent candidates, require a
        // bounded top-level dictionary containing an info dictionary; the
        // torrent parser performs full validation when the user opens it.
        if (fileNameHint?.endsWith(".torrent", ignoreCase = true) == true && hasTorrentInfoDictionary(header)) {
            return FileMagicResult("application/x-bittorrent", "BitTorrent Metainfo")
        }

        // 12. Text / Script / Markup heuristics
        return detectTextFormat(header)
    }

    /** Validate the chunk structure needed to identify an IFF ILBM/PBM bitmap. */
    private fun isValidIlbmHeader(header: ByteArray, resourceLength: Long?): Boolean {
        if (header.size < 20) return false
        val formLength = u32be(header, 4)
        if (formLength < 4L) return false
        val formEnd = formLength + 8L
        if (resourceLength != null && formEnd > resourceLength) return false
        val formType = String(header, 8, 4, StandardCharsets.US_ASCII)
        if (formType != "ILBM" && formType != "PBM ") return false

        val scanEnd = minOf(header.size.toLong(), formEnd).toInt()
        var offset = 12
        var validBitmapHeader = false
        var bodyChunkPresent = false
        while (offset + 8 <= scanEnd) {
            val chunkId = String(header, offset, 4, StandardCharsets.US_ASCII)
            val chunkLength = u32be(header, offset + 4)
            val dataStart = offset.toLong() + 8L
            val dataEnd = dataStart + chunkLength
            if (dataEnd < dataStart || dataEnd > formEnd) return false
            if (chunkId == "BMHD") {
                if (chunkLength < 20L || dataStart + 20L > scanEnd) return false
                val start = dataStart.toInt()
                val width = u16be(header, start)
                val height = u16be(header, start + 2)
                val planes = u8(header, start + 8)
                val compression = u8(header, start + 10)
                if (width !in 1..32768 || height !in 1..32768 || planes !in 1..24 || compression !in 0..1)
                    return false
                validBitmapHeader = true
            } else if (chunkId == "BODY") {
                if (chunkLength == 0L) return false
                bodyChunkPresent = true
            }
            val next = dataEnd + (chunkLength and 1L)
            if (next <= offset.toLong() || next > formEnd) return false
            if (next > scanEnd.toLong()) break
            offset = next.toInt()
        }
        return validBitmapHeader && bodyChunkPresent
    }

    /** TGA has no fixed signature; validate its header geometry using the extension hint. */
    private fun isPlausibleTga(header: ByteArray, resourceLength: Long?): Boolean {
        if (header.size < 18) return false
        val idLength = u8(header, 0)
        val colorMapType = u8(header, 1)
        val imageType = u8(header, 2)
        val width = u16le(header, 12)
        val height = u16le(header, 14)
        val bitsPerPixel = u8(header, 16)
        val descriptor = u8(header, 17)
        if (colorMapType != 0 || imageType !in setOf(2, 3, 10, 11) ||
            width !in 1..32768 || height !in 1..32768 || (descriptor and 0xC0) != 0) return false
        val grayscale = imageType == 3 || imageType == 11
        if (grayscale && bitsPerPixel != 8 || !grayscale && bitsPerPixel !in setOf(24, 32)) return false
        val dataOffset = 18L + idLength
        if (resourceLength != null && (dataOffset > resourceLength ||
                dataOffset + ((width.toLong() * height * bitsPerPixel + 7L) / 8L) > resourceLength && imageType in setOf(2,3)))
            return false
        return true
    }

    /** Headerless fixed-layout retro images need their extension plus size/layout validation. */
    private fun detectRawRetroImage(header: ByteArray, fileName: String?, resourceLength: Long?): FileMagicResult? {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return null
        val length = resourceLength ?: header.size.toLong()
        return when (extension) {
            "neo" -> if (header.size >= 128 && length == 32128L && u16be(header, 2) in 0..2)
                FileMagicResult("image/x-neochrome", "Atari ST Neochrome Image") else null
            "pi1", "pi2", "pi3", "pc1", "pc2", "pc3" -> {
                val resolution = if (header.size >= 2) u16be(header, 0) and 0x0003 else -1
                val compressed = extension.startsWith("pc") || header.size >= 2 && (u16be(header, 0) and 0x8000) != 0
                if (header.size >= 34 && resolution in 0..2 &&
                    (compressed && length > 34L || !compressed && length >= 32034L))
                    FileMagicResult("image/x-degas", "Atari ST DEGAS Image") else null
            }
            "scr" -> if (header.size >= 16 && length == 6912L)
                FileMagicResult("image/x-zx-spectrum-scr", "ZX Spectrum Screen Image") else null
            "kla", "koa" -> if (header.size >= 16 && length in setOf(10000L, 10003L))
                FileMagicResult("image/x-koala", "Commodore 64 Koala Image") else null
            else -> null
        }
    }

    private fun hasTorrentInfoDictionary(bytes: ByteArray): Boolean {
        fun parseString(at: Int): Pair<String, Int>? {
            if (at !in bytes.indices || u8(bytes, at) !in '0'.code..'9'.code) return null
            var colon = at
            while (colon < bytes.size && u8(bytes, colon) in '0'.code..'9'.code) colon++
            if (colon >= bytes.size || u8(bytes, colon) != ':'.code || colon == at) return null
            val length = String(bytes, at, colon - at, StandardCharsets.US_ASCII).toLongOrNull() ?: return null
            if (length < 0L || length > 65536L || length > bytes.size - colon - 1L) return null
            val start = colon + 1
            val end = start + length.toInt()
            return String(bytes, start, length.toInt(), StandardCharsets.ISO_8859_1) to end
        }

        fun skipValue(at: Int, depth: Int): Int? {
            if (depth > 16 || at !in bytes.indices) return null
            return when (u8(bytes, at)) {
                in '0'.code..'9'.code -> parseString(at)?.second
                'i'.code -> {
                    var end = at + 1
                    while (end < bytes.size && u8(bytes, end) != 'e'.code) end++
                    end.takeIf { it < bytes.size }?.plus(1)
                }
                'l'.code, 'd'.code -> {
                    val dictionary = u8(bytes, at) == 'd'.code
                    var cursor = at + 1
                    while (cursor < bytes.size && u8(bytes, cursor) != 'e'.code) {
                        if (dictionary) cursor = parseString(cursor)?.second ?: return null
                        cursor = skipValue(cursor, depth + 1) ?: return null
                    }
                    cursor.takeIf { it < bytes.size }?.plus(1)
                }
                else -> null
            }
        }

        if (bytes.isEmpty() || u8(bytes, 0) != 'd'.code) return false
        var cursor = 1
        while (cursor < bytes.size && u8(bytes, cursor) != 'e'.code) {
            val key = parseString(cursor) ?: return false
            cursor = key.second
            if (key.first == "info") return cursor < bytes.size && u8(bytes, cursor) == 'd'.code
            cursor = skipValue(cursor, 1) ?: return false
        }
        return false
    }

    private val identifying=ThreadLocal.withInitial {false}

    private fun probePrefixLength(fileName: String, expectedMimeType: String?): Int {
        val normalized = expectedMimeType?.let(com.infinifolder.mime.MimeRegistry::normalize)
            ?: com.infinifolder.mime.MimeRegistry.guess(fileName)
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        var defaultLength = 4096
        if (com.infinifolder.mime.MimeRegistry.isUnspecified(normalized) ||
            extension in setOf("bin", "dat", "raw", "img", "ima", "rom", "iso", "cdr")) defaultLength = 69632
        if (normalized.startsWith("image/") || normalized in setOf("video/x-mng", "video/x-fli"))
            defaultLength = 69632
        // Audio probes may need a complete first codec frame/container header
        // before the decoder can distinguish a real stream from an extension.
        if (normalized.startsWith("audio/") || extension in setOf("gp3", "sf2", "sfz", "pat", "nsf", "nsfe", "spc", "gbs", "hes", "kss", "gym", "ay", "vgm", "vgz", "sap", "sgc"))
            defaultLength = 69632
        val declared = runCatching {
            io.github.gretchycat.infinistream.RuntimeCapabilities.installed().descriptors()
                .filter { io.github.gretchycat.infinistream.Operations.IDENTIFY in it.operations }
                .filter { descriptor -> descriptor.formats.any { format ->
                    normalized in format.mimeTypes.map(com.infinifolder.mime.MimeRegistry::normalize) ||
                        extension.isNotEmpty() && format.extensions.any { it.equals(extension, true) }
                } }
        }.getOrDefault(emptyList())
        val registeredEnd = declared.flatMap { it.formats }.flatMap { it.magic }
            .maxOfOrNull { signature -> (signature.offset + signature.hex.length / 2).coerceAtMost(69632L).toInt() } ?: 0
        val handlerPrefix = declared.mapNotNull { it.attributes["probePrefixBytes"]?.toIntOrNull() }
            .maxOrNull() ?: 0
        return maxOf(defaultLength, registeredEnd, handlerPrefix).coerceAtMost(69632)
    }

    /** An ID3 tag is not an audio file by itself; require a valid following MPEG frame header. */
    private fun hasMpegFrameHeader(bytes: ByteArray, start: Int, resourceLength: Long?): Boolean {
        val end = minOf(bytes.size - 3, start + 4096)
        for (at in start.coerceAtLeast(0) until end) {
            if (u8(bytes, at) != 0xff || (u8(bytes, at + 1) and 0xe0) != 0xe0) continue
            val version = (u8(bytes, at + 1) ushr 3) and 3
            val layer = (u8(bytes, at + 1) ushr 1) and 3
            val bitrate = (u8(bytes, at + 2) ushr 4) and 15
            val sampleRate = (u8(bytes, at + 2) ushr 2) and 3
            val emphasis = u8(bytes, at + 3) and 3
            if (version == 1 || layer == 0 || bitrate !in 1..14 || sampleRate == 3 || emphasis == 2) continue
            if (resourceLength == null || at.toLong() + 4 <= resourceLength) return true
        }
        return false
    }

    /** Large embedded ID3 artwork can put a valid MP3 frame past the ordinary prefix probe. */
    private fun detectMpegAfterLargeId3(raf: RandomAccessFile, header: ByteArray, resourceLength: Long): FileMagicResult? {
        if (header.size < 10 || String(header, 0, 3, StandardCharsets.US_ASCII) != "ID3") return null
        val version = u8(header, 3)
        val revision = u8(header, 4)
        val flags = u8(header, 5)
        if (version !in 2..4 || revision == 255 || (6..9).any { u8(header, it) >= 128 }) return null
        val reservedFlags = when (version) { 2 -> 0x3f; 3 -> 0x1f; else -> 0x0f }
        if ((flags and reservedFlags) != 0) return null
        val tagSize = (u8(header, 6).toLong() shl 21) or (u8(header, 7).toLong() shl 14) or
            (u8(header, 8).toLong() shl 7) or u8(header, 9).toLong()
        val audioAt = 10L + tagSize + if (version >= 4 && (flags and 0x10) != 0) 10L else 0L
        if (audioAt <= header.size || audioAt + 4 > resourceLength) return null
        return try {
            val count = minOf(4096L, resourceLength - audioAt).toInt()
            val bytes = ByteArray(count)
            raf.seek(audioAt)
            raf.readFully(bytes)
            if (hasMpegFrameHeader(bytes, 0, resourceLength - audioAt))
                FileMagicResult("audio/mpeg", "MPEG Audio after ID3 metadata")
            else null
        } catch (_: Exception) {
            null
        }
    }

    /** Stream counterpart for providers that can only supply sequential reads. */
    private fun detectMpegAfterLargeId3(input: InputStream, header: ByteArray, resourceLength: Long?): FileMagicResult? {
        if (header.size < 10 || String(header, 0, 3, StandardCharsets.US_ASCII) != "ID3") return null
        val version = u8(header, 3)
        val revision = u8(header, 4)
        val flags = u8(header, 5)
        if (version !in 2..4 || revision == 255 || (6..9).any { u8(header, it) >= 128 }) return null
        val reservedFlags = when (version) { 2 -> 0x3f; 3 -> 0x1f; else -> 0x0f }
        if ((flags and reservedFlags) != 0) return null
        val tagSize = (u8(header, 6).toLong() shl 21) or (u8(header, 7).toLong() shl 14) or
            (u8(header, 8).toLong() shl 7) or u8(header, 9).toLong()
        val audioAt = 10L + tagSize + if (version >= 4 && (flags and 0x10) != 0) 10L else 0L
        if (audioAt <= header.size || audioAt - header.size > 16L * 1024 * 1024 ||
            resourceLength != null && audioAt + 4 > resourceLength) return null

        var remaining = audioAt - header.size
        val discard = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read <= 0) return null
                remaining -= read
            }
        }
        val maxRead = resourceLength?.let { minOf(4096L, it - audioAt).toInt() } ?: 4096
        if (maxRead < 4) return null
        val bytes = readUpTo(input, maxRead)
        return if (hasMpegFrameHeader(bytes, 0, resourceLength?.minus(audioAt)))
            FileMagicResult("audio/mpeg", "MPEG Audio after ID3 metadata") else null
    }

    private fun registeredIdentity(header:ByteArray, fileNameHint: String?, expectedMimeType: String?, resourceLength: Long?,
        probeSource: io.github.gretchycat.infinistream.SourceResource? = null):FileMagicResult? {
        if(identifying.get())return null
        identifying.set(true)
        return try {registeredIdentityOnce(header, fileNameHint, expectedMimeType, resourceLength, probeSource)}finally{identifying.set(false)}
    }
    private fun registeredIdentityOnce(header:ByteArray, fileNameHint: String?, expectedMimeType: String?, resourceLength: Long?,
        probeSource: io.github.gretchycat.infinistream.SourceResource?):FileMagicResult? {
        val registry=io.github.gretchycat.infinistream.RuntimeCapabilities.installed()
        val mimeHint = expectedMimeType ?: fileNameHint?.let(com.infinifolder.mime.MimeRegistry::guess)
        val probeMetadata=io.github.gretchycat.infinistream.StreamMetadata(
            content = io.github.gretchycat.infinistream.ContentType(mimeType = mimeHint),
            name = fileNameHint, length = resourceLength ?: header.size.toLong(),
            finiteness = if (resourceLength != null) io.github.gretchycat.infinistream.Finiteness.FINITE else io.github.gretchycat.infinistream.Finiteness.UNKNOWN,
            capabilities = setOf(io.github.gretchycat.infinistream.Capability.READ) +
                if (resourceLength != null) setOf(io.github.gretchycat.infinistream.Capability.KNOWN_LENGTH) else emptySet())

        val extension = fileNameHint?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val candidateMime = mimeHint?.let(com.infinifolder.mime.MimeRegistry::normalize)
        // Probe handlers suggested by the extension/MIME or by a matching
        // declared signature. This keeps per-file validation bounded as listings
        // grow, while still allowing a signature to identify a mismatched file.
        val eligibleHandlers = registry.registeredHandlers(io.github.gretchycat.infinistream.Operations.IDENTIFY).filter { handler ->
            val descriptor = handler.describe()
            descriptor.id != "store.magic" && descriptor.formats.any { format ->
                val matchingIdentity = candidateMime != null && format.mimeTypes.any { declaredMime ->
                    val declared = com.infinifolder.mime.MimeRegistry.normalize(declaredMime)
                    declared == candidateMime || declared.endsWith("/*") && candidateMime.startsWith(declared.dropLast(1))
                } || extension.isNotEmpty() && format.extensions.any { it.equals(extension, true) }
                matchingIdentity || format.magic.any { it.matches(header) }
            }
        }

        // Ask relevant registered parsers to validate the candidate against
        // their own structure. Only signature or verified results can set an
        // identity; a matching extension by itself remains a candidate.
        val source = probeSource?.let { original -> object : io.github.gretchycat.infinistream.SourceResource {
            override val metadata = probeMetadata
            override fun open(): io.github.gretchycat.infinistream.ByteSource = original.open()
        } } ?: object : io.github.gretchycat.infinistream.SourceResource {
            override val metadata = probeMetadata
            override fun open(): io.github.gretchycat.infinistream.ByteSource =
                io.github.gretchycat.infinistream.InputStreamSource(header.inputStream(), probeMetadata)
        }
        val resource = io.github.gretchycat.infinistream.CapabilityResource(probeMetadata, source = source)
        val probeContext = io.github.gretchycat.infinistream.CapabilityProbeContext(resource,
            io.github.gretchycat.infinistream.ProbeBudget(maxBytes = minOf(header.size, 69632), maxProbes = 64,
                maxNanos = 100_000_000, maxDepth = 0))
        val validated = eligibleHandlers.asSequence().mapNotNull { handler ->
            val descriptor = handler.describe()
            val match = try { handler.probe(probeContext) }
            catch (cancel: java.io.InterruptedIOException) { throw cancel }
            catch (_: Exception) { return@mapNotNull null }
            catch (_: LinkageError) { return@mapNotNull null }
            if (match.confidence == io.github.gretchycat.infinistream.MatchConfidence.NONE) return@mapNotNull null
            val operations = match.operations.intersect(descriptor.operations)
            if (io.github.gretchycat.infinistream.Operations.IDENTIFY !in operations) return@mapNotNull null
            val availability = try { registry.availability(handler) }
                catch (cancel: java.io.InterruptedIOException) { throw cancel }
                catch (_: Exception) { return@mapNotNull null }
                catch (_: LinkageError) { return@mapNotNull null }
            if (availability.state == io.github.gretchycat.infinistream.AvailabilityState.UNAVAILABLE) return@mapNotNull null
            io.github.gretchycat.infinistream.DiscoveredCapability(handler, availability,
                match.copy(operations = operations))
        }
            .filter {
                val confidence = it.probe?.confidence
                it.descriptor.id != "store.magic" && (confidence == io.github.gretchycat.infinistream.MatchConfidence.SIGNATURE ||
                    confidence == io.github.gretchycat.infinistream.MatchConfidence.VERIFIED)
            }
            .mapNotNull { found ->
                val explicit = found.probe?.metadata?.get("mimeType")?.let(com.infinifolder.mime.MimeRegistry::normalize)
                val signed = found.descriptor.formats.asSequence()
                    .filter { it.magic.any { signature -> signature.matches(header) } }
                    .flatMap { it.mimeTypes.asSequence() }
                    .map(com.infinifolder.mime.MimeRegistry::normalize)
                    .filterNot { it.contains('*') }.distinct().toList()
                val mime = explicit ?: when {
                    expectedMimeType != null && expectedMimeType in signed -> expectedMimeType
                    signed.size == 1 -> signed.single()
                    else -> null
                }
                mime?.let { FileMagicResult(it, found.descriptor.title,
                    found.descriptor.attributes["container"] == "true") }
            }.distinctBy { it.mimeType }.toList()
        val selected = when {
            expectedMimeType != null && validated.any { com.infinifolder.mime.MimeRegistry.normalize(it.mimeType) == com.infinifolder.mime.MimeRegistry.normalize(expectedMimeType) } ->
                validated.first { com.infinifolder.mime.MimeRegistry.normalize(it.mimeType) == com.infinifolder.mime.MimeRegistry.normalize(expectedMimeType) }
            validated.size == 1 -> validated.single()
            else -> null
        }
        if (selected != null) return selected

        val input=object:io.github.gretchycat.infinistream.PositionalReadable {
            override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                io.github.gretchycat.infinistream.requireRange(position,buffer,offset,length)
                if(length==0)return 0
                if(position>=header.size)return -1
                val count=minOf(length,header.size-position.toInt());header.copyInto(buffer,offset,position.toInt(),position.toInt()+count);return count
            }
        }
        val reader=io.github.gretchycat.infinistream.ProbeReader(input,probeMetadata)
        val candidates=mutableListOf<Triple<String,String,Boolean>>()
        eligibleHandlers.forEach { entry ->
            if(entry.descriptor.id=="store.magic")return@forEach
            val probe=entry.service(io.github.gretchycat.infinistream.FormatProbe::class.java)?:return@forEach
            try {probe.detect(reader).filter{it.confidence>=.8}.forEach {candidate->candidate.content.mimeType?.let {mime->
                candidates+=Triple(com.infinifolder.mime.MimeRegistry.normalize(mime),entry.descriptor.title,entry.descriptor.attributes["container"]=="true")
            }}}catch(cancel:java.io.InterruptedIOException){throw cancel}catch(_:Exception){}
        }
        // Ordinary decode signatures can be weak (e.g. ID3); only explicit identity claims may replace built-in identification.
        registry.descriptors().filter{it.attributes["identityMagic"]=="true"}.forEach {handler->
            handler.formats.filter {f->f.magic.any{it.matches(header)}}.forEach{format->
                format.mimeTypes.filter{!it.contains('*')}.forEach {mime->candidates+=Triple(
                    com.infinifolder.mime.MimeRegistry.normalize(mime),handler.title,handler.attributes["container"]=="true")}
            }
        }
        return candidates.distinctBy{it.first}.singleOrNull()?.let{FileMagicResult(it.first,it.second,it.third)}
    }

    private enum class OuterCompressor(val label: String, val mimeType: String) {
        GZIP("Gzip", "application/gzip"),
        BZIP2("BZip2", "application/x-bzip2"),
        XZ("XZ", "application/x-xz"),
        ZSTD("Zstandard", "application/zstd"),
        COMPRESS("Unix compress", "application/x-compress")
    }

    private fun detectOuterCompressor(header: ByteArray): OuterCompressor? {
        if (header.size >= 2 && u8(header, 0) == 0x1F && u8(header, 1) == 0x9D) return OuterCompressor.COMPRESS
        if (header.size >= 2 && u8(header, 0) == 0x1F && u8(header, 1) == 0x8B) {
            return OuterCompressor.GZIP
        }
        if (header.size >= 3 && header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()) {
            return OuterCompressor.BZIP2
        }
        if (header.size >= 6 && u8(header, 0) == 0xFD && header[1] == '7'.code.toByte() && header[2] == 'z'.code.toByte() &&
            header[3] == 'X'.code.toByte() && header[4] == 'Z'.code.toByte() && u8(header, 5) == 0x00
        ) {
            return OuterCompressor.XZ
        }
        if (header.size >= 4 && u8(header, 0) == 0x28 && u8(header, 1) == 0xB5 &&
            u8(header, 2) == 0x2F && u8(header, 3) == 0xFD
        ) {
            return OuterCompressor.ZSTD
        }
        return null
    }

    private fun openCompressedStream(raw: InputStream, compressor: OuterCompressor): InputStream {
        val bis = BufferedInputStream(raw, 32768)
        return when (compressor) {
            OuterCompressor.GZIP -> GzipCompressorInputStream(bis, true)
            OuterCompressor.BZIP2 -> BZip2CompressorInputStream(bis, true)
            OuterCompressor.XZ -> com.infinifolder.archive.DecoderMemory.xz(bis)
            OuterCompressor.ZSTD -> CompressorStreamFactory().createCompressorInputStream(bis)
            OuterCompressor.COMPRESS -> ZCompressorInputStream(bis)
        }
    }

    internal fun ufsVersion(bytes: ByteArray, offset: Int): Int? {
        val little = u32le(bytes, offset)
        val big = ((little and 255) shl 24) or ((little and 0xff00) shl 8) or
            ((little ushr 8) and 0xff00) or ((little ushr 24) and 255)
        return when {
            little == 0x011954L || big == 0x011954L -> 1
            little == 0x19540119L || big == 0x19540119L -> 2
            else -> null
        }
    }

    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buf, total, maxBytes - total)
            if (read <= 0) break
            total += read
        }
        return if (total == maxBytes) buf else buf.copyOf(total)
    }

    private fun readUpTo(source: io.github.gretchycat.infinistream.ByteSource, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = source.read(buf, total, maxBytes - total)
            if (read < 0) break
            if (read == 0 || read > maxBytes - total) throw java.io.IOException("Invalid source read result: $read")
            total += read
        }
        return if (total == maxBytes) buf else buf.copyOf(total)
    }

    private fun isFatBpb(bpb: ByteArray): Boolean {
        val bytesPerSector = u16le(bpb, 11)
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096)) return false
        val sectorsPerCluster = u8(bpb, 13)
        if (sectorsPerCluster == 0 || (sectorsPerCluster and (sectorsPerCluster - 1)) != 0 || sectorsPerCluster > 128) {
            return false
        }
        val reservedSectors = u16le(bpb, 14)
        if (reservedSectors <= 0) return false
        val numFats = u8(bpb, 16)
        if (numFats !in 1..4) return false
        val fsType16 = String(bpb, 54, 8, StandardCharsets.US_ASCII)
        val fsType32 = String(bpb, 82, 8, StandardCharsets.US_ASCII)
        if (fsType16.startsWith("FAT") || fsType32.startsWith("FAT")) return true

        val jump0 = u8(bpb, 0)
        val hasBootSig = u8(bpb, 510) == 0x55 && u8(bpb, 511) == 0xAA
        val totalSectors = u16le(bpb, 19).takeIf { it != 0 }?.toLong() ?: u32le(bpb, 32)
        val sectorsPerFat = u16le(bpb, 22).takeIf { it != 0 }?.toLong() ?: u32le(bpb, 36)
        return (jump0 == 0xEB || jump0 == 0xE9) && hasBootSig && totalSectors > reservedSectors && sectorsPerFat > 0L
    }

    private fun isMbrPartitionTable(mbr: ByteArray): Boolean {
        if (u8(mbr, 510) != 0x55 || u8(mbr, 511) != 0xAA) return false
        var validPartitions = 0
        for (i in 0 until 4) {
            val pos = 446 + i * 16
            val status = u8(mbr, pos)
            if (status != 0x00 && status != 0x80) return false
            val type = u8(mbr, pos + 4)
            val startLba = u32le(mbr, pos + 8)
            val numSectors = u32le(mbr, pos + 12)
            if (type != 0 && startLba > 0L && numSectors > 0L) {
                validPartitions++
            }
        }
        return validPartitions > 0
    }

    private fun detectTextFormat(header: ByteArray): FileMagicResult? {
        val sampleLen = header.size.coerceAtMost(512)
        if (sampleLen == 0) return null

        // Check UTF-16 BOM
        if (sampleLen >= 2 &&
            ((u8(header, 0) == 0xFF && u8(header, 1) == 0xFE) || (u8(header, 0) == 0xFE && u8(header, 1) == 0xFF))
        ) {
            return FileMagicResult("text/plain", "UTF-16 Plain Text")
        }

        // Reject binary data containing null bytes
        var controlCount = 0
        for (i in 0 until sampleLen) {
            val b = u8(header, i)
            if (b == 0) return null
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x1B) {
                controlCount++
            }
        }
        if (controlCount * 20 > sampleLen) return null // > 5% non-whitespace control chars

        val terminalSample = String(header, 0, sampleLen, StandardCharsets.ISO_8859_1)
        if (Regex("(?m)^!\\|(?:\\*|c[0-9A-Z]{1,2}(?:\\||$)|[LRB][0-9A-Z]{8}(?:\\||$))").containsMatchIn(terminalSample))
            return FileMagicResult("image/x-rip", "RIPscrip Graphics")
        val ansiControls = Regex("\u001b\\[[0-?]*[ -/]*[@-~]").findAll(terminalSample).take(2).count()
        if (ansiControls >= 2) return FileMagicResult("image/x-ansi", "ANSI Text Art")
        // A permissive UTF-8 String replaces arbitrary high bytes with glyphs. That
        // must not turn erased ROM/binary data into a successful text detection.
        // Keep a partial UTF-8 character at the bounded sample's end permissible.
        val utf8=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(header,0,sampleLen),
            java.nio.CharBuffer.allocate(sampleLen),false)
        if(utf8.isError){
            val ascii=(0 until sampleLen).count{val b=u8(header,it);b in 0x20..0x7e||b==9||b==10||b==13}
            val boxes=(0 until sampleLen).count{u8(header,it) in 0xb0..0xdf}
            val multiline=(0 until sampleLen).any{u8(header,it)==10||u8(header,it)==13}
            if(ascii*4<sampleLen*3&&!(multiline&&boxes*4>=sampleLen))return null
        }
        val text = String(header, 0, sampleLen, StandardCharsets.UTF_8).trimStart('\uFEFF').trimStart()
        if (text.startsWith("#!")) {
            val shebang = text.lineSequence().firstOrNull()?.take(48) ?: "#!"
            return FileMagicResult("text/plain", "Executable Script ($shebang)")
        }
        val lower = text.lowercase(Locale.ROOT)
        if (text.startsWith("/* XPM */") && text.contains('"') &&
            Regex("(?m)^\\s*\\\"\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\\"").containsMatchIn(text)) {
            return FileMagicResult("image/x-xpixmap", "X PixMap Image")
        }
        if (Regex("(?m)^#define\\s+\\w+_(?:width|height)\\s+\\d+").findAll(text).count() >= 2 &&
            Regex("(?is)static\\s+(?:unsigned\\s+)?char\\s+\\w+_bits\\s*\\[").containsMatchIn(text)) {
            return FileMagicResult("image/x-xbitmap", "X Bitmap Image")
        }
        if (text.startsWith("%!PS-Adobe") || text.startsWith("%!")) {
            return FileMagicResult("image/x-eps", "PostScript / EPS Document")
        }
        if (lower.startsWith("<svg") || (lower.startsWith("<?xml") && lower.contains("<svg"))) {
            return FileMagicResult("image/svg+xml", "SVG Vector Image")
        }
        if (lower.startsWith("<?xml")) {
            return FileMagicResult("text/xml", "XML Document")
        }
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) {
            return FileMagicResult("text/html", "HTML Document")
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            return FileMagicResult("application/json", "JSON Text")
        }
        return FileMagicResult("text/plain",if(utf8.isError)"Legacy 8-bit Plain Text"else "UTF-8 Plain Text")
    }

    private fun u8(buf: ByteArray, offset: Int): Int =
        buf[offset].toInt() and 0xFF

    private fun u16le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun u16be(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun u32be(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
            (buf[offset + 3].toLong() and 0xFFL)

    private fun u32le(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)
}
