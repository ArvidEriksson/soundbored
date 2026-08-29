package dev.arvid.soundbored.data

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Clips live as plain files in filesDir/clips, with a small JSON index next to them holding
 * the boards, the clips on them, and which board was last open. Nothing here touches the
 * network: once a clip is cut it is a self-contained file.
 */
class ClipRepository private constructor(root: File) {

    private val clipsDir = File(root, "clips").apply { mkdirs() }
    private val indexFile = File(root, "clips.json")

    private val _boards = MutableStateFlow<List<Board>>(emptyList())
    val boards: StateFlow<List<Board>> = _boards.asStateFlow()

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    private val _activeBoardId = MutableStateFlow("")
    val activeBoardId: StateFlow<String> = _activeBoardId.asStateFlow()

    init {
        readIndex()
    }

    val activeBoard: Board? get() = _boards.value.firstOrNull { it.id == _activeBoardId.value }

    fun clipsOn(boardId: String): List<Clip> = _clips.value.filter { it.boardId == boardId }

    fun fileFor(clip: Clip): File = File(clipsDir, clip.fileName)

    /** Cut into a .tmp file first so a half-written clip can never end up on the board. */
    fun tempClipFile(): File = File(clipsDir, "clip-${UUID.randomUUID()}.tmp")

    fun finalizeClipFile(temp: File, extension: String): File {
        val destination = File(clipsDir, "${temp.nameWithoutExtension}.$extension")
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        return destination
    }

    // ------------------------------------------------------------------------- boards

    @Synchronized
    fun addBoard(name: String): Board {
        val board = Board(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(40).ifBlank { "Board ${_boards.value.size + 1}" },
            createdAt = System.currentTimeMillis(),
        )
        _boards.value = _boards.value + board
        _activeBoardId.value = board.id
        writeIndex()
        return board
    }

    @Synchronized
    fun renameBoard(id: String, name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isBlank()) return
        _boards.value = _boards.value.map { if (it.id == id) it.copy(name = trimmed) else it }
        writeIndex()
    }

    /** Deleting a board takes its sounds with it; the last board can never be removed. */
    @Synchronized
    fun deleteBoard(id: String) {
        if (_boards.value.size <= 1) return
        _clips.value.filter { it.boardId == id }.forEach { fileFor(it).delete() }
        _clips.value = _clips.value.filterNot { it.boardId == id }
        _boards.value = _boards.value.filterNot { it.id == id }
        if (_activeBoardId.value == id) {
            _activeBoardId.value = _boards.value.first().id
        }
        writeIndex()
    }

    @Synchronized
    fun selectBoard(id: String) {
        if (_boards.value.none { it.id == id }) return
        _activeBoardId.value = id
        writeIndex()
    }

    // -------------------------------------------------------------------------- clips

    @Synchronized
    fun add(clip: Clip) {
        _clips.value = _clips.value + clip
        writeIndex()
    }

    @Synchronized
    fun rename(id: String, name: String) {
        _clips.value = _clips.value.map { if (it.id == id) it.copy(name = name) else it }
        writeIndex()
    }

    /**
     * Replaces an edited clip in place: same id, same slot on the board, new audio file.
     * The old file is only deleted once the index points at the new one.
     */
    @Synchronized
    fun update(clip: Clip) {
        val previous = _clips.value.firstOrNull { it.id == clip.id }
        _clips.value = _clips.value.map { if (it.id == clip.id) clip else it }
        writeIndex()
        if (previous != null && previous.fileName != clip.fileName) {
            fileFor(previous).delete()
        }
    }

    @Synchronized
    fun delete(clip: Clip) {
        _clips.value = _clips.value.filterNot { it.id == clip.id }
        fileFor(clip).delete()
        writeIndex()
    }

    /** Colours cycle within a board so a fresh page does not end up all one shade. */
    fun nextColorIndex(boardId: String, paletteSize: Int): Int =
        ((clipsOn(boardId).maxOfOrNull { it.colorIndex } ?: -1) + 1).mod(paletteSize)

    // ------------------------------------------------------------------------ storage

    @Synchronized
    private fun readIndex() {
        val parsed = runCatching { parse(indexFile.takeIf { it.exists() }?.readText()) }
            .onFailure { Log.w(TAG, "Could not read clip index", it) }
            .getOrNull()

        var boards = parsed?.first.orEmpty()
        var clips = parsed?.second.orEmpty().filter { File(clipsDir, it.fileName).exists() }

        if (boards.isEmpty()) {
            boards = listOf(
                Board(UUID.randomUUID().toString(), DEFAULT_BOARD_NAME, System.currentTimeMillis())
            )
        }
        // Clips written before boards existed (or pointing at a deleted board) join the first one.
        val boardIds = boards.map { it.id }.toSet()
        clips = clips.map { if (it.boardId in boardIds) it else it.copy(boardId = boards.first().id) }

        _boards.value = boards
        _clips.value = clips
        _activeBoardId.value = parsed?.third?.takeIf { it in boardIds } ?: boards.first().id
    }

    /** Handles both the current object form and the original bare array of clips. */
    private fun parse(text: String?): Triple<List<Board>, List<Clip>, String?>? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val clips = (0 until array.length()).map { Clip.fromJson(array.getJSONObject(it)) }
            return Triple(emptyList(), clips, null)
        }
        val root = JSONObject(trimmed)
        val boardArray = root.optJSONArray("boards") ?: JSONArray()
        val clipArray = root.optJSONArray("clips") ?: JSONArray()
        return Triple(
            (0 until boardArray.length()).map { Board.fromJson(boardArray.getJSONObject(it)) },
            (0 until clipArray.length()).map { Clip.fromJson(clipArray.getJSONObject(it)) },
            root.optString("activeBoardId").takeIf { it.isNotBlank() },
        )
    }

    private fun writeIndex() {
        val root = JSONObject().apply {
            put("version", INDEX_VERSION)
            put("activeBoardId", _activeBoardId.value)
            put("boards", JSONArray().also { array -> _boards.value.forEach { array.put(it.toJson()) } })
            put("clips", JSONArray().also { array -> _clips.value.forEach { array.put(it.toJson()) } })
        }
        runCatching { indexFile.writeText(root.toString()) }
            .onFailure { Log.e(TAG, "Could not write clip index", it) }
    }

    companion object {
        private const val TAG = "ClipRepository"
        private const val INDEX_VERSION = 2
        const val DEFAULT_BOARD_NAME = "My sounds"

        @Volatile
        private var instance: ClipRepository? = null

        fun get(context: Context): ClipRepository =
            instance ?: synchronized(this) {
                instance ?: ClipRepository(context.applicationContext.filesDir).also { instance = it }
            }

        /** Lets tests exercise storage and migration against a throwaway directory. */
        @VisibleForTesting
        fun createIn(root: File): ClipRepository = ClipRepository(root)
    }
}
