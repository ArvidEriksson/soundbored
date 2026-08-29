package dev.arvid.soundbored.audio

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Pulls an audio file the user picked into our own cache, and reads what it is. */
object LocalAudioImporter {

    class ImportFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Imported(
        val file: File,
        val title: String,
        val subtitle: String,
        val durationMs: Long,
    )

    /**
     * @param persistAccess granted the first time the user picks a file, so that reopening the
     *   clip for editing later can read the same file again without another picker round trip.
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        destination: File,
        persistAccess: Boolean,
    ): Imported = withContext(Dispatchers.IO) {
        if (persistAccess) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        val displayName = displayNameOf(context.contentResolver, uri)
        destination.parentFile?.mkdirs()
        destination.delete()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw IOException("Could not open that file")
        } catch (e: SecurityException) {
            throw ImportFailure(
                "This app no longer has permission to read that file. Pick it again.", e
            )
        } catch (e: Exception) {
            destination.delete()
            throw ImportFailure(e.message ?: "Could not read that file.", e)
        }
        if (destination.length() == 0L) {
            destination.delete()
            throw ImportFailure("That file is empty.")
        }

        val retriever = MediaMetadataRetriever()
        val metadata = try {
            retriever.setDataSource(destination.absolutePath)
            Triple(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            )
        } catch (e: Exception) {
            destination.delete()
            throw ImportFailure("That file does not look like audio this device can play.", e)
        } finally {
            runCatching { retriever.release() }
        }

        val durationMs = metadata.first ?: 0L
        if (durationMs <= 0L) {
            destination.delete()
            throw ImportFailure("Could not work out how long that file is.")
        }

        Imported(
            file = destination,
            title = metadata.second?.takeIf { it.isNotBlank() }
                ?: displayName?.substringBeforeLast('.')
                ?: "Audio file",
            subtitle = listOfNotNull(metadata.third?.takeIf { it.isNotBlank() }, displayName)
                .firstOrNull()
                .orEmpty(),
            durationMs = durationMs,
        )
    }

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
