package com.infinifolder.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

data class FilePermissions(
    val canRead: Boolean,
    val canWrite: Boolean,
    val canExecute: Boolean,
    val mode: Int?,
    val formatted: String?,
    val owner: String?,
    val group: String?
)

data class SubjectIdentity(
    val uid: Int? = null,
    val username: String? = null,
    val gid: Int? = null,
    val groupname: String? = null,
    val supplementaryGids: Set<Int> = emptySet(),
    val supplementaryGroups: Set<String> = emptySet(),
    val isRoot: Boolean = (uid == 0 || username == "root")
)

object PermissionHelper {

    fun formatPosix(mode: Int, isDirectory: Boolean): String {
        val typeChar = if (isDirectory) 'd' else '-'
        val uRead = if ((mode and 0b100_000_000) != 0) 'r' else '-'
        val uWrite = if ((mode and 0b010_000_000) != 0) 'w' else '-'
        val uExec = when {
            (mode and 0b100_000_000_000) != 0 -> if ((mode and 0b001_000_000) != 0) 's' else 'S'
            (mode and 0b001_000_000) != 0 -> 'x'
            else -> '-'
        }
        val gRead = if ((mode and 0b000_100_000) != 0) 'r' else '-'
        val gWrite = if ((mode and 0b000_010_000) != 0) 'w' else '-'
        val gExec = when {
            (mode and 0b010_000_000_000) != 0 -> if ((mode and 0b000_001_000) != 0) 's' else 'S'
            (mode and 0b000_001_000) != 0 -> 'x'
            else -> '-'
        }
        val oRead = if ((mode and 0b000_000_100) != 0) 'r' else '-'
        val oWrite = if ((mode and 0b000_000_010) != 0) 'w' else '-'
        val oExec = when {
            (mode and 0b001_000_000_000) != 0 -> if ((mode and 0b000_000_001) != 0) 't' else 'T'
            (mode and 0b000_000_001) != 0 -> 'x'
            else -> '-'
        }
        val octal = String.format("%04o", mode and 0xFFF)
        return "$typeChar$uRead$uWrite$uExec$gRead$gWrite$gExec$oRead$oWrite$oExec ($octal)"
    }

    fun parseAndroidUid(name: String): Int? {
        val match = Regex("^u(\\d+)_a(\\d+)$").find(name) ?: return null
        val user = match.groupValues[1].toIntOrNull() ?: return null
        val app = match.groupValues[2].toIntOrNull() ?: return null
        return user * 100000 + 10000 + app
    }

    fun evaluatePosixPermissions(
        mode: Int,
        fileUid: Int? = null,
        fileOwner: String? = null,
        fileGid: Int? = null,
        fileGroup: String? = null,
        subject: SubjectIdentity
    ): Triple<Boolean, Boolean, Boolean> {
        if (subject.isRoot) {
            val anyExec = (mode and 0b001_001_001) != 0
            return Triple(true, true, anyExec)
        }

        val isOwner = when {
            fileUid != null && subject.uid != null -> fileUid == subject.uid
            fileOwner != null && subject.username != null -> {
                if (fileOwner.equals(subject.username, ignoreCase = true)) {
                    true
                } else {
                    val ownerUid = fileOwner.toIntOrNull()
                    val userUid = subject.username.toIntOrNull()
                    val parsedUserUid = parseAndroidUid(subject.username)
                    val parsedOwnerUid = parseAndroidUid(fileOwner)
                    (ownerUid != null && parsedUserUid != null && ownerUid == parsedUserUid) ||
                    (ownerUid != null && userUid != null && ownerUid == userUid) ||
                    (parsedOwnerUid != null && parsedUserUid != null && parsedOwnerUid == parsedUserUid)
                }
            }
            fileOwner != null && subject.uid != null && fileOwner.toIntOrNull() == subject.uid -> true
            fileUid != null && subject.username != null && (subject.username.toIntOrNull() == fileUid || parseAndroidUid(subject.username) == fileUid) -> true
            else -> false
        }

        if (isOwner) {
            val canRead = (mode and 0b100_000_000) != 0
            val canWrite = (mode and 0b010_000_000) != 0
            val canExec = (mode and 0b001_000_000) != 0
            return Triple(canRead, canWrite, canExec)
        }

        val inGroup = when {
            fileGid != null && subject.gid != null && fileGid == subject.gid -> true
            fileGid != null && subject.supplementaryGids.contains(fileGid) -> true
            fileGroup != null && subject.groupname != null && fileGroup.equals(subject.groupname, ignoreCase = true) -> true
            fileGroup != null && subject.supplementaryGroups.any { it.equals(fileGroup, ignoreCase = true) } -> true
            else -> false
        }

        if (inGroup) {
            val canRead = (mode and 0b000_100_000) != 0
            val canWrite = (mode and 0b000_010_000) != 0
            val canExec = (mode and 0b000_001_000) != 0
            return Triple(canRead, canWrite, canExec)
        }

        val canRead = (mode and 0b000_000_100) != 0
        val canWrite = (mode and 0b000_000_010) != 0
        val canExec = (mode and 0b000_000_001) != 0
        return Triple(canRead, canWrite, canExec)
    }

