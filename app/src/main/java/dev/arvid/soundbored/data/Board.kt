package dev.arvid.soundbored.data

import org.json.JSONObject

/** A named page of sound buttons. Every clip belongs to exactly one. */
data class Board(
    val id: String,
    val name: String,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Board = Board(
            id = json.getString("id"),
            name = json.optString("name", "Board"),
            createdAt = json.optLong("createdAt"),
        )
    }
}
