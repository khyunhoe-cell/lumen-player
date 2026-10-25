package com.lumen.player.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.player.LumenApp
import com.lumen.player.data.db.PlaybackStateEntity
import com.lumen.player.data.db.ServerEntity
import com.lumen.player.data.media.LocalFolder
import com.lumen.player.data.media.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) { VIDEO("영상"), MUSIC("음악"), FOLDER("폴더") }

data class LibraryData(
    val videos: List<MediaEntry> = emptyList(),
    val music: List<MediaEntry> = emptyList(),
    val folders: List<LocalFolder> = emptyList(),
    val loading: Boolean = true,
    val permissionMissing: Boolean = false,
)

class LibraryViewModel : ViewModel() {
    private val container = LumenApp.container
    private val _data = MutableStateFlow(LibraryData())
    val data: StateFlow<LibraryData> = _data.asStateFlow()

    val tab = MutableStateFlow(LibraryTab.VIDEO)
    val query = MutableStateFlow("")

    val continueWatching: StateFlow<List<PlaybackStateEntity>> =
        container.db.playbackStates().observeContinue()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val servers: StateFlow<List<ServerEntity>> = container.db.servers().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** uri → 진행률(0..1). 목록에 이어보기 막대를 그릴 때 쓴다 */
    val progress: StateFlow<Map<String, Float>> = container.db.playbackStates().observeAll()
        .map { states ->
            states.filter { it.durationMs > 0 }.associate { it.uri to (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val result = runCatching {
                val videos = container.localMedia.videos()
                LibraryData(
                    videos = videos,
                    music = container.localMedia.audio(),
                    folders = videos.groupBy { it.sourceLabel }
                        .map { (name, items) -> LocalFolder(name, name, items) }
                        .sortedBy { it.name.lowercase() },
                    loading = false,
                )
            }
            _data.value = result.getOrElse { LibraryData(loading = false, permissionMissing = it is SecurityException) }
        }
    }
}
