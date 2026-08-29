package dev.arvid.soundbored.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Decodes an audio file to PCM once and reduces it to per-bucket peaks for drawing. */
object Waveform {

    private const val MS_PER_BUCKET = 25L
    private const val MIN_BUCKETS = 400
    private const val MAX_BUCKETS = 8000
    private const val PARTIAL_INTERVAL_MS = 200L

    /**
     * ~25 ms per bucket: fine enough that the zoomed-in view still shows real detail,
     * and only a few hundred kB even for a long video.
     */
    fun bucketsFor(durationMs: Long): Int =
        (durationMs / MS_PER_BUCKET).toInt().coerceIn(MIN_BUCKETS, MAX_BUCKETS)

    /**
     * @param onPartial receives the waveform-so-far every few hundred milliseconds, so the
     *   editor can draw itself while the rest of the track is still decoding.
     */
    suspend fun compute(
        source: File,
        buckets: Int,
        onProgress: (Float) -> Unit = {},
        onPartial: (FloatArray) -> Unit = {},
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val peaks = FloatArray(buckets)
        try {
            extractor.setDataSource(source.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            val trackFormat = format ?: throw IOException("No audio track")
            val durationUs = runCatching { trackFormat.getLong(MediaFormat.KEY_DURATION) }
                .getOrDefault(0L)
                .coerceAtLeast(1L)

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var lastPartial = 0L
            var inputDone = false
            var outputDone = false
            var floatOutput = false

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000L)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        floatOutput = runCatching {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (index >= 0) {
                            val output = codec.getOutputBuffer(index)
                            if (output != null && bufferInfo.size > 0) {
                                output.position(bufferInfo.offset)
                                output.limit(bufferInfo.offset + bufferInfo.size)
                                val bucket = ((bufferInfo.presentationTimeUs.toDouble() / durationUs) *
                                    buckets).toInt().coerceIn(0, buckets - 1)
                                peaks[bucket] = maxOf(peaks[bucket], peakOf(output, floatOutput))
                                onProgress(
                                    (bufferInfo.presentationTimeUs.toFloat() / durationUs)
                                        .coerceIn(0f, 1f)
                                )
                                val now = System.currentTimeMillis()
                                if (now - lastPartial > PARTIAL_INTERVAL_MS) {
                                    lastPartial = now
                                    onPartial(normalize(fillGaps(peaks.copyOf())))
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        onProgress(1f)
        normalize(fillGaps(peaks))
    }

    /** Peak of a PCM buffer; every 4th frame is plenty for a 720-bucket drawing. */
    private fun peakOf(buffer: java.nio.ByteBuffer, floatOutput: Boolean): Float {
        val ordered = buffer.order(ByteOrder.nativeOrder())
        var peak = 0f
        if (floatOutput) {
            val samples = ordered.asFloatBuffer()
            var i = 0
            while (i < samples.limit()) {
                peak = maxOf(peak, abs(samples.get(i)))
                i += 4
            }
        } else {
            val samples = ordered.asShortBuffer()
            var i = 0
            while (i < samples.limit()) {
                peak = maxOf(peak, abs(samples.get(i).toInt()) / 32768f)
                i += 4
            }
        }
        return peak
    }

    /** Short clips can leave buckets untouched between decoded frames; bridge them. */
    private fun fillGaps(peaks: FloatArray): FloatArray {
        var lastSeen = -1
        for (i in peaks.indices) {
            if (peaks[i] > 0f) {
                if (lastSeen >= 0 && i - lastSeen > 1) {
                    val from = peaks[lastSeen]
                    val to = peaks[i]
                    for (j in lastSeen + 1 until i) {
                        val t = (j - lastSeen).toFloat() / (i - lastSeen)
                        peaks[j] = from + (to - from) * t
                    }
                }
                lastSeen = i
            }
        }
        return peaks
    }

    private fun normalize(peaks: FloatArray): FloatArray {
        val max = peaks.maxOrNull() ?: 0f
        if (max <= 0.001f) return peaks
        for (i in peaks.indices) peaks[i] = (peaks[i] / max).coerceIn(0f, 1f)
        return peaks
    }
}
