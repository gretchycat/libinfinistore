package com.infinifolder.model

import java.io.*

/** Null means unknown, never an instruction to clear destination attributes. */
data class TransferMetadata(
    val mode: Int? = null, val uid: Long? = null, val gid: Long? = null,
    val modifiedSeconds: Long? = null, val modifiedNanos: Int = 0,
    val accessedSeconds: Long? = null, val accessedNanos: Int = 0,
    val xattrs: Map<String, ByteArray>? = null
) {
    init {
        require(mode == null || mode in 0..0xfff)
        require(uid == null || uid in 0..0xffffffffL)
        require(gid == null || gid in 0..0xffffffffL)
        require(modifiedNanos in 0..999999999 && accessedNanos in 0..999999999)
    }
    val isEmpty: Boolean get() = mode == null && uid == null && gid == null && modifiedSeconds == null && accessedSeconds == null && xattrs == null
    fun matches(actual: TransferMetadata): Boolean = (mode == null || mode == actual.mode) &&
        (uid == null || uid == actual.uid) && (gid == null || gid == actual.gid) &&
        (modifiedSeconds == null || modifiedSeconds == actual.modifiedSeconds && modifiedNanos == actual.modifiedNanos) &&
        (accessedSeconds == null || accessedSeconds == actual.accessedSeconds && accessedNanos == actual.accessedNanos) &&
        (xattrs == null || actual.xattrs != null && xattrs.keys == actual.xattrs.keys &&
            xattrs.all { (name, value) -> value.contentEquals(actual.xattrs.getValue(name)) })
    fun sameAs(other: TransferMetadata): Boolean = mode == other.mode && uid == other.uid && gid == other.gid &&
        modifiedSeconds == other.modifiedSeconds && modifiedNanos == other.modifiedNanos &&
        accessedSeconds == other.accessedSeconds && accessedNanos == other.accessedNanos &&
        (xattrs == null && other.xattrs == null || xattrs != null && other.xattrs != null &&
            xattrs.keys == other.xattrs.keys && xattrs.all { (key, value) -> value.contentEquals(other.xattrs.getValue(key)) })

    fun writeTo(file: File) = DataOutputStream(file.outputStream().buffered()).use { out ->
        var mask = 0
        if (mode != null) mask = mask or 1
        if (uid != null) mask = mask or 2
        if (gid != null) mask = mask or 4
        if (modifiedSeconds != null) mask = mask or 8
        if (accessedSeconds != null) mask = mask or 16
        if (xattrs != null) mask = mask or 32
        out.writeInt(0x49464d31); out.writeInt(mask)
        out.writeInt(mode ?: 0); out.writeInt(uid?.toInt() ?: 0); out.writeInt(gid?.toInt() ?: 0)
        out.writeLong(modifiedSeconds ?: 0); out.writeInt(modifiedNanos)
        out.writeLong(accessedSeconds ?: 0); out.writeInt(accessedNanos)
        out.writeInt(xattrs?.size ?: 0)
        xattrs?.toSortedMap()?.forEach { (name, value) ->
            val bytes = name.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..255 && '\u0000' !in name && value.size <= 65536) { "Invalid transfer attribute" }
            out.writeInt(bytes.size); out.write(bytes); out.writeInt(value.size); out.write(value)
        }
    }
    companion object {
        fun from(metadata: ResourceMetadata) = TransferMetadata(metadata.permissionsMode,
            metadata.owner?.toLongOrNull(), metadata.group?.toLongOrNull(),
            metadata.modifiedAtMillis?.let { Math.floorDiv(it, 1000) },
            metadata.modifiedAtMillis?.let { (Math.floorMod(it, 1000) * 1_000_000).toInt() } ?: 0)
        fun readFrom(file: File): TransferMetadata {
            require(file.length() in 48..(2 * 1024 * 1024)) { "Invalid transfer metadata size" }
            return DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == 0x49464d31) { "Unknown transfer metadata version" }
                val mask = input.readInt()
                require(mask and 63 == mask) { "Unsupported transfer metadata fields" }
                val mode = input.readInt().takeIf { mask and 1 != 0 }
                val uid = (input.readInt().toLong() and 0xffffffffL).takeIf { mask and 2 != 0 }
                val gid = (input.readInt().toLong() and 0xffffffffL).takeIf { mask and 4 != 0 }
                val modified = input.readLong().takeIf { mask and 8 != 0 }; val mn = input.readInt()
                val accessed = input.readLong().takeIf { mask and 16 != 0 }; val an = input.readInt()
                require(mn in 0..999999999 && an in 0..999999999) { "Invalid metadata timestamp" }
                val count = input.readInt(); require(count in 0..4096) { "Invalid attribute count" }
                val attrs = linkedMapOf<String, ByteArray>()
                repeat(count) {
                    val n = input.readInt(); require(n in 1..255)
                    val name = String(ByteArray(n).also(input::readFully), Charsets.UTF_8)
                    val size = input.readInt(); require(size in 0..65536 && '\u0000' !in name && name !in attrs)
                    attrs[name] = ByteArray(size).also(input::readFully)
                }
                require(input.read() == -1) { "Unexpected metadata trailer" }
                TransferMetadata(mode, uid, gid, modified, mn, accessed, an, attrs.takeIf { mask and 32 != 0 })
            }
        }
    }
}
