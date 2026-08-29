package dev.arvid.soundbored

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.arvid.soundbored.audio.AudioFetcher
import dev.arvid.soundbored.audio.AudioTrimmer
import dev.arvid.soundbored.audio.Waveform
import dev.arvid.soundbored.yt.YoutubeAudio
import dev.arvid.soundbored.yt.YoutubeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the whole path a new sound takes: resolve a video, download its audio,
 * draw a waveform from it, cut an interval, and check the cut file really plays.
 * Needs network on the device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class ClipPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun youtubeLinkBecomesAPlayableClip() = runBlocking {
        val (audio, source) = downloadedSource()
        assertTrue("title was blank", audio.title.isNotBlank())
        assertTrue("duration was ${audio.durationMs}", audio.durationMs > 60_000)
        assertEquals("m4a", audio.fileExtension)
        assertTrue("downloaded ${source.length()} bytes", source.length() > 500_000)

        val buckets = Waveform.bucketsFor(audio.durationMs)
        val peaks = Waveform.compute(source, buckets)
        assertEquals(buckets, peaks.size)
        assertTrue("waveform was flat", peaks.count { it > 0.2f } > peaks.size / 4)

        val temp = File(context.cacheDir, "test-clip.tmp")
        temp.delete()
        val extension = AudioTrimmer.trim(source, temp, START_MS, END_MS)
        assertEquals("m4a", extension)
        assertTrue("clip file was empty", temp.length() > 10_000)
        assertDuration(temp, END_MS - START_MS)

        val player = MediaPlayer()
        try {
            player.setDataSource(temp.absolutePath)
            player.prepare()
            player.start()
            Thread.sleep(400)
            assertTrue("clip did not start playing", player.isPlaying)
            assertTrue("playback position stuck at 0", player.currentPosition > 0)
        } finally {
            player.release()
            temp.delete()
        }
    }

    /** The fade is rendered into the file, not applied at playback time, so it must be audible there. */
    @Test
    fun fadeOutIsBakedIntoTheClip() = runBlocking {
        val (_, source) = downloadedSource()

        val temp = File(context.cacheDir, "test-faded.tmp")
        temp.delete()
        try {
            val extension = AudioTrimmer.trim(
                source = source,
                destination = temp,
                startMs = START_MS,
                endMs = END_MS,
                fadeInMs = 0L,
                fadeOutMs = FADE_MS,
            )
            assertEquals("m4a", extension)
            assertDuration(temp, END_MS - START_MS)

            val peaks = Waveform.compute(temp, Waveform.bucketsFor(END_MS - START_MS))
            val tail = peaks.takeLast(peaks.size / 20).max()
            val body = peaks.take(peaks.size / 2).max()

            assertTrue("clip body was silent (peak $body)", body > 0.8f)
            assertTrue("tail did not fade out (peak $tail)", tail < 0.2f)
        } finally {
            temp.delete()
        }
    }

    private fun assertDuration(file: File, expectedMs: Long) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                .toLong()
            assertTrue(
                "cut duration was ${durationMs}ms, expected about ${expectedMs}ms",
                durationMs in (expectedMs - 250)..(expectedMs + 250),
            )
        } finally {
            retriever.release()
        }
    }

    /** Downloaded once and shared: the tests here differ in what they do with the audio, not how they get it. */
    private suspend fun downloadedSource(): Pair<YoutubeAudio, File> {
        shared?.let { return it }
        val audio = YoutubeSource.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val file = File(context.cacheDir, "test-source.${audio.fileExtension}")
        AudioFetcher.download(audio.streamUrl, file, audio.sizeBytes)
        return (audio to file).also { shared = it }
    }

    private companion object {
        const val START_MS = 43_000L
        const val END_MS = 48_000L
        const val FADE_MS = 2_000L

        var shared: Pair<YoutubeAudio, File>? = null
    }
}
