package dev.arvid.soundbored.data

/** Where a clip's audio came from, which is also how it is fetched again when edited. */
enum class SourceKind {
    YOUTUBE,
    LOCAL_FILE;

    companion object {
        fun fromKey(key: String?): SourceKind =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: YOUTUBE
    }
}
