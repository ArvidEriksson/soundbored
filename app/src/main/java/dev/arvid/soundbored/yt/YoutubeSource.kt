package dev.arvid.soundbored.yt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Everything the app needs about one video's audio track. */
data class YoutubeAudio(
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val streamUrl: String,
    val fileExtension: String,
    val bitrateKbps: Int,
    val sizeBytes: Long,
    val pageUrl: String,
)

class ExtractionFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

object YoutubeSource {

    private val initialized = AtomicBoolean(false)

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloader, Localization("en", "US"), ContentCountry("US"))
        }
    }

    /** Accepts a bare link, a shared "look at this <url>" blob, or an 11-character video id. */
    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        val inText = Regex("https?://\\S+").find(trimmed)?.value?.trimEnd('.', ',', ')', '"')
        if (inText != null) return inText
        if (trimmed.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            return "https://www.youtube.com/watch?v=$trimmed"
        }
        if (trimmed.startsWith("youtu", ignoreCase = true) || trimmed.startsWith("www.", ignoreCase = true)) {
            return "https://$trimmed"
        }
        return trimmed
    }

    suspend fun resolve(input: String): YoutubeAudio = withContext(Dispatchers.IO) {
        ensureInitialized()
        val url = normalizeUrl(input)
        if (url.isEmpty()) throw ExtractionFailure("Paste a YouTube link first.")

        val info = try {
            val service = NewPipe.getServiceByUrl(url)
            StreamInfo.getInfo(service, url)
        } catch (e: ExtractionFailure) {
            throw e
        } catch (e: Exception) {
            throw ExtractionFailure(e.message ?: "Could not read that video.", e)
        }

        val stream = pickAudioStream(info.audioStreams)
            ?: throw ExtractionFailure(
                "No downloadable audio track for this video. Live streams and some " +
                    "age-restricted videos cannot be used."
            )

        val size = runCatching { stream.itagItem?.contentLength ?: -1L }.getOrDefault(-1L)

        YoutubeAudio(
            videoId = info.id,
            title = info.name.orEmpty().ifBlank { "Untitled" },
            uploader = info.uploaderName.orEmpty(),
            durationMs = info.duration * 1000L,
            streamUrl = stream.content,
            fileExtension = stream.format?.suffix ?: "m4a",
            bitrateKbps = stream.averageBitrate, // NewPipe reports YouTube itag bitrates in kbps
            sizeBytes = size,
            pageUrl = url,
        )
    }

    /**
     * Prefer a progressive M4A (AAC) track: it can be cut sample-accurately straight
     * into an MP4 container with no re-encoding, on every supported Android version.
     */
    private fun pickAudioStream(streams: List<AudioStream>?): AudioStream? {
        val usable = streams.orEmpty().filter { stream ->
            stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.isUrl &&
                !stream.content.isNullOrBlank()
        }
        if (usable.isEmpty()) return null

        // Skip dubbed/descriptive tracks when the original language track is present.
        val original = usable.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { usable }

        val m4a = original.filter { it.format == MediaFormat.M4A }
        return (m4a.ifEmpty { original }).maxByOrNull { it.averageBitrate }
    }
}
