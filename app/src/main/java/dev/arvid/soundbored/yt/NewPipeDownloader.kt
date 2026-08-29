package dev.arvid.soundbored.yt

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP layer NewPipeExtractor calls into. Plain HttpURLConnection keeps the
 * dependency list short; the extractor only makes a handful of requests per video.
 */
object NewPipeDownloader : Downloader() {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"

    override fun execute(request: Request): Response {
        val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = request.httpMethod()
            instanceFollowRedirects = true
        }

        var hasUserAgent = false
        request.headers().forEach { (key, values) ->
            // Let HttpURLConnection negotiate (and transparently undo) compression itself.
            if (key.equals("Accept-Encoding", ignoreCase = true)) return@forEach
            if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            values.forEach { value -> connection.addRequestProperty(key, value) }
        }
        if (!hasUserAgent) connection.setRequestProperty("User-Agent", USER_AGENT)

        try {
            val payload = request.dataToSend()
            if (payload != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }

            val code = connection.responseCode
            if (code == 429) {
                throw ReCaptchaException("reCAPTCHA challenge requested", request.url())
            }

            val body = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            val headers = HashMap<String, List<String>>()
            for ((key, values) in connection.headerFields) {
                if (key != null) headers[key] = values
            }

            return Response(code, connection.responseMessage, headers, body, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }
}
