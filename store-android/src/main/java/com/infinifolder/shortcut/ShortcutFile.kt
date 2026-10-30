package com.infinifolder.shortcut

import org.json.JSONObject

data class ShortcutInfo(
    val targetUri: String,
    val targetPath: String? = null,
    val displayNameOverride: String? = null,
    val targetMimeType: String? = null,
    val targetIsDirectory: Boolean = false,
    val iconBase64: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", 1)
        obj.put("targetUri", targetUri)
        if (!targetPath.isNullOrBlank()) obj.put("targetPath", targetPath)
        if (!displayNameOverride.isNullOrBlank()) obj.put("displayNameOverride", displayNameOverride)
        if (!targetMimeType.isNullOrBlank()) obj.put("targetMimeType", targetMimeType)
        obj.put("targetIsDirectory", targetIsDirectory)
        if (!iconBase64.isNullOrBlank()) obj.put("iconBase64", iconBase64)
        obj.put("createdAtMillis", createdAtMillis)
        return obj.toString(2)
    }

    companion object {
        const val SHORTCUT_EXTENSION = ".iflink"
        const val SHORTCUT_MIME = "application/vnd.infinifolder.shortcut+json"

        fun isShortcut(name: String, mimeType: String? = null): Boolean {
            if (mimeType == "vnd.android.document/directory") return false
            return name.endsWith(SHORTCUT_EXTENSION, ignoreCase = true) || name.endsWith(".lnk", ignoreCase = true) ||
                    mimeType == SHORTCUT_MIME ||
                    mimeType == "application/x-shortcut" || mimeType == "application/x-ms-shortcut"
        }

        fun fromJson(jsonStr: String): ShortcutInfo? {
            return try {
                val obj = JSONObject(jsonStr)
                val targetUri = obj.optString("targetUri").takeIf { it.isNotEmpty() } ?: return null
                ShortcutInfo(
                    targetUri = targetUri,
                    targetPath = obj.optString("targetPath").takeIf { it.isNotEmpty() },
                    displayNameOverride = obj.optString("displayNameOverride").takeIf { it.isNotEmpty() },
                    targetMimeType = obj.optString("targetMimeType").takeIf { it.isNotEmpty() },
                    targetIsDirectory = obj.optBoolean("targetIsDirectory", false),
                    iconBase64 = obj.optString("iconBase64").takeIf { it.isNotEmpty() },
                    createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
