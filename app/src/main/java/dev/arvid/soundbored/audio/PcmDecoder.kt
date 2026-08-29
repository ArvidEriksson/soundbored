package dev.arvid.soundbored.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Decodes a slice of an audio file to PCM. Shared by the trimmer (which re-encodes it) and the
 * editor preview (which plays it), so what you hear before saving is what gets saved.
 */
object PcmDecoder {

    private const val CODEC_TIMEOUT_US = 10_000L

    /** Decoded audio for the selected region. A 60 s cap keeps this comfortably in memory. */
    class Pcm(
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

    suspend fun decodeRegion(source: File, startUs: Long, endUs: Long): Pcm {
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


    fun audioTrackOf(extractor: MediaExtractor): Pair<Int, MediaFormat> {
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return i to candidate
            }
        }
        throw IOException("No audio track in the downloaded file")
    }
}
