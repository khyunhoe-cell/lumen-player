package com.lumen.player.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import kotlinx.coroutines.delay

/** ExoPlayer 상태를 Compose가 읽을 수 있게 옮긴다. 위치는 200ms마다 갱신한다. */
@Stable
class PlayerUiState(val player: Player) {
    var isPlaying by mutableStateOf(player.isPlaying)
    var playbackState by mutableIntStateOf(player.playbackState)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(player.playbackParameters.speed)
    var videoSize by mutableStateOf(player.videoSize)
    var embeddedCues by mutableStateOf("")
    var tracks by mutableStateOf(player.currentTracks)
    var repeatMode by mutableIntStateOf(player.repeatMode)
    var shuffle by mutableStateOf(player.shuffleModeEnabled)
    var hasNext by mutableStateOf(player.hasNextMediaItem())
    var hasPrevious by mutableStateOf(player.hasPreviousMediaItem())
    var error by mutableStateOf<String?>(null)

    fun refreshPosition() {
        positionMs = player.currentPosition.coerceAtLeast(0)
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        bufferedMs = player.bufferedPosition
    }

    val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@PlayerUiState.isPlaying = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            playbackState = state
            refreshPosition()
        }

        override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
            speed = params.speed
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoSize = size
        }

        override fun onCues(cueGroup: CueGroup) {
            embeddedCues = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
        }

        override fun onTracksChanged(tracks: Tracks) {
            this@PlayerUiState.tracks = tracks
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            this@PlayerUiState.repeatMode = repeatMode
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            shuffle = enabled
        }

        override fun onEvents(player: Player, events: Player.Events) {
            hasNext = player.hasNextMediaItem()
            hasPrevious = player.hasPreviousMediaItem()
        }

        override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
            error = when (e.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "이 기기가 해독할 수 없는 영상 코덱입니다"
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                    "지원하지 않는 파일 형식입니다"
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "서버에 연결하지 못했습니다. 네트워크를 확인하세요"
                else -> e.message ?: e.errorCodeName
            }
        }
    }
}

@Composable
fun rememberPlayerUiState(player: Player): PlayerUiState {
    val state = remember(player) { PlayerUiState(player) }
    DisposableEffect(player) {
        player.addListener(state.listener)
        onDispose { player.removeListener(state.listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            state.refreshPosition()
            delay(200)
        }
    }
    return state
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
