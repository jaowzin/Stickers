package com.jaowzin.stickers.util

import java.io.File
import java.io.FileInputStream
import kotlin.math.min

object FileFormatDetector {
    private const val MAX_SCAN_BYTES = 64 * 1024

    enum class Category { IMAGE, VIDEO, UNKNOWN }

    data class Detection(
        val format: String,
        val mimeType: String,
        val extension: String,
        val offset: Long,
        val category: Category,
        val animated: Boolean = false
    )

    fun detect(file: File): Detection {
        if (!file.isFile || file.length() <= 0L) return unknown()

        val length = min(file.length(), MAX_SCAN_BYTES.toLong()).toInt()
        val bytes = ByteArray(length)
        val read = FileInputStream(file).use { it.read(bytes) }
        if (read <= 0) return unknown()
        val data = if (read == bytes.size) bytes else bytes.copyOf(read)

        detectAt(data, 0)?.let { return it }
        for (offset in 1 until data.size) {
            detectAt(data, offset)?.let { return it }
        }

        val firstText = data.indexOfFirst { byte ->
            val c = byte.toInt().toChar()
            !c.isWhitespace()
        }
        if (firstText >= 0 && (data[firstText].toInt().toChar() == '{' || data[firstText].toInt().toChar() == '[')) {
            return Detection("JSON/Lottie", "application/json", "json", firstText.toLong(), Category.UNKNOWN)
        }

        return unknown()
    }

    private fun detectAt(data: ByteArray, offset: Int): Detection? {
        if (matches(data, offset, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Detection("PNG", "image/png", "png", offset.toLong(), Category.IMAGE)
        }
        if (matches(data, offset, 0xFF, 0xD8, 0xFF)) {
            return Detection("JPEG", "image/jpeg", "jpg", offset.toLong(), Category.IMAGE)
        }
        if (ascii(data, offset, "GIF87a") || ascii(data, offset, "GIF89a")) {
            return Detection("GIF", "image/gif", "gif", offset.toLong(), Category.IMAGE, animated = true)
        }
        if (ascii(data, offset, "RIFF") && ascii(data, offset + 8, "WEBP")) {
            val animated = containsAscii(data, offset, "ANIM") || containsAscii(data, offset, "ANMF")
            return Detection("WebP", "image/webp", "webp", offset.toLong(), Category.IMAGE, animated)
        }
        if (ascii(data, offset, "RIFF") && ascii(data, offset + 8, "AVI ")) {
            return Detection("AVI", "video/x-msvideo", "avi", offset.toLong(), Category.VIDEO)
        }
        if (matches(data, offset, 0x1A, 0x45, 0xDF, 0xA3)) {
            return Detection("WebM/Matroska", "video/webm", "webm", offset.toLong(), Category.VIDEO)
        }
        if (ascii(data, offset, "OggS")) {
            return Detection("Ogg", "video/ogg", "ogv", offset.toLong(), Category.VIDEO)
        }
        if (ascii(data, offset, "FLV")) {
            return Detection("FLV", "video/x-flv", "flv", offset.toLong(), Category.VIDEO)
        }
        if (ascii(data, offset, "%PDF")) {
            return Detection("PDF", "application/pdf", "pdf", offset.toLong(), Category.UNKNOWN)
        }
        if (ascii(data, offset, "SQLite format 3")) {
            return Detection("SQLite", "application/vnd.sqlite3", "sqlite", offset.toLong(), Category.UNKNOWN)
        }

        if (ascii(data, offset + 4, "ftyp") && offset + 12 <= data.size) {
            val brand = asciiValue(data, offset + 8, 4).lowercase()
            return when (brand) {
                "avif", "avis" -> Detection("AVIF", "image/avif", "avif", offset.toLong(), Category.IMAGE)
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" ->
                    Detection("HEIF/HEIC", "image/heic", "heic", offset.toLong(), Category.IMAGE)
                "3gp4", "3gp5", "3gp6", "3ge6", "3gg6" ->
                    Detection("3GPP", "video/3gpp", "3gp", offset.toLong(), Category.VIDEO)
                else -> Detection("MP4", "video/mp4", "mp4", offset.toLong(), Category.VIDEO)
            }
        }

        return null
    }

    private fun unknown() = Detection(
        format = "Desconhecido",
        mimeType = "application/octet-stream",
        extension = "bin",
        offset = 0L,
        category = Category.UNKNOWN
    )

    private fun matches(data: ByteArray, offset: Int, vararg expected: Int): Boolean {
        if (offset < 0 || offset + expected.size > data.size) return false
        return expected.indices.all { index -> (data[offset + index].toInt() and 0xFF) == expected[index] }
    }

    private fun ascii(data: ByteArray, offset: Int, expected: String): Boolean {
        if (offset < 0 || offset + expected.length > data.size) return false
        return expected.indices.all { index -> data[offset + index].toInt().toChar() == expected[index] }
    }

    private fun asciiValue(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > data.size) return ""
        return buildString(length) {
            repeat(length) { append(data[offset + it].toInt().toChar()) }
        }
    }

    private fun containsAscii(data: ByteArray, start: Int, expected: String): Boolean {
        if (start < 0 || expected.isEmpty()) return false
        val last = data.size - expected.length
        for (index in start..last) {
            if (ascii(data, index, expected)) return true
        }
        return false
    }
}
