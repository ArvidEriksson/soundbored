package dev.arvid.soundbored.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.arvid.soundbored.audio.AudioFetcher
import dev.arvid.soundbored.audio.AudioTrimmer
import dev.arvid.soundbored.audio.LocalAudioImporter
import dev.arvid.soundbored.audio.Waveform
import dev.arvid.soundbored.data.Clip
import dev.arvid.soundbored.data.ClipRepository
import dev.arvid.soundbored.data.SourceKind
import dev.arvid.soundbored.ui.theme.ClipPalette
import dev.arvid.soundbored.yt.ExtractionFailure
import dev.arvid.soundbored.yt.YoutubeAudio
import dev.arvid.soundbored.yt.YoutubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What the editor is working on, whichever kind of source it came from. */
data class LoadedAudio(
    val title: String,
    val subtitle: String,
    val durationMs: Long,
    val sourceUrl: String,
    val sourceKind: SourceKind,
)

class AddClipViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Stage {
        /** Pick where the sound comes from. */
        data object Choose : Stage
        data object Input : Stage
        data object Resolving : Stage
        data class Fetching(val title: String, val bytes: Long, val total: Long) : Stage
        data object Importing : Stage
        data class Ready(val audio: LoadedAudio) : Stage
        data object Saving : Stage
    }

    var url by mutableStateOf("")
        private set
    var stage by mutableStateOf<Stage>(Stage.Choose)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var name by mutableStateOf("")
        private set
    var peaks by mutableStateOf(FloatArray(0))
        private set
    var startMs by mutableLongStateOf(0L)
        private set
    var endMs by mutableLongStateOf(0L)
        private set
    var fadeInMs by mutableLongStateOf(0L)
        private set
    var fadeOutMs by mutableLongStateOf(0L)
        private set
    var playheadMs by mutableLongStateOf(-1L)
        private set
    var isPreviewing by mutableStateOf(false)
        private set

    /** The waveform keeps filling in after the editor opens; this drives the thin progress line. */
    var waveformProgress by mutableFloatStateOf(0f)
        private set

    private val repository = ClipRepository.get(application)

    /** Set when the editor was opened on an existing board button rather than a fresh link. */
    var editing by mutableStateOf<Clip?>(null)
        private set

    private var sourceFile: File? = null
    private var loadJob: Job? = null
    private var waveformJob: Job? = null
    private var player: MediaPlayer? = null
    private var previewJob: Job? = null

    fun onUrlChange(value: String) {
        url = value
        if (error != null) error = null
    }

    fun onNameChange(value: String) {
        name = value
    }

    /**
     * Re-opens an existing button for editing. The cut audio has no source attached any more,
     * so the original is fetched again and the old in/out points and fades are restored.
     */
    fun loadForEdit(clip: Clip) {
        editing = clip
        url = clip.sourceUrl
        when (clip.sourceKind) {
            SourceKind.YOUTUBE -> load()
            SourceKind.LOCAL_FILE -> importLocal(Uri.parse(clip.sourceUrl), persistAccess = false)
        }
    }

    fun chooseYoutube() {
        error = null
        stage = Stage.Input
    }

    /** Back out of the YouTube field to the source picker. */
    fun backToChoose() {
        error = null
        stage = Stage.Choose
    }

    fun load() {
        loadJob?.cancel()
        error = null
        loadJob = viewModelScope.launch {
            try {
                resetForLoad()

                stage = Stage.Resolving
                val audio = YoutubeSource.resolve(url)

                val file = File(
                    getApplication<Application>().cacheDir,
                    "source-${audio.videoId}.${audio.fileExtension}"
                )
                stage = Stage.Fetching(audio.title, 0L, audio.sizeBytes)
                AudioFetcher.download(audio.streamUrl, file, audio.sizeBytes) { read, total ->
                    stage = Stage.Fetching(audio.title, read, if (total > 0) total else audio.sizeBytes)
                }
                sourceFile = file

                openEditor(
                    file = file,
                    audio = LoadedAudio(
                        title = audio.title,
                        subtitle = listOf(audio.uploader, "${audio.bitrateKbps} kbps")
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        durationMs = audio.durationMs,
                        sourceUrl = audio.pageUrl,
                        sourceKind = SourceKind.YOUTUBE,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Loading $url failed", e)
                error = when (e) {
                    is ExtractionFailure -> e.message
                    else -> e.message ?: "Something went wrong."
                }
                stage = Stage.Input
            }
        }
    }

    /** Copies a picked file into our cache; from there it is edited exactly like a download. */
    fun importLocal(uri: Uri, persistAccess: Boolean = true) {
        loadJob?.cancel()
        error = null
        loadJob = viewModelScope.launch {
            try {
                resetForLoad()
                stage = Stage.Importing

                val file = File(getApplication<Application>().cacheDir, "source-import")
                val imported = LocalAudioImporter.import(
                    context = getApplication(),
                    uri = uri,
                    destination = file,
                    persistAccess = persistAccess,
                )
                sourceFile = imported.file

                openEditor(
                    file = imported.file,
                    audio = LoadedAudio(
                        title = imported.title,
                        subtitle = imported.subtitle,
                        durationMs = imported.durationMs,
                        sourceUrl = uri.toString(),
                        sourceKind = SourceKind.LOCAL_FILE,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Importing $uri failed", e)
                error = e.message ?: "Could not read that file."
                stage = Stage.Choose
            }
        }
    }

    private suspend fun resetForLoad() {
        stopPreview()
        releasePlayer()
        discardSource()
    }

    /** Everything after the audio is on disk is the same for both kinds of source. */
    private fun openEditor(file: File, audio: LoadedAudio) {
        val buckets = Waveform.bucketsFor(audio.durationMs)
        peaks = FloatArray(buckets)
        waveformProgress = 0f

        val edited = editing
        name = edited?.name ?: suggestLabel(audio.title)
        startMs = edited?.startMs?.coerceIn(0L, audio.durationMs) ?: 0L
        endMs = edited?.endMs?.coerceIn(startMs + MIN_CLIP_MS, audio.durationMs)
            ?: minOf(audio.durationMs, DEFAULT_CLIP_MS)
        fadeInMs = edited?.fadeInMs ?: 0L
        fadeOutMs = edited?.fadeOutMs ?: 0L
        playheadMs = -1L

        preparePlayer(file)
        stage = Stage.Ready(audio)
        startWaveform(file, buckets)
    }

    fun cancelLoad() {
        loadJob?.cancel()
        waveformJob?.cancel()
        stage = if (editing?.sourceKind == SourceKind.LOCAL_FILE) Stage.Choose else Stage.Input
    }

    private fun startWaveform(file: File, buckets: Int) {
        waveformJob?.cancel()
        waveformJob = viewModelScope.launch {
            try {
                peaks = Waveform.compute(
                    source = file,
                    buckets = buckets,
                    onProgress = { waveformProgress = it },
                    onPartial = { peaks = it },
                )
                waveformProgress = 1f
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A missing waveform only costs the drawing; trimming still works.
                Log.w(TAG, "Waveform failed", e)
                waveformProgress = 1f
            }
        }
    }

    fun updateRange(newStartMs: Long, newEndMs: Long) {
        val duration = durationMs()
        var start = newStartMs.coerceIn(0L, (duration - MIN_CLIP_MS).coerceAtLeast(0L))
        var end = newEndMs.coerceIn(start + MIN_CLIP_MS, duration)
        if (end - start > MAX_CLIP_MS) {
            if (start != startMs) start = end - MAX_CLIP_MS else end = start + MAX_CLIP_MS
        }
        startMs = start
        endMs = end
        clampFades()
        if (isPreviewing) restartPreview()
    }

    fun nudgeStart(deltaMs: Long) = updateRange(startMs + deltaMs, endMs)

    fun nudgeEnd(deltaMs: Long) = updateRange(startMs, endMs + deltaMs)

    fun moveRange(deltaMs: Long) {
        val duration = durationMs()
        val length = endMs - startMs
        val start = (startMs + deltaMs).coerceIn(0L, (duration - length).coerceAtLeast(0L))
        startMs = start
        endMs = start + length
        if (isPreviewing) restartPreview()
    }

    fun setFadeIn(ms: Long) {
        fadeInMs = ms.coerceAtLeast(0L)
        clampFades()
    }

    fun setFadeOut(ms: Long) {
        fadeOutMs = ms.coerceAtLeast(0L)
        clampFades()
    }

    fun nudgeFadeIn(deltaMs: Long) = setFadeIn(fadeInMs + deltaMs)

    fun nudgeFadeOut(deltaMs: Long) = setFadeOut(fadeOutMs + deltaMs)

    /** Fades may not overlap each other or outgrow the clip. */
    private fun clampFades() {
        val length = (endMs - startMs).coerceAtLeast(0L)
        fadeInMs = fadeInMs.coerceIn(0L, length)
        fadeOutMs = fadeOutMs.coerceIn(0L, length - fadeInMs)
    }

    /** Volume at [positionMs] within the clip, so the preview sounds like the saved file will. */
    fun gainAt(positionMs: Long): Float {
        val offset = positionMs - startMs
        val length = endMs - startMs
        if (offset < 0 || length <= 0) return 1f
        val fadeIn = if (fadeInMs > 0 && offset < fadeInMs) offset.toFloat() / fadeInMs else 1f
        val remaining = length - offset
        val fadeOut = if (fadeOutMs > 0 && remaining < fadeOutMs) {
            remaining.toFloat() / fadeOutMs
        } else {
            1f
        }
        return minOf(fadeIn, fadeOut).coerceIn(0f, 1f)
    }

    fun togglePreview() {
        if (isPreviewing) stopPreview() else startPreview()
    }

    fun save(onSaved: () -> Unit) {
        val ready = stage as? Stage.Ready ?: return
        val source = sourceFile ?: return
        stage = Stage.Saving
        viewModelScope.launch {
            try {
                stopPreview()
                waveformJob?.cancel()
                releasePlayer()
                val temp = repository.tempClipFile()
                val extension = AudioTrimmer.trim(source, temp, startMs, endMs, fadeInMs, fadeOutMs)
                val file = repository.finalizeClipFile(temp, extension)
                val edited = editing
                val clip = Clip(
                    id = edited?.id ?: UUID.randomUUID().toString(),
                    name = name.ifBlank { suggestLabel(ready.audio.title) }.take(60),
                    fileName = file.name,
                    boardId = edited?.boardId ?: repository.activeBoardId.value,
                    sourceUrl = ready.audio.sourceUrl,
                    sourceKind = ready.audio.sourceKind,
                    startMs = startMs,
                    endMs = endMs,
                    fadeInMs = fadeInMs,
                    fadeOutMs = fadeOutMs,
                    colorIndex = edited?.colorIndex
                        ?: repository.nextColorIndex(repository.activeBoardId.value, ClipPalette.size),
                    createdAt = edited?.createdAt ?: System.currentTimeMillis(),
                )
                if (edited != null) repository.update(clip) else repository.add(clip)
                discardSource()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Saving clip failed", e)
                error = e.message ?: "Could not cut that clip."
                stage = ready
            }
        }
    }

    /** Leaving the screen throws away the downloaded source; clips are self-contained. */
    fun discard() {
        loadJob?.cancel()
        waveformJob?.cancel()
        stopPreview()
        releasePlayer()
        viewModelScope.launch { discardSource() }
        stage = Stage.Choose
        peaks = FloatArray(0)
        error = null
        editing = null
        fadeInMs = 0L
        fadeOutMs = 0L
    }

    override fun onCleared() {
        loadJob?.cancel()
        waveformJob?.cancel()
        previewJob?.cancel()
        releasePlayer()
        sourceFile?.delete()
        super.onCleared()
    }

    /**
     * Video titles are long and soundboard buttons are small, so the suggested label drops
     * the "(Official Video)" style noise. The field stays editable either way.
     */
    private fun suggestLabel(title: String): String {
        val stripped = title
            .replace(Regex("[\\[(][^\\[\\]()]*[)\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', '|', '\u2013', ' ')
        val cleaned = stripped.ifBlank { title.trim() }
        return if (cleaned.length <= LABEL_LIMIT) cleaned else cleaned.take(LABEL_LIMIT).trimEnd()
    }

    private fun durationMs(): Long = (stage as? Stage.Ready)?.audio?.durationMs
        ?: (endMs.coerceAtLeast(MIN_CLIP_MS))

    private suspend fun discardSource() {
        val file = sourceFile ?: return
        sourceFile = null
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun preparePlayer(file: File) {
        releasePlayer()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        }.onFailure { Log.e(TAG, "Preview player failed", it) }.getOrNull()
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
    }

    private fun startPreview() {
        val mediaPlayer = player ?: return
        previewJob?.cancel()
        isPreviewing = true
        previewJob = viewModelScope.launch {
            runCatching {
                mediaPlayer.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                mediaPlayer.start()
            }.onFailure {
                isPreviewing = false
                return@launch
            }
            while (isPreviewing) {
                val position = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(endMs)
                playheadMs = position
                if (position >= endMs) break
                // The source file has no fades baked in, so ride the volume to preview them.
                val gain = gainAt(position)
                runCatching { mediaPlayer.setVolume(gain, gain) }
                delay(25)
            }
            runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
            runCatching { mediaPlayer.setVolume(1f, 1f) }
            isPreviewing = false
            playheadMs = -1L
        }
    }

    private fun restartPreview() {
        stopPreview()
        startPreview()
    }

    private fun stopPreview() {
        isPreviewing = false
        previewJob?.cancel()
        previewJob = null
        playheadMs = -1L
        runCatching { if (player?.isPlaying == true) player?.pause() }
        runCatching { player?.setVolume(1f, 1f) }
    }

    companion object {
        private const val TAG = "AddClipViewModel"
        const val MIN_CLIP_MS = 200L
        const val MAX_CLIP_MS = 60_000L
        const val DEFAULT_CLIP_MS = 5_000L
        private const val LABEL_LIMIT = 40
    }
}
