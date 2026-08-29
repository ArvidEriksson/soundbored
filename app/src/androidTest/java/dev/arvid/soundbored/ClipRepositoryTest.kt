package dev.arvid.soundbored

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.arvid.soundbored.data.Clip
import dev.arvid.soundbored.data.ClipRepository
import dev.arvid.soundbored.data.SourceKind
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Storage rules: editing swaps audio in place, boards own their sounds, old indexes still load. */
@RunWith(AndroidJUnit4::class)
class ClipRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var repository: ClipRepository

    @Before
    fun setUp() {
        root = File(context.cacheDir, "repo-test-${UUID.randomUUID()}").apply { mkdirs() }
        repository = ClipRepository.createIn(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun startsWithOneBoardThatOwnsNewSounds() {
        assertEquals(1, repository.boards.value.size)
        val board = repository.boards.value.single()
        assertEquals(board.id, repository.activeBoardId.value)

        repository.add(clip(boardId = board.id))
        assertEquals(1, repository.clipsOn(board.id).size)
    }

    @Test
    fun editingAClipReplacesItsFileButKeepsItsIdentity() {
        val boardId = repository.activeBoardId.value
        val original = clip(boardId = boardId)
        repository.add(original)

        val replacement = original.copy(
            fileName = writeClipFile("second"),
            startMs = 1_000L,
            endMs = 4_000L,
            fadeOutMs = 750L,
        )
        repository.update(replacement)

        val stored = repository.clips.value.filter { it.id == original.id }
        assertEquals("clip was duplicated instead of replaced", 1, stored.size)
        assertEquals(original.colorIndex, stored.single().colorIndex)
        assertEquals(boardId, stored.single().boardId)
        assertEquals(750L, stored.single().fadeOutMs)
        assertEquals("second", repository.fileFor(stored.single()).readText())
        assertFalse(
            "the replaced audio file was left behind",
            repository.fileFor(original).exists(),
        )
        assertEquals(750L, Clip.fromJson(stored.single().toJson()).fadeOutMs)
    }

    @Test
    fun boardsKeepTheirOwnSoundsAndTakeThemWhenDeleted() {
        val first = repository.boards.value.single()
        repository.add(clip(boardId = first.id))

        val second = repository.addBoard("Reactions")
        assertEquals("a new board becomes the open one", second.id, repository.activeBoardId.value)

        val onSecond = clip(boardId = second.id)
        repository.add(onSecond)
        val secondFile = repository.fileFor(onSecond)

        assertEquals(1, repository.clipsOn(first.id).size)
        assertEquals(1, repository.clipsOn(second.id).size)

        repository.deleteBoard(second.id)
        assertTrue(repository.boards.value.none { it.id == second.id })
        assertTrue(repository.clips.value.none { it.id == onSecond.id })
        assertFalse("a deleted board left its audio behind", secondFile.exists())
        assertEquals("the surviving board became active", first.id, repository.activeBoardId.value)
        assertEquals(1, repository.clipsOn(first.id).size)
    }

    @Test
    fun theLastBoardCannotBeDeleted() {
        val only = repository.boards.value.single()
        repository.deleteBoard(only.id)
        assertEquals(1, repository.boards.value.size)
    }

    @Test
    fun boardsAndTheOpenBoardSurviveAReload() {
        val extra = repository.addBoard("Bits")
        repository.add(clip(boardId = extra.id))

        val reopened = ClipRepository.createIn(root)
        assertEquals(2, reopened.boards.value.size)
        assertEquals(extra.id, reopened.activeBoardId.value)
        assertEquals(1, reopened.clipsOn(extra.id).size)
    }

    /** Indexes written before boards existed are a bare array of clips. */
    @Test
    fun clipsFromTheOldIndexFormatJoinTheFirstBoard() {
        val fileName = writeClipFile("legacy")
        val legacy = JSONArray().put(
            clip(boardId = "").copy(fileName = fileName, name = "legacy clip").toJson()
                .apply { remove("boardId"); remove("sourceKind") }
        )
        File(root, "clips.json").writeText(legacy.toString())

        val migrated = ClipRepository.createIn(root)
        val board = migrated.boards.value.single()
        val clip = migrated.clips.value.single()
        assertEquals("legacy clip", clip.name)
        assertEquals(board.id, clip.boardId)
        assertEquals(SourceKind.YOUTUBE, clip.sourceKind)
        assertEquals(1, migrated.clipsOn(board.id).size)
    }

    private fun clip(boardId: String) = Clip(
        id = UUID.randomUUID().toString(),
        name = "test clip",
        fileName = writeClipFile("first"),
        boardId = boardId,
        sourceUrl = "https://youtu.be/dQw4w9WgXcQ",
        sourceKind = SourceKind.YOUTUBE,
        startMs = 0L,
        endMs = 3_000L,
        fadeInMs = 0L,
        fadeOutMs = 0L,
        colorIndex = 3,
        createdAt = 1L,
    )

    private fun writeClipFile(contents: String): String {
        val temp = repository.tempClipFile()
        temp.writeText(contents)
        return repository.finalizeClipFile(temp, "m4a").name
    }
}
