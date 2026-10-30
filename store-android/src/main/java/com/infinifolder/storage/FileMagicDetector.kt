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
        if (cleanMime.isEmpty() || cleanMime == "application/octet-stream" || cleanMime == "application/binary") {
            return true
        }
        val dotIdx = fileName.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == fileName.length - 1) return true
        val ext = fileName.substring(dotIdx + 1).lowercase(Locale.ROOT)
        return ext in AMBIGUOUS_EXTENSIONS
    }

    fun detect(file: File, peekCompressedPayload: Boolean = false): FileMagicResult? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val len = file.length()
        if (len <= 0L) return null

        val cacheKey = "${file.absolutePath}:$len:${file.lastModified()}:$peekCompressedPayload"
        synchronized(fileCache) {
            fileCache.get(cacheKey)?.let { return it }
        }

        val detected = try {
            RandomAccessFile(file, "r").use { raf ->
                val headLen = len.coerceAtMost(2048L).toInt()
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
                    val innerResult = if (peekCompressedPayload) {
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

                    if (innerResult != null && innerResult.isArchiveOrDiscImage) {
                        FileMagicResult(
                            mimeType = innerResult.mimeType,
                            description = "${compressor.label}-compressed ${innerResult.description}",
                            isArchiveOrDiscImage = true
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
                    detectFromBytes(header, extMagic, isoMagic, vhdFooter, ufsMagic)
                }
            }
        } catch (_: Exception) {
            null
        }

        if (detected != null) {
            val nameLower = file.name.lowercase(Locale.ROOT)
            val finalDetected = if (detected.mimeType == "text/plain" && nameLower.endsWith(".g722")) {
                FileMagicResult("audio/x-g722", "G.722 Audio")
            } else if (nameLower.endsWith(".wma") && detected.mimeType != "audio/x-ms-wma") {
                FileMagicResult("audio/x-ms-wma", "Windows Media Audio (WMA)")
            } else {
                detected
            }
            synchronized(fileCache) {
                fileCache.put(cacheKey, finalDetected)
            }
            return finalDetected
        }
        return detected
    }

    fun detectFromStream(openStream: () -> InputStream): FileMagicResult? {
        return try {
            val prefix = openStream().use { readUpTo(it, 69632) }
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
                if (innerResult != null && innerResult.isArchiveOrDiscImage) {
                    return FileMagicResult(
                        mimeType = innerResult.mimeType,
                        description = "${compressor.label}-compressed ${innerResult.description}",
                        isArchiveOrDiscImage = true
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
            detectFromBytes(prefix, null, null)
        } catch (_: Throwable) {
            null
        }
    }

    fun detectFromBytes(
        header: ByteArray,
        extMagicAt1080: ByteArray? = null,
        isoMagicAt32769: ByteArray? = null,
        vhdFooterAtEof: ByteArray? = null,
        ufsMagicAtSuperblock: Int? = null
    ): FileMagicResult? {
        if (header.isEmpty()) return null
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

        // Standard MIDI: "MThd"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "MThd") {
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

        // PSD: "8BPS"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "8BPS") {
            return FileMagicResult("image/vnd.adobe.photoshop", "Adobe Photoshop Image")
        }

        // OpenEXR: 76 2F 31 01
        if (header.size >= 4 && u8(header, 0) == 0x76 && u8(header, 1) == 0x2F && u8(header, 2) == 0x31 && u8(header, 3) == 0x01) {
            return FileMagicResult("image/x-exr", "OpenEXR Image")
        }

        // 8. Documents & Databases
        // PDF: "%PDF-"
        if (header.size >= 5 && String(header, 0, 5, StandardCharsets.US_ASCII) == "%PDF-") {
            return FileMagicResult("application/pdf", "PDF Document")
        }

        // SQLite 3: "SQLite format 3\0"
        if (header.size >= 16 && String(header, 0, 15, StandardCharsets.US_ASCII) == "SQLite format 3" && header[15] == 0.toByte()) {
            return FileMagicResult("application/vnd.sqlite3", "SQLite 3 Database")
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
        // FLAC: "fLaC"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "fLaC") {
            return FileMagicResult("audio/flac", "FLAC Audio")
        }

        // OGG: "OggS"
        if (header.size >= 4 && String(header, 0, 4, StandardCharsets.US_ASCII) == "OggS") {
            return FileMagicResult("audio/ogg", "Ogg Audio")
        }

        // MPEG audio: "ID3" or frame sync (MPEG Layer I/II/III)
        if (header.size >= 3 && String(header, 0, 3, StandardCharsets.US_ASCII) == "ID3") {
            return FileMagicResult("audio/mpeg", "MPEG Audio")
        }
        if (header.size >= 2 && u8(header, 0) == 0xFF && (u8(header, 1) and 0xE0) == 0xE0) {
            val layer = (u8(header, 1) shr 1) and 0x03
            if (layer in 1..3) {
                val layerName = when (layer) { 3 -> "Layer I"; 2 -> "Layer II"; else -> "MP3" }
                return FileMagicResult("audio/mpeg", "MPEG $layerName Audio")
            }
        }

        // ASF / WMA: 30 26 B2 75 8E 66 CF 11 A6 D9 00 AA 00 62 CE 6C
        if (header.size >= 16 &&
            u8(header, 0) == 0x30 && u8(header, 1) == 0x26 && u8(header, 2) == 0xB2 && u8(header, 3) == 0x75 &&
            u8(header, 4) == 0x8E && u8(header, 5) == 0x66 && u8(header, 6) == 0xCF && u8(header, 7) == 0x11 &&
            u8(header, 8) == 0xA6 && u8(header, 9) == 0xD9 && u8(header, 10) == 0x00 && u8(header, 11) == 0xAA &&
            u8(header, 12) == 0x00 && u8(header, 13) == 0x62 && u8(header, 14) == 0xCE && u8(header, 15) == 0x6C
        ) {
            return FileMagicResult("audio/x-ms-wma", "Windows Media Audio (WMA)")
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
            return if (brand == "M4A " || brand == "M4B ") {
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

        // 12. Text / Script / Markup heuristics
        return detectTextFormat(header)
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
            OuterCompressor.XZ -> XZCompressorInputStream(bis, true)
            OuterCompressor.ZSTD -> CompressorStreamFactory().createCompressorInputStream(bis)
            OuterCompressor.COMPRESS -> ZCompressorInputStream(bis)
        }
    }

    private fun ufsVersion(bytes: ByteArray, offset: Int): Int? {
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

        val text = String(header, 0, sampleLen, StandardCharsets.UTF_8).trimStart('\uFEFF').trimStart()
        if (text.startsWith("#!")) {
            val shebang = text.lineSequence().firstOrNull()?.take(48) ?: "#!"
            return FileMagicResult("text/plain", "Executable Script ($shebang)")
        }
        val lower = text.lowercase(Locale.ROOT)
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
        return FileMagicResult("text/plain", "UTF-8 Plain Text")
    }

    private fun u8(buf: ByteArray, offset: Int): Int =
        buf[offset].toInt() and 0xFF

    private fun u16le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32le(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)
}
