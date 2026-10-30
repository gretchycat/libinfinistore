package com.infinifolder.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AtomicFileReplacement {
    fun replace(selected: File, data: ByteArray) = replaceWith(selected) { output -> output.write(data) }
    fun replaceFromFile(selected: File, source: File) = replaceWith(selected) { output -> source.inputStream().use { it.copyTo(output) } }
    private fun replaceWith(selected: File, write: (java.io.OutputStream) -> Unit) {
        // Editing a link intentionally edits its target; its directory entry remains intact.
        val target = selected.canonicalFile
        require(target.isFile) { "Original file is unavailable" }
        val temporary = Files.createTempFile(target.parentFile.toPath(), ".infinifolder-save-", ".tmp")
        try {
            temporary.toFile().outputStream().use { output -> write(output); output.fd.sync() }
            val originalPermissions = Files.getPosixFilePermissions(target.toPath())
            // Emulated Android storage can expose fixed permissions without supporting chmod.
            if (Files.getPosixFilePermissions(temporary) != originalPermissions) {
                Files.setPosixFilePermissions(temporary, originalPermissions)
            }
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
