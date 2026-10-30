package com.infinifolder.apps

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val category: String,
    val sourceDir: String,
    val sizeBytes: Long,
    val lastUpdateTime: Long,
    val versionName: String? = null,
    val isSystem: Boolean = false,
    val canExtract: Boolean = true
)
