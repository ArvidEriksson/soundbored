package dev.arvid.soundbored

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.arvid.soundbored.audio.AudioTrimmer
import dev.arvid.soundbored.audio.PcmDecoder
import dev.arvid.soundbored.audio.PreviewPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** The preview has to start where the selection starts, not wherever the player happened to be. */
@RunWith(AndroidJUnit4::class)
class PreviewPlayerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val player = PreviewPlayer()
    private val scratch = mutableListOf<File>()

    @After
    fun tearDown() {
        player.release()
        scratch.forEach { it.delete() }
    }

    @Test
    fun playbackStartsAtTheSelectionAndStopsAtItsEnd() = runBlocking {
        val source = aacSource()
        assertTrue("could not prepare the source", player.prepare(source))

        val positions = mutableListOf<Long>()
        withTimeout(8_000) {
            player.play(
                startMs = START_MS,
                endMs = END_MS,
                onPosition = { positions += it },
            )
        }

        assertTrue("no positions were reported", positions.isNotEmpty())
        assertTrue(
            "preview began at ${positions.first()}ms, expected about ${START_MS}ms",
            positions.first() >= START_MS - TOLERANCE_MS,
        )
        assertTrue(
            "preview ran past the selection, last position ${positions.last()}ms",
            positions.last() <= END_MS + TOLERANCE_MS,
        )
        assertTrue("preview never advanced", positions.last() - positions.first() > 500)
    }

    /** Replaying the same range has to rewind: a second run must not carry on from the end. */
    @Test
    fun replayingRewindsToTheStart() = runBlocking {
        player.prepare(aacSource())

        withTimeout(8_000) { player.play(START_MS, END_MS) }

        val second = mutableListOf<Long>()
        withTimeout(8_000) {
            player.play(startMs = START_MS, endMs = END_MS, onPosition = { second += it })
        }
        assertTrue(
            "replay began at ${second.first()}ms, expected about ${START_MS}ms",
            second.first() in (START_MS - TOLERANCE_MS)..(START_MS + TOLERANCE_MS),
        )
    }

    /**
     * The content check behind the design: the samples handed to the audio track really do
     * come from the requested offset. Each second of the source is a step louder than the last,
     * so the decoded peak says which second was decoded.
     */
    @Test
    fun decodingStartsAtTheRequestedOffset() = runBlocking {
        val stepped = steppedWav(seconds = 10)
        val m4a = File(context.cacheDir, "preview-stepped.m4a").also { scratch += it; it.delete() }
        AudioTrimmer.trim(stepped, m4a, startMs = 0L, endMs = 10_000L)

        val region = PcmDecoder.decodeRegion(m4a, startUs = 5_000_000L, endUs = 7_000_000L)
        var peak = 0f
        for (i in 0 until region.frameCount * region.channels) {
            val value = kotlin.math.abs(region.samples[i].toInt()) / 32768f
            if (value > peak) peak = value
        }

        // Seconds 5-6 sit at roughly 0.6; the top of the file is at 0.1.
        assertTrue("decoded peak was $peak, expected the level of seconds 5-6", peak > 0.45f)
        assertTrue("decoded peak was $peak, louder than the source ever gets", peak < 0.85f)
    }

    /** Amplitude steps up every second, so a decoded peak identifies the second it came from. */
    private fun steppedWav(seconds: Int, sampleRate: Int = 44_100): File {
        val frames = seconds * sampleRate
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
            val second = frame / sampleRate
            val amplitude = 0.1 + 0.1 * second
            val value = sin(2.0 * PI * 440.0 * frame / sampleRate) * amplitude * Short.MAX_VALUE
            buffer.putShort(value.toInt().toShort())
        }
        val file = File(context.cacheDir, "preview-stepped.wav").also { scratch += it }
        file.writeBytes(buffer.array())
        return file
    }

    /**
     * What the editor actually previews is compressed audio, where a seek needs a decoder
     * resync and so takes real time. A WAV seeks instantly and hides the problem.
     */
    private suspend fun aacSource(): File {
        val wav = sineWav(durationMs = 12_000L)
        val m4a = File(context.cacheDir, "preview-source.m4a").also { scratch += it }
        m4a.delete()
        AudioTrimmer.trim(wav, m4a, startMs = 0L, endMs = 12_000L)
        return m4a
    }

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
        val file = File(context.cacheDir, "preview-source.wav")
        file.writeBytes(buffer.array())
        scratch += file
        return file
    }

    private companion object {
        // 5 s in is exactly where MediaPlayer's own seek failed on a Pixel 7 Pro: it ignored
        // the seek and played from the top of the file.
        const val START_MS = 5_000L
        const val END_MS = 7_000L
        const val TOLERANCE_MS = 250L
    }
}
