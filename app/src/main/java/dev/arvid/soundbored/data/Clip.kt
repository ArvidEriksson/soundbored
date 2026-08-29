package dev.arvid.soundbored.data

import org.json.JSONObject

/** One soundboard button: a fixed audio file cut out of a longer source. */
data class Clip(
    val id: String,
    val name: String,
    val fileName: String,
    val boardId: String,
    val sourceUrl: String,
    val sourceKind: SourceKind,
    val startMs: Long,
    val endMs: Long,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val colorIndex: Int,
    val createdAt: Long,
) {
    val durationMs: Long get() = endMs - startMs

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("fileName", fileName)
        put("boardId", boardId)
        put("sourceUrl", sourceUrl)
        put("sourceKind", sourceKind.name)
        put("startMs", startMs)
        put("endMs", endMs)
        put("fadeInMs", fadeInMs)
        put("fadeOutMs", fadeOutMs)
        put("colorIndex", colorIndex)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Clip = Clip(
            id = json.getString("id"),
            name = json.optString("name", "Clip"),
            fileName = json.getString("fileName"),
            boardId = json.optString("boardId", ""),
            sourceUrl = json.optString("sourceUrl", ""),
            sourceKind = SourceKind.fromKey(json.optString("sourceKind")),
            startMs = json.optLong("startMs"),
            endMs = json.optLong("endMs"),
            fadeInMs = json.optLong("fadeInMs"),
            fadeOutMs = json.optLong("fadeOutMs"),
            colorIndex = json.optInt("colorIndex"),
            createdAt = json.optLong("createdAt"),
        )
    }
}
