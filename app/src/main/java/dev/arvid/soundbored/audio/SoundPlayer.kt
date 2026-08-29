package dev.arvid.soundbored.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Keeps a prepared MediaPlayer per clip so a tap starts audio immediately.
 * Tapping a clip that is already playing restarts it; different clips overlap freely.
 */
class SoundPlayer {

    private class Voice(val path: String, val player: MediaPlayer)

    private val players = LinkedHashMap<String, Voice>()
    private val _playing = MutableStateFlow<Set<String>>(emptySet())
    val playing: StateFlow<Set<String>> = _playing.asStateFlow()

    @Synchronized
    fun play(clipId: String, file: File) {
        // An edited clip keeps its id but gets a new file, so a stale voice has to go.
        val cached = players[clipId]?.takeIf { it.path == file.absolutePath }
        if (cached == null) release(clipId)
        val player = cached?.player ?: prepare(clipId, file) ?: return
        try {
            player.seekTo(0)
            player.start()
            _playing.value = _playing.value + clipId
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Player for $clipId went stale, rebuilding", e)
            release(clipId)
            prepare(clipId, file)?.let {
                it.start()
                _playing.value = _playing.value + clipId
            }
        }
    }

    @Synchronized
    fun stopAll() {
        players.values.forEach { voice ->
            runCatching { if (voice.player.isPlaying) { voice.player.pause(); voice.player.seekTo(0) } }
        }
        _playing.value = emptySet()
    }

    @Synchronized
    fun release(clipId: String) {
        players.remove(clipId)?.let { runCatching { it.player.release() } }
        _playing.value = _playing.value - clipId
    }

    @Synchronized
    fun releaseAll() {
        players.values.forEach { runCatching { it.player.release() } }
        players.clear()
        _playing.value = emptySet()
    }

    private fun prepare(clipId: String, file: File): MediaPlayer? = try {
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { _playing.value = _playing.value - clipId }
        }
        trimPool()
        players[clipId] = Voice(file.absolutePath, player)
        player
    } catch (e: Exception) {
        Log.e(TAG, "Could not prepare ${file.name}", e)
        null
    }

    /** Media codecs are a limited resource; keep only the most recently used handful. */
    private fun trimPool() {
        while (players.size >= MAX_PLAYERS) {
            val oldest = players.keys.firstOrNull() ?: return
            if (oldest in _playing.value) return
            players.remove(oldest)?.let { runCatching { it.player.release() } }
        }
    }

    private companion object {
        const val TAG = "SoundPlayer"
        const val MAX_PLAYERS = 12
    }
}
