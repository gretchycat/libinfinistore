package com.infinifolder.archive

object ArchiveMimeTypes {
    const val MIME_TYPE_DIR = "vnd.android.document/directory"
    fun guessMimeType(name: String, isDirectory: Boolean): String = com.infinifolder.mime.MimeRegistry.guess(name, isDirectory)
}
