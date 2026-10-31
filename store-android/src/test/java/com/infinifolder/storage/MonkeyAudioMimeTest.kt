package com.infinifolder.storage

import com.infinifolder.mime.MimeRegistry
import org.junit.Assert.*
import org.junit.Test

class MonkeyAudioMimeTest {
    @Test fun monkeyAudioUsesAnAudioAssociation() {
        assertEquals("audio/x-ape", MimeRegistry.guess("music.ape"))
    }
}
