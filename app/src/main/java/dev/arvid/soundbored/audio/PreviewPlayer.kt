package dev.arvid.soundbored.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
 */
class PreviewPlayer {

    private var source: File? = null
    private var cached: PcmDecoder.Pcm? = null
    private var cachedRange: Pair<Long, Long>? = null
    private var track: AudioTrack? = null

    fun prepare(file: File): Boolean {
        stop()
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
    ) {
        val file = source ?: return
        val pcm = try {
            decoded(file, startMs, endMs)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decode the preview range", e)
            return
        }
        if (pcm.frameCount == 0) return

        withContext(Dispatchers.Default) {
            val audioTrack = openTrack(pcm) ?: return@withContext
            track = audioTrack
            val scratch = ShortArray(CHUNK_FRAMES * pcm.channels)
            try {
                audioTrack.play()
                var frame = 0
                while (frame < pcm.frameCount) {
                    coroutineContext.ensureActive()
                    val frames = minOf(CHUNK_FRAMES, pcm.frameCount - frame)
                    val positionMs = startMs + frame * 1000L / pcm.sampleRate

                    // Fades are not in the source file, so they are applied on the way out.
                    val gain = gainAt(positionMs)
                    val offset = frame * pcm.channels
                    val count = frames * pcm.channels
                    for (i in 0 until count) {
                        scratch[i] = (pcm.samples[offset + i] * gain).toInt().toShort()
                    }

                    val written = audioTrack.write(scratch, 0, count, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) break
                    frame += written / pcm.channels

                    val rendered = audioTrack.playbackHeadPosition.toLong()
                    onPosition(startMs + rendered * 1000L / pcm.sampleRate)
                }
                // Let whatever is still buffered finish before the track goes away.
                while (audioTrack.playbackHeadPosition < pcm.frameCount) {
                    coroutineContext.ensureActive()
                    onPosition(
                        startMs + audioTrack.playbackHeadPosition.toLong() * 1000L / pcm.sampleRate
                    )
                    kotlinx.coroutines.delay(POLL_MS)
                }
                onPosition(endMs)
            } finally {
                runCatching { audioTrack.pause() }
                runCatching { audioTrack.flush() }
                runCatching { audioTrack.release() }
                if (track === audioTrack) track = null
            }
        }
    }

    fun stop() {
        val audioTrack = track ?: return
        track = null
        runCatching { audioTrack.pause() }
        runCatching { audioTrack.flush() }
        runCatching { audioTrack.release() }
    }

    fun release() {
        stop()
        source = null
        cached = null
        cachedRange = null
    }

    /** Decoding a range takes a moment, so a repeated press of the same selection is instant. */
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
        const val POLL_MS = 20L
    }
}
