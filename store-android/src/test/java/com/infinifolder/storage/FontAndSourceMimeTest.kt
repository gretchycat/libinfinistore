package com.infinifolder.storage

import com.infinifolder.mime.MimeRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class FontAndSourceMimeTest {
    @Test fun fontExtensionsAndLegacyProviderMimeTypesResolveConsistently() {
        for ((extension, mime) in mapOf("ttf" to "font/ttf", "otf" to "font/otf", "ttc" to "font/collection", "otc" to "font/collection")) {
            assertEquals(mime, MimeRegistry.guess("sample.$extension"))
            assertEquals(mime, MimeRegistry.effective("sample.${extension.uppercase()}", "application/octet-stream"))
        }
        assertEquals("font/ttf", MimeRegistry.normalize("application/x-font-ttf"))
        assertEquals("font/otf", MimeRegistry.normalize("application/vnd.ms-opentype"))
        assertEquals("font/collection", MimeRegistry.normalize("application/x-font-ttc"))
    }
    @Test fun magicIdentifiesNativeFontPrefixesWithoutAFilename() {
        for ((signature, mime) in mapOf(0x00010000 to "font/ttf", 0x4f54544f to "font/otf", 0x74727565 to "font/ttf")) {
            val header = ByteBuffer.allocate(12).putInt(signature).putShort(3).array()
            assertEquals(mime, FileMagicDetector.detectFromBytes(header)?.mimeType)
            assertNotEquals(mime, FileMagicDetector.detectFromBytes(header.copyOf(6))?.mimeType)
        }
        val collection = ByteBuffer.allocate(16).putInt(0x74746366).putInt(0x00010000).putInt(2).putInt(20).array()
        assertEquals("font/collection", FileMagicDetector.detectFromBytes(collection)?.mimeType)
        ByteBuffer.wrap(collection).putInt(8, 0)
        assertNotEquals("font/collection", FileMagicDetector.detectFromBytes(collection)?.mimeType)
    }
    @Test fun genesisRomsHaveANonAudioAssociationInsteadOfGenericMagicFallback() {
        assertEquals("application/x-genesis-rom", MimeRegistry.guess("game.GEN"))
        assertEquals("application/x-genesis-rom", MimeRegistry.effective("game.gen", "application/octet-stream"))
        assertFalse(FileMagicDetector.isAmbiguousOrUnknown("game.gen", MimeRegistry.guess("game.gen")))
    }
    @Test fun sourceAssociationsRefineGenericProviderText() {
        for ((ext, mime) in mapOf("c" to "text/x-csrc", "h" to "text/x-chdr", "cpp" to "text/x-c++src",
            "hpp" to "text/x-c++hdr", "pas" to "text/x-pascal", "adb" to "text/x-adasrc", "bas" to "text/x-basic", "bat" to "application/x-bat")) {
            assertEquals(mime, MimeRegistry.guess("source.$ext"))
            assertEquals(mime, MimeRegistry.effective("source.$ext", "text/plain"))
        }
    }
}
