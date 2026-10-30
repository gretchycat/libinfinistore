package com.infinifolder.archive

import java.util.Locale

/** Filename family hints only; actual decoding belongs to Vault. */
object NativeArchiveNames {
        fun isSupported(name: String) = family(name).second in setOf("rar", "arj", "cab")
        fun family(name: String): Pair<String, String> {
            if (name.endsWith(".cab", ignoreCase = true)) return name.lowercase(Locale.ROOT) to "cab"
            Regex("(?i)^(.*)\\.part[0-9]+\\.rar$").matchEntire(name)?.let { return ("parts:" + it.groupValues[1].lowercase(Locale.ROOT)) to "rar" }
            Regex("(?i)^(.*)\\.[0-9]{3,}\\.rar$").matchEntire(name)?.let { return ("numbers:" + it.groupValues[1].lowercase(Locale.ROOT)) to "rar" }
            Regex("(?i)^(.*)\\.(rar|cbr|r[0-9]{2})$").matchEntire(name)?.let { return it.groupValues[1].lowercase(Locale.ROOT) to "rar" }
            Regex("(?i)^(.*)\\.(arj|a[0-9]{2})$").matchEntire(name)?.let { return it.groupValues[1].lowercase(Locale.ROOT) to "arj" }
            return name.lowercase(Locale.ROOT) to ""
        }
}
