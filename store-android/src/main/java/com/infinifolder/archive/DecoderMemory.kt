package com.infinifolder.archive

import java.io.InputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/** Codec dictionaries are RAM, independently of the shared disposable disk cache. */
object DecoderMemory {
    val limitKiB get() = (Runtime.getRuntime().maxMemory() / 8 / 1024).coerceIn(16 * 1024, 64 * 1024).toInt()
    fun xz(input: InputStream, concatenated: Boolean = true) = XZCompressorInputStream(input, concatenated, limitKiB)
    fun lzma(input: InputStream) = LZMACompressorInputStream(input, limitKiB)
    fun automatic(input: InputStream) = CompressorStreamFactory(true, limitKiB).createCompressorInputStream(input)
}
