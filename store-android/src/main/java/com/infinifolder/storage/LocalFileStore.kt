package com.infinifolder.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Direct files only. Never accepts a SAF document ID. Follows symbolic links on supported filesystems. */
class LocalFileStore(root: File) {
    val root: File = root.canonicalFile

    fun resolve(components: List<String>): File {
        var file = root
        components.forEach { component ->
            validateName(component)
            file = File(file, component)
        }
        val resolved = file.canonicalFile
        require(resolved == root || resolved.toPath().startsWith(root.toPath())) { "Outside shared storage" }
        return resolved
    }

    fun components(file: File): List<String> {
        val relative = root.toPath().relativize(file.toPath()).map { it.toString() }.filter { it.isNotEmpty() }
        resolve(relative)
        return relative
    }

    fun list(directory: File): List<File> {
        resolve(components(directory))
        return directory.listFiles()?.toList() ?: throw IOException("Android did not allow reading this directory")
    }

    fun createDirectory(parent: File, name: String): File {
        validateName(name)
        val created = resolve(components(parent) + name)
        Files.createDirectory(created.toPath())
        return created
    }

    fun createFile(parent: File, name: String): File {
        validateName(name)
        val created = resolve(components(parent) + name)
        Files.createFile(created.toPath())
        return created
    }

    fun rename(file: File, name: String): File {
        require(file != root) { "Cannot rename the storage root" }
        validateName(name)
        val destination = resolve(components(requireNotNull(file.parentFile)) + name)
        Files.move(file.toPath(), destination.toPath()) // No REPLACE_EXISTING: never overwrite.
        return destination
    }

    fun delete(file: File) {
        require(file != root) { "Cannot delete the storage root" }
        resolve(components(file))
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) list(file).forEach(::delete)
        Files.delete(file.toPath())
    }

    fun copy(source: File, destination: File): File {
        resolve(components(source)); resolve(components(destination))
        require(source != root) { "Cannot copy the storage root" }
        require(destination.isDirectory) { "Choose a destination directory" }
        require(!destination.toPath().startsWith(source.toPath())) { "Cannot copy a folder into itself" }
        val temporary = Files.createTempDirectory(destination.toPath(), ".infinifolder-copy-").toFile()
        val staged = File(temporary, source.name)
        try {
            copyTree(source, staged)
            var index = 1
            var target: File
            while (true) {
                val dot = source.name.lastIndexOf('.').takeIf { it > 0 && !source.isDirectory } ?: source.name.length
                val name = if (index == 1) source.name else source.name.substring(0, dot) + " ($index)" + source.name.substring(dot)
                target = File(destination, name)
                try {
                    Files.move(staged.toPath(), target.toPath())
                    break
                } catch (_: java.nio.file.FileAlreadyExistsException) { index++ }
            }
            return target
        } finally {
            // Only this operation's private temporary tree is removed, never a pre-existing destination.
            if (temporary.exists()) delete(temporary)
        }
    }

    private fun copyTree(source: File, destination: File) {
        resolve(components(source))
        if (Files.isSymbolicLink(source.toPath())) {
            Files.createSymbolicLink(destination.toPath(), Files.readSymbolicLink(source.toPath()))
        } else if (source.isDirectory) {
            Files.createDirectory(destination.toPath())
            list(source).forEach { copyTree(it, File(destination, it.name)) }
        } else {
            Files.copy(source.toPath(), destination.toPath())
            if (!digest(source).contentEquals(digest(destination))) {
                throw IOException("Copy verification failed; original retained")
            }
        }
    }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    private fun validateName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "Invalid file name"
        }
    }
}
