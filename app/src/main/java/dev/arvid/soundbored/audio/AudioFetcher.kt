package dev.arvid.soundbored.audio

import dev.arvid.soundbored.yt.NewPipeDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Pulls the whole audio-only track down to a scratch file so cutting is instant and offline.
 *
 * YouTube's media servers throttle and then drop a single long-lived response, so the file is
 * fetched as a series of ranged requests. A dropped chunk is retried from the byte we actually
 * managed to write, which also makes flaky mobile connections survivable.
 */
object AudioFetcher {

    private const val CHUNK_BYTES = 2L * 1024 * 1024
    private const val MAX_RETRIES = 4

    suspend fun download(
        url: String,
        destination: File,
        expectedSize: Long = -1L,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        destination.delete()

        var total = expectedSize
        var position = 0L
        var failures = 0

        try {
            FileOutputStream(destination, true).buffered().use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val connection = open(url, position, position + CHUNK_BYTES - 1)
                    var chunkRead = 0L
                    var wholeBodyDelivered = false
                    try {
                        when (val code = connection.responseCode) {
                            HttpURLConnection.HTTP_PARTIAL -> {
                                total = totalFrom(connection.getHeaderField("Content-Range"), total)
                            }
                            HttpURLConnection.HTTP_OK -> {
                                // Server ignored the range: it is sending the file from byte 0.
                                if (position > 0L) throw IOException("Server does not support resuming")
                                total = connection.contentLengthLong
                                wholeBodyDelivered = true
                            }
                            416 -> break
                            else -> throw IOException("Audio download failed with HTTP $code")
                        }

                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                position += read
                                chunkRead += read
                                onProgress(position, total)
                            }
                        }
                        output.flush()
                        failures = 0
                    } catch (e: IOException) {
                        output.flush()
                        failures++
                        if (failures > MAX_RETRIES) throw e
                        delay(250L * failures)
                        continue
                    } finally {
                        connection.disconnect()
                    }

                    if (wholeBodyDelivered) break
                    if (chunkRead == 0L) break
                    if (total in 1..position) break
                }
            }

            if (total > 0L && destination.length() < total) {
                throw IOException("Audio download ended early (${destination.length()} of $total bytes)")
            }
            onProgress(destination.length(), if (total > 0L) total else destination.length())
        } catch (t: Throwable) {
            destination.delete()
            throw t
        }
    }

    private fun open(url: String, from: Long, to: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", NewPipeDownloader.USER_AGENT)
            setRequestProperty("Range", "bytes=$from-$to")
        }

    /** "bytes 0-2097151/3449447" -> 3449447 */
    private fun totalFrom(contentRange: String?, fallback: Long): Long {
        val declared = contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()
        return declared ?: fallback
    }
}
