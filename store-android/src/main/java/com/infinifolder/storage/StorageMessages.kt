package com.infinifolder.storage

import java.text.NumberFormat

/** Approximate sizes for messages; exact byte counts belong in diagnostic logs. */
object StorageMessages {
    fun approximateSize(bytes: Long): String {
        val units = arrayOf("bytes", "KB", "MB", "GB", "TB", "PB", "EB")
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1000.0 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val formatter = NumberFormat.getNumberInstance().apply {
            maximumFractionDigits = if (unit > 0 && value < 10) 1 else 0
            minimumFractionDigits = 0
        }
        return "${formatter.format(value)} ${units[unit]}"
    }
}