    fun getCurrentLocalSubject(): SubjectIdentity {
        return try {
            val uid = android.system.Os.getuid()
            val gid = android.system.Os.getgid()
            val groups: Set<Int> = runCatching {
                val method = android.system.Os::class.java.getMethod("getgroups")
                (method.invoke(null) as? IntArray)?.toSet() ?: emptySet()
            }.getOrDefault(emptySet())
            val username = System.getProperty("user.name")
            SubjectIdentity(
                uid = uid,
                username = username,
                gid = gid,
                groupname = null,
                supplementaryGids = groups,
                isRoot = uid == 0
            )
        } catch (_: Throwable) {
            val username = try { System.getProperty("user.name") } catch (_: Throwable) { null }
            SubjectIdentity(
                username = username,
                isRoot = username == "root"
            )
        }
    }

    fun getFilePermissions(file: File): FilePermissions {
        val canRead = try { file.canRead() } catch (_: Throwable) { true }
        val canWrite = try { file.canWrite() } catch (_: Throwable) { true }
        val canExecute = try { file.canExecute() } catch (_: Throwable) { false }

        // Attempt 1: Java NIO PosixFileAttributes (standard on Linux JVM & supported file systems)
        try {
            val path = file.toPath()
            val attrs = Files.readAttributes(path, PosixFileAttributes::class.java)
            val perms = attrs.permissions()
            val mode = posixPermissionsToMode(perms)
            val owner = attrs.owner()?.name ?: formatUid(0)
            val group = attrs.group()?.name ?: formatGid(0)
            val formatted = formatPosix(mode, file.isDirectory)
            val eval = evaluatePosixPermissions(
                mode = mode,
                fileOwner = owner,
                fileGroup = group,
                subject = getCurrentLocalSubject()
            )
            // On Android, file.can*() reflects the true OS kernel & permission system access.
            // If the kernel grants read/write access (e.g. via MANAGE_EXTERNAL_STORAGE, app internal storage,
            // or supplementary GIDs), canRead/canWrite is true regardless of whether POSIX owner matches app UID.
            val effectiveRead = if (file.exists()) canRead else (canRead || eval.first)
            val effectiveWrite = if (file.exists()) canWrite else (canWrite || eval.second)
            val effectiveExec = if (file.exists()) canExecute else (canExecute || eval.third)
            return FilePermissions(
                canRead = effectiveRead,
                canWrite = effectiveWrite,
                canExecute = effectiveExec,
                mode = mode,
                formatted = formatted,
                owner = owner,
                group = group
            )
        } catch (_: Throwable) {
            // PosixFileAttributes not supported or running on platform where NIO provider is non-POSIX
        }

        // Attempt 2: android.system.Os.stat
        try {
            val stat = android.system.Os.stat(file.absolutePath)
            val mode = stat.st_mode
            val formatted = formatPosix(mode, file.isDirectory)
            val owner = formatUid(stat.st_uid)
            val group = formatGid(stat.st_gid)
            val eval = evaluatePosixPermissions(
                mode = mode,
                fileUid = stat.st_uid,
                fileGid = stat.st_gid,
                subject = getCurrentLocalSubject()
            )
            val effectiveRead = if (file.exists()) canRead else (canRead || eval.first)
            val effectiveWrite = if (file.exists()) canWrite else (canWrite || eval.second)
            val effectiveExec = if (file.exists()) canExecute else (canExecute || eval.third)
            return FilePermissions(
                canRead = effectiveRead,
                canWrite = effectiveWrite,
                canExecute = effectiveExec,
                mode = mode,
                formatted = formatted,
                owner = owner,
                group = group
            )
        } catch (_: Throwable) {
            // Android OS stat unavailable
        }

        // Fallback based on file.can*
        val synthesizedMode = if (file.isDirectory) {
            var m = 0b101_101_101 // r-xr-xr-x (0555)
            if (canWrite) m = m or 0b010_010_010 // rwxrwxrwx (0777)
            if (!canRead) m = m and 0b011_011_011
            m
        } else {
            var m = 0b100_100_100 // r--r--r-- (0444)
            if (canWrite) m = m or 0b010_010_010 // rw-rw-rw- (0666)
            if (canExecute) m = m or 0b001_001_001 // rwxrwxrwx (0777)
            if (!canRead) m = m and 0b011_011_011
            m
        }

        val appUid = runCatching { android.system.Os.getuid() }.getOrDefault(-1)
        val appGid = runCatching { android.system.Os.getgid() }.getOrDefault(-1)

        return FilePermissions(
            canRead = canRead,
            canWrite = canWrite,
            canExecute = canExecute,
            mode = synthesizedMode,
            formatted = formatPosix(synthesizedMode, file.isDirectory),
            owner = if (appUid >= 0) formatUid(appUid) else null,
            group = if (appGid >= 0) formatGid(appGid) else null
        )
    }

