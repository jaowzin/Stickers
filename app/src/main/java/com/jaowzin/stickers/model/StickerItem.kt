package com.jaowzin.stickers.model

import org.json.JSONObject

data class StickerItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val format: String,
    val mimeType: String,
    val extension: String,
    val dataOffset: Long,
    val category: Category,
    val animated: Boolean
) {
    enum class Category { IMAGE, VIDEO, UNKNOWN }

    companion object {
        fun fromJson(raw: String): StickerItem {
            val json = JSONObject(raw)
            return StickerItem(
                path = json.getString("path"),
                name = json.getString("name"),
                size = json.getLong("size"),
                lastModified = json.getLong("lastModified"),
                format = json.getString("format"),
                mimeType = json.getString("mimeType"),
                extension = json.getString("extension"),
                dataOffset = json.getLong("dataOffset"),
                category = runCatching {
                    Category.valueOf(json.getString("category"))
                }.getOrDefault(Category.UNKNOWN),
                animated = json.optBoolean("animated", false)
            )
        }
    }
}
