package dev.arvid.soundbored.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.arvid.soundbored.audio.SoundPlayer
import dev.arvid.soundbored.data.Clip
import dev.arvid.soundbored.data.ClipRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class BoardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ClipRepository.get(application)
    private val soundPlayer = SoundPlayer()

    val boards = repository.boards
    val activeBoardId = repository.activeBoardId
    val playing = soundPlayer.playing

    /** Only the sounds on the board currently open. */
    val clips: StateFlow<List<Clip>> =
        combine(repository.clips, repository.activeBoardId) { clips, boardId ->
            clips.filter { it.boardId == boardId }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, repository.clipsOn(repository.activeBoardId.value))

    fun play(clip: Clip) = soundPlayer.play(clip.id, repository.fileFor(clip))

    fun stopAll() = soundPlayer.stopAll()

    fun rename(clip: Clip, name: String) = repository.rename(clip.id, name.trim().take(60))

    fun delete(clip: Clip) {
        soundPlayer.release(clip.id)
        repository.delete(clip)
    }

    fun selectBoard(id: String) {
        soundPlayer.stopAll()
        repository.selectBoard(id)
    }

    fun addBoard(name: String) = repository.addBoard(name)

    fun renameBoard(id: String, name: String) = repository.renameBoard(id, name)

    fun deleteBoard(id: String) {
        repository.clipsOn(id).forEach { soundPlayer.release(it.id) }
        repository.deleteBoard(id)
    }

    override fun onCleared() {
        soundPlayer.releaseAll()
        super.onCleared()
    }
}
