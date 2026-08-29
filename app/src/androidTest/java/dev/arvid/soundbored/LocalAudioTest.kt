package dev.arvid.soundbored

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.arvid.soundbored.audio.AudioTrimmer
import dev.arvid.soundbored.audio.Waveform
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Imported files are whatever the user has lying around. A WAV stands in for "not AAC":
 * MediaMuxer cannot hold it, so the trimmer has to fall back to re-encoding.
 */
@RunWith(AndroidJUnit4::class)
class LocalAudioTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = mutableListOf<File>()

    @After
    fun tearDown() {
        scratch.forEach { it.delete() }
    }

    @Test
    fun aWavFileIsReEncodedRatherThanRemuxed() = runBlocking {
        val wav = sineWav(durationMs = 4_000L)
        val out = scratchFile("local-cut.tmp")

        val extension = AudioTrimmer.trim(wav, out, startMs = 1_000L, endMs = 3_000L)

        assertEquals("m4a", extension)
        assertTrue("nothing was written", out.length() > 2_000)
        assertDuration(out, 2_000L)
        assertPlays(out)
    }

    @Test
    fun aWavFileCanBeCutWithAFade() = runBlocking {
        val wav = sineWav(durationMs = 4_000L)
        val out = scratchFile("local-faded.tmp")

        AudioTrimmer.trim(wav, out, startMs = 1_000L, endMs = 3_000L, fadeOutMs = 800L)

        assertDuration(out, 2_000L)
        val peaks = Waveform.compute(out, Waveform.bucketsFor(2_000L))
        assertTrue("body was silent", peaks.take(peaks.size / 2).max() > 0.8f)
        assertTrue("tail did not fade", peaks.takeLast(peaks.size / 20).max() < 0.2f)
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

    private fun assertPlays(file: File) {
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            Thread.sleep(300)
            assertTrue("cut file did not play", player.isPlaying)
        } finally {
            player.release()
        }
    }

    private fun scratchFile(name: String): File =
        File(context.cacheDir, name).also { it.delete(); scratch += it }

    /** A plain 16-bit mono PCM WAV: no encoder involved, so the test controls the audio exactly. */
    private fun sineWav(durationMs: Long, sampleRate: Int = 44_100): File {
        val frames = (durationMs * sampleRate / 1000L).toInt()
        val dataSize = frames * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (frame in 0 until frames) {
            val value = sin(2.0 * PI * 440.0 * frame / sampleRate) * 0.6 * Short.MAX_VALUE
            buffer.putShort(value.toInt().toShort())
        }
        return scratchFile("local-source.wav").also { it.writeBytes(buffer.array()) }
    }
}
