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
            val (trackIndex, trackFormat) = audioTrackOf(extractor)
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
        val pcm = decodeRegion(source, startMs * 1000L, endMs * 1000L)
        if (pcm.frameCount == 0) throw IOException("Nothing to cut in that range")
        pcm.applyFades(fadeInMs, fadeOutMs)
        encodeAac(pcm, destination)
        return "m4a"
    }

    /** Decoded audio for the selected region. A 60 s cap keeps this comfortably in memory. */
    private class Pcm(
        val samples: ShortArray,
        val frameCount: Int,
        val sampleRate: Int,
        val channels: Int,
    ) {
        /** Linear ramps, so what you hear matches the envelope drawn in the editor. */
        fun applyFades(fadeInMs: Long, fadeOutMs: Long) {
            val fadeIn = (fadeInMs * sampleRate / 1000L).toInt().coerceIn(0, frameCount)
            val fadeOut = (fadeOutMs * sampleRate / 1000L).toInt().coerceIn(0, frameCount - fadeIn)

            for (frame in 0 until fadeIn) {
                scale(frame, (frame + 1).toFloat() / fadeIn)
            }
            for (i in 0 until fadeOut) {
                val frame = frameCount - fadeOut + i
                scale(frame, 1f - (i + 1).toFloat() / fadeOut)
            }
        }

        private fun scale(frame: Int, gain: Float) {
            val base = frame * channels
            for (channel in 0 until channels) {
                samples[base + channel] = (samples[base + channel] * gain).toInt().toShort()
            }
        }
    }

    private suspend fun decodeRegion(source: File, startUs: Long, endUs: Long): Pcm {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val (trackIndex, trackFormat) = audioTrackOf(extractor)
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatOutput = false
            // One extra second of slack: decoders hand back whole frames either side.
            var out = ShortArray(capacityFor(endUs - startUs, sampleRate, channels))
            var size = 0

            decoder = MediaCodec.createDecoderByType(
                trackFormat.getString(MediaFormat.KEY_MIME)!!
            )
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false

            while (!done) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index)!!
                        val read = extractor.readSampleData(input, 0)
                        if (read < 0) {
                            decoder.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatOutput = runCatching {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        out = ShortArray(capacityFor(endUs - startUs, sampleRate, channels))
                        size = 0
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (index >= 0) {
                            val buffer = decoder.getOutputBuffer(index)
                            if (buffer != null && bufferInfo.size > 0) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                size += appendRegion(
                                    buffer = buffer,
                                    floatOutput = floatOutput,
                                    bufferStartUs = bufferInfo.presentationTimeUs,
                                    startUs = startUs,
                                    endUs = endUs,
                                    sampleRate = sampleRate,
                                    channels = channels,
                                    out = out,
                                    outOffset = size,
                                )
                            }
                            val bufferEndUs = bufferInfo.presentationTimeUs +
                                framesIn(bufferInfo.size, channels, floatOutput) *
                                1_000_000L / sampleRate
                            decoder.releaseOutputBuffer(index, false)
                            if (bufferEndUs >= endUs ||
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            ) {
                                done = true
                            }
                        }
                    }
                }
            }

            return Pcm(out, size / channels, sampleRate, channels)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Copies the part of one decoded buffer that falls inside [startUs, endUs). */
    private fun appendRegion(
        buffer: ByteBuffer,
        floatOutput: Boolean,
        bufferStartUs: Long,
        startUs: Long,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
        out: ShortArray,
        outOffset: Int,
    ): Int {
        val ordered = buffer.order(ByteOrder.nativeOrder())
        val totalFrames = if (floatOutput) {
            ordered.asFloatBuffer().limit() / channels
        } else {
            ordered.asShortBuffer().limit() / channels
        }
        if (totalFrames == 0) return 0

        val firstFrame = if (bufferStartUs >= startUs) {
            0
        } else {
            (((startUs - bufferStartUs) * sampleRate + 999_999L) / 1_000_000L).toInt()
        }
        val lastFrame = (((endUs - bufferStartUs) * sampleRate) / 1_000_000L).toInt()
            .coerceAtMost(totalFrames)
        if (lastFrame <= firstFrame || firstFrame >= totalFrames) return 0

        val frames = lastFrame - firstFrame
        val count = (frames * channels).coerceAtMost(out.size - outOffset)
        if (count <= 0) return 0

        if (floatOutput) {
            val floats = ordered.asFloatBuffer()
            for (i in 0 until count) {
                val value = floats.get(firstFrame * channels + i)
                out[outOffset + i] = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        } else {
            val shorts = ordered.asShortBuffer()
            shorts.position(firstFrame * channels)
            shorts.get(out, outOffset, count)
        }
        return count
    }

    private fun framesIn(byteSize: Int, channels: Int, floatOutput: Boolean): Long {
        val bytesPerFrame = channels * if (floatOutput) 4 else 2
        return if (bytesPerFrame == 0) 0L else (byteSize / bytesPerFrame).toLong()
    }

    private fun capacityFor(durationUs: Long, sampleRate: Int, channels: Int): Int =
        ((durationUs / 1_000_000.0 + 1.0) * sampleRate).toInt() * channels

    private suspend fun encodeAac(pcm: Pcm, destination: File) {
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
            audioTrackOf(extractor).second.getString(MediaFormat.KEY_MIME).orEmpty()
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun canRemux(mime: String): Boolean = when {
        mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) -> true
        mime.contains("opus", ignoreCase = true) -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        else -> false
    }

    private fun audioTrackOf(extractor: MediaExtractor): Pair<Int, MediaFormat> {
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return i to candidate
            }
        }
        throw IOException("No audio track in the downloaded file")
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