    fun formatUid(uid: Int): String = when (uid) {
        0 -> "root (0)"
        1000 -> "system (1000)"
        1023 -> "media_rw (1023)"
        2000 -> "shell (2000)"
        runCatching { android.system.Os.getuid() }.getOrDefault(-1) -> "app ($uid)"
        else -> uid.toString()
    }

    fun formatGid(gid: Int): String = when (gid) {
        0 -> "root (0)"
        1000 -> "system (1000)"
        1015 -> "sdcard_rw (1015)"
        1023 -> "media_rw (1023)"
        1078 -> "ext_data_rw (1078)"
        1079 -> "ext_obb_rw (1079)"
        9997 -> "everybody (9997)"
        runCatching { android.system.Os.getgid() }.getOrDefault(-1) -> "app ($gid)"
        else -> gid.toString()
    }

    fun posixPermissionsToMode(perms: Set<PosixFilePermission>): Int {
        var mode = 0
        if (perms.contains(PosixFilePermission.OWNER_READ)) mode = mode or 0b100_000_000
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) mode = mode or 0b010_000_000
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) mode = mode or 0b001_000_000
        if (perms.contains(PosixFilePermission.GROUP_READ)) mode = mode or 0b000_100_000
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) mode = mode or 0b000_010_000
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) mode = mode or 0b000_001_000
        if (perms.contains(PosixFilePermission.OTHERS_READ)) mode = mode or 0b000_000_100
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) mode = mode or 0b000_000_010
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) mode = mode or 0b000_000_001
        return mode
    }
}
