package dev.arvid.soundbored.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Plays the selected range for the editor's preview button.
 *
 * It decodes the range and pushes the samples to an AudioTrack rather than seeking a MediaPlayer:
 * MediaPlayer's seek is only approximate — on a Pixel 7 Pro a seek to 5 s in a YouTube M4A is
 * ignored outright and playback starts from the beginning of the file. Decoding gives a
 * sample-exact start, and it is the same decode the trimmer uses, so the preview is literally
 * what will be saved.
 *
 * Every part of that — decode included — runs off the main thread, and the audio track is owned
 * by the playing coroutine alone: stopping cancels that coroutine, which then releases it.
 */
class PreviewPlayer {

    private var source: File? = null
    private var cached: PcmDecoder.Pcm? = null
    private var cachedRange: Pair<Long, Long>? = null

    @Volatile
    private var track: AudioTrack? = null

    fun prepare(file: File): Boolean {
        pause()
        source = file
        cached = null
        cachedRange = null
        return file.exists()
    }

    /** Suspends until the range has played out; cancelling the caller stops playback. */
    suspend fun play(
        startMs: Long,
        endMs: Long,
        gainAt: (positionMs: Long) -> Float = { 1f },
        onPosition: (positionMs: Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val file = source ?: return@withContext
        val pcm = try {
            decoded(file, startMs, endMs)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decode the preview range", e)
            return@withContext
        }
        if (pcm.frameCount == 0) return@withContext

        val audioTrack = openTrack(pcm) ?: return@withContext
        track = audioTrack
        val scratch = ShortArray(CHUNK_FRAMES * pcm.channels)
        try {
            audioTrack.play()
            val total = pcm.frameCount * pcm.channels
            var sample = 0
            while (sample < total && coroutineContext.isActive) {
                val count = minOf(scratch.size, total - sample)
                val positionMs = startMs + (sample / pcm.channels) * 1000L / pcm.sampleRate

                // Fades are not in the source file, so they are applied on the way out.
                val gain = gainAt(positionMs)
                for (i in 0 until count) {
                    scratch[i] = (pcm.samples[sample + i] * gain).toInt().toShort()
                }

                // Non-blocking, so cancelling the preview is felt within a few milliseconds
                // rather than after however much audio is already queued.
                val written = audioTrack.write(scratch, 0, count, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) break
                if (written == 0) {
                    delay(POLL_MS)
                    continue
                }
                sample += written
                onPosition(startMs + renderedMs(audioTrack, pcm))
            }

            // Let what is already queued finish, but never wait longer than it could take.
            val queuedFrames = sample / pcm.channels
            val deadline = SystemClock.elapsedRealtime() + (endMs - startMs) + DRAIN_GRACE_MS
            while (coroutineContext.isActive &&
                audioTrack.playbackHeadPosition < queuedFrames &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                onPosition(startMs + renderedMs(audioTrack, pcm))
                delay(POLL_MS)
            }
            if (coroutineContext.isActive) onPosition(endMs)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Audio track went away mid-preview", e)
        } finally {
            track = null
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
        }
    }

    /** Silences playback straight away; the playing coroutine still owns and releases the track. */
    fun pause() {
        runCatching { track?.pause() }
    }

    fun release() {
        pause()
        source = null
        cached = null
        cachedRange = null
    }

    private fun renderedMs(audioTrack: AudioTrack, pcm: PcmDecoder.Pcm): Long =
        audioTrack.playbackHeadPosition.toLong() * 1000L / pcm.sampleRate

    /** Decoding takes a moment, so pressing play again on the same selection is instant. */
    private suspend fun decoded(file: File, startMs: Long, endMs: Long): PcmDecoder.Pcm {
        cached?.let { if (cachedRange == startMs to endMs) return it }
        val pcm = PcmDecoder.decodeRegion(file, startMs * 1000L, endMs * 1000L)
        cached = pcm
        cachedRange = startMs to endMs
        return pcm
    }

    private fun openTrack(pcm: PcmDecoder.Pcm): AudioTrack? = runCatching {
        val channelMask = if (pcm.channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minimum = AudioTrack.getMinBufferSize(
            pcm.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(max(minimum, CHUNK_FRAMES * pcm.channels * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.onFailure { Log.e(TAG, "Could not open an audio track", it) }.getOrNull()

    private companion object {
        const val TAG = "PreviewPlayer"
        const val CHUNK_FRAMES = 1024
        const val POLL_MS = 5L
        const val DRAIN_GRACE_MS = 500L
    }
}
