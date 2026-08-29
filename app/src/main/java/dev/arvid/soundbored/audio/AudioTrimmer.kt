package dev.arvid.soundbored.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Cuts [startMs, endMs) out of an audio file.
 *
 * With no fades this copies encoded frames straight into a new container — no decode/encode
 * round trip, so it is fast and lossless. Fades need the actual samples, so those clips take
 * the decode → gain → re-encode path instead.
 */
object AudioTrimmer {

    private const val DEFAULT_BUFFER_SIZE = 512 * 1024
    private const val CODEC_TIMEOUT_US = 10_000L

    /** @return the container extension actually written ("m4a" or "ogg"). */
    suspend fun trim(
        source: File,
        destination: File,
        startMs: Long,
        endMs: Long,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
    ): String = withContext(Dispatchers.IO) {
        try {
            // Imported files can be anything (MP3, FLAC, WAV…); only formats MediaMuxer can
            // hold are eligible for the lossless shortcut, and only without fades.
            if (fadeInMs <= 0L && fadeOutMs <= 0L && canRemux(audioMimeOf(source))) {
                copyFrames(source, destination, startMs, endMs)
            } else {
                renderWithFades(source, destination, startMs, endMs, fadeInMs, fadeOutMs)
            }
        } catch (t: Throwable) {
            destination.delete()
            throw t
        }
    }

    // ---------------------------------------------------------------- lossless path

    private suspend fun copyFrames(
        source: File,
        destination: File,
        startMs: Long,
        endMs: Long,
    ): String {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val (trackIndex, trackFormat) = PcmDecoder.audioTrackOf(extractor)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()

            val container = containerFor(mime)
            muxer = MediaMuxer(destination.absolutePath, container)
            val outputTrack = muxer.addTrack(trackFormat)
            muxer.start()

            extractor.selectTrack(trackIndex)
            // Land on or before the requested start, then walk forward to it: AAC frames
            // decode independently, so the first frame at/after startMs is a clean cut.
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val capacity = runCatching { trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(0)
                .coerceAtLeast(DEFAULT_BUFFER_SIZE)
            val buffer = ByteBuffer.allocate(capacity)
            val bufferInfo = MediaCodec.BufferInfo()
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L

            var firstSampleUs = -1L
            var wrote = false
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleUs = extractor.sampleTime
                if (sampleUs >= endUs) break
                if (sampleUs < startUs && firstSampleUs < 0) {
                    if (!extractor.advance()) break
                    continue
                }
                if (firstSampleUs < 0) firstSampleUs = sampleUs

                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = sampleUs - firstSampleUs
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                wrote = true

                if (!extractor.advance()) break
            }
            if (!wrote) throw IOException("Nothing to cut in that range")

            return if (container == MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG) "ogg" else "m4a"
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------------- fade path

    private suspend fun renderWithFades(
        source: File,
        destination: File,
        startMs: Long,
        endMs: Long,
        fadeInMs: Long,
        fadeOutMs: Long,
    ): String {
        val pcm = PcmDecoder.decodeRegion(source, startMs * 1000L, endMs * 1000L)
        if (pcm.frameCount == 0) throw IOException("Nothing to cut in that range")
        pcm.applyFades(fadeInMs, fadeOutMs)
        encodeAac(pcm, destination)
        return "m4a"
    }

    private suspend fun encodeAac(pcm: PcmDecoder.Pcm, destination: File) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            pcm.sampleRate,
            pcm.channels,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, (pcm.channels * 96_000).coerceAtMost(192_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var track = -1
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val totalShorts = pcm.frameCount * pcm.channels
            var fed = 0
            var framesFed = 0L
            var inputDone = false
            var done = false

            while (!done) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index >= 0) {
                        val input = encoder.getInputBuffer(index)!!
                        input.clear()
                        // Whole frames only, or the encoder drifts out of channel alignment.
                        val bytesPerFrame = pcm.channels * 2
                        val frames = minOf(
                            input.capacity() / bytesPerFrame,
                            (totalShorts - fed) / pcm.channels,
                        )
                        if (frames <= 0) {
                            encoder.queueInputBuffer(
                                index, 0, 0,
                                framesFed * 1_000_000L / pcm.sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val shorts = frames * pcm.channels
                            input.order(ByteOrder.nativeOrder())
                                .asShortBuffer()
                                .put(pcm.samples, fed, shorts)
                            encoder.queueInputBuffer(
                                index, 0, shorts * 2,
                                framesFed * 1_000_000L / pcm.sampleRate,
                                0,
                            )
                            fed += shorts
                            framesFed += frames
                        }
                    }
                }

                when (val index = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (index >= 0) {
                            val output = encoder.getOutputBuffer(index)
                            val isConfig =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (output != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                                muxer.writeSampleData(track, output, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(index, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                done = true
                            }
                        }
                    }
                }
            }
            if (!muxerStarted) throw IOException("Encoder produced no audio")
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    // ----------------------------------------------------------------------- shared

    private fun audioMimeOf(source: File): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            PcmDecoder.audioTrackOf(extractor).second.getString(MediaFormat.KEY_MIME).orEmpty()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun canRemux(mime: String): Boolean = when {
        mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) -> true
        mime.contains("opus", ignoreCase = true) -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        else -> false
    }

    private fun containerFor(mime: String): Int = when {
        mime.contains("opus", ignoreCase = true) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            } else {
                throw IOException("This video only offers Opus audio, which needs Android 10 or newer")
            }
        }
        else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    }
}
