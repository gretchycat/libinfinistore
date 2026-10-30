package com.infinifolder.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream

object AppCategories {
    const val GAMES = "Games"
    const val AUDIO = "Audio"
    const val VIDEO = "Video"
    const val PHOTOGRAPHY = "Photography"
    const val SOCIAL = "Social"
    const val NEWS = "News"
    const val MAPS = "Maps"
    const val PRODUCTIVITY = "Productivity"
    const val ACCESSIBILITY = "Accessibility"
    const val SYSTEM = "System"
    const val OTHER = "Other"

    private val FALLBACK_PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x60, 0x60, 0x60, 0x60,
        0x00, 0x00, 0x00, 0x05, 0x00, 0x01, 0xA5.toByte(), 0xF6.toByte(),
        0x45, 0x40, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
        0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
    )

    val ALL_CATEGORIES = listOf(
        GAMES,
        PRODUCTIVITY,
        SOCIAL,
        AUDIO,
        VIDEO,
        PHOTOGRAPHY,
        NEWS,
        MAPS,
        ACCESSIBILITY,
        OTHER,
        SYSTEM
    )

    fun getCategoryIcon(category: String): String = when (category) {
        GAMES -> "🎮"
        AUDIO -> "🎵"
        VIDEO -> "🎬"
        PHOTOGRAPHY -> "📷"
        SOCIAL -> "💬"
        NEWS -> "📰"
        MAPS -> "🗺️"
        PRODUCTIVITY -> "💼"
        ACCESSIBILITY -> "♿"
        SYSTEM -> "⚙️"
        else -> "📦"
    }

    /**
     * Renders the category icon into a PNG byte array suitable for saving as `.icon.png`
     * inside a copied category directory.
     */
    fun renderCategoryIconPng(category: String, sizePx: Int = 256): ByteArray {
        return try {
            val iconEmoji = getCategoryIcon(category)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) ?: return FALLBACK_PNG
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sizePx * 0.72f
                textAlign = Paint.Align.CENTER
                color = 0xFFFFFFFF.toInt()
            }
            val fm = paint.fontMetrics
            val centerY = if (fm != null) {
                (sizePx / 2f) - ((fm.ascent + fm.descent) / 2f)
            } else {
                sizePx * 0.75f
            }
            canvas.drawText(iconEmoji, sizePx / 2f, centerY, paint)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            if (bytes.isNotEmpty()) bytes else FALLBACK_PNG
        } catch (_: Throwable) {
            FALLBACK_PNG
        }
    }

    fun categorize(
        categoryInt: Int,
        isGameFlag: Boolean,
        isSystemFlag: Boolean
    ): String {
        return when (categoryInt) {
            0 -> GAMES // ApplicationInfo.CATEGORY_GAME
            1 -> AUDIO // ApplicationInfo.CATEGORY_AUDIO
            2 -> VIDEO // ApplicationInfo.CATEGORY_VIDEO
            3 -> PHOTOGRAPHY // ApplicationInfo.CATEGORY_IMAGE
            4 -> SOCIAL // ApplicationInfo.CATEGORY_SOCIAL
            5 -> NEWS // ApplicationInfo.CATEGORY_NEWS
            6 -> MAPS // ApplicationInfo.CATEGORY_MAPS
            7 -> PRODUCTIVITY // ApplicationInfo.CATEGORY_PRODUCTIVITY
            8 -> ACCESSIBILITY // ApplicationInfo.CATEGORY_ACCESSIBILITY
            else -> {
                if (isGameFlag) GAMES
                else if (isSystemFlag) SYSTEM
                else OTHER
            }
        }
    }
}
