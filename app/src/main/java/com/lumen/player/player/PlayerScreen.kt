package com.lumen.player.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.lumen.player.LumenApp
import com.lumen.player.data.settings.SubtitlePosition
import com.lumen.player.subtitle.ui.SubtitleOverlay
import com.lumen.player.subtitle.ui.reservedHeight
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class PlayerSheet { NONE, SPEED, SUBTITLE, QUEUE, AUDIO }

private enum class ResizeMode(val label: String, val value: Int) {
    FIT("화면에 맞춤", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("꽉 채우기(잘림)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("늘이기", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

@UnstableApi
@Composable
fun PlayerScreen(
    service: PlaybackService,
    inPip: Boolean,
    onClose: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val player = service.player
    val state = rememberPlayerUiState(player)
    val container = LumenApp.container
    val settings by container.settings.settings.collectAsState()
    val entry by service.current.collectAsState()
    val external by service.externalSubtitle.collectAsState()
    val delayMs by service.subtitleDelayMs.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(PlayerSheet.NONE) }
    var resize by remember { mutableStateOf(ResizeMode.FIT) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var subtitleDragMode by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun touch() {
        lastInteraction = System.currentTimeMillis()
    }

    // 재생 중이면 3.5초 뒤 컨트롤을 숨긴다
    LaunchedEffect(controlsVisible, state.isPlaying, lastInteraction, sheet) {
        if (controlsVisible && state.isPlaying && sheet == PlayerSheet.NONE) {
            delay(3_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(900)
            feedback = null
        }
    }
    // 외부 자막은 50ms 간격으로 따로 맞춘다(위치 갱신 200ms로는 늦게 보인다)
    var subtitleClock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(external) {
        while (external != null) {
            subtitleClock = player.currentPosition
            delay(50)
        }
    }
    BackHandler(enabled = sheet != PlayerSheet.NONE) { sheet = PlayerSheet.NONE }

    val isVideo = entry?.isVideo != false
    val subtitleText = external?.at(subtitleClock - delayMs)?.joinToString("\n") { it.text } ?: state.embeddedCues
    // 자막을 끄는 동안에는 로컬 값만 바꾸고, 손을 떼면 저장한다
    var marginOverride by remember { mutableStateOf<Float?>(null) }
    val style = settings.subtitle.let { s -> marginOverride?.let { s.copy(marginDp = it) } ?: s }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(locked, inPip) {
                if (inPip) return@pointerInput
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        touch()
                    },
                    onDoubleTap = { offset ->
                        if (locked) return@detectTapGestures
                        val step = settings.seekStepSec * 1000L
                        when {
                            offset.x < size.width / 3f -> {
                                player.seekTo((player.currentPosition - step).coerceAtLeast(0))
                                feedback = "-${settings.seekStepSec}초"
                            }
                            offset.x > size.width * 2 / 3f -> {
                                player.seekTo(player.currentPosition + step)
                                feedback = "+${settings.seekStepSec}초"
                            }
                            else -> if (player.isPlaying) player.pause() else player.play()
                        }
                    },
                )
            }
            .pointerInput(locked, inPip) {
                if (locked || inPip) return@pointerInput
                var mode = DragMode.NONE
                var startX = 0f
                var totalX = 0f
                var totalY = 0f
                var basePos = 0L
                var target = 0L
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                var baseVol = 0
                var baseBright = 0.5f
                detectDragGestures(
                    onDragStart = { offset ->
                        mode = DragMode.NONE
                        startX = offset.x
                        totalX = 0f
                        totalY = 0f
                        basePos = player.currentPosition
                        baseVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val attrs = (context as Activity).window.attributes
                        baseBright = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
                    },
                    onDragEnd = {
                        if (mode == DragMode.SEEK) player.seekTo(target)
                        mode = DragMode.NONE
                        scrubMs = null
                    },
                    onDragCancel = {
                        mode = DragMode.NONE
                        scrubMs = null
                    },
                ) { change, drag ->
                    totalX += drag.x
                    totalY += drag.y
                    if (mode == DragMode.NONE) {
                        mode = when {
                            abs(totalX) > abs(totalY) -> DragMode.SEEK
                            startX < size.width / 2f -> DragMode.BRIGHTNESS
                            else -> DragMode.VOLUME
                        }
                    }
                    change.consume()
                    when (mode) {
                        DragMode.SEEK -> {
                            // 화면 너비만큼 끌면 90초 이동
                            val delta = (totalX / size.width * 90_000).toLong()
                            target = (basePos + delta).coerceIn(0, state.durationMs.coerceAtLeast(0))
                            scrubMs = target
                            val sign = if (delta >= 0) "+" else "-"
                            feedback = "${formatDuration(target)}  ($sign${formatDuration(abs(delta))})"
                        }
                        DragMode.BRIGHTNESS -> {
                            val v = (baseBright - totalY / size.height).coerceIn(0.01f, 1f)
                            val window = (context as Activity).window
                            window.attributes = window.attributes.apply { screenBrightness = v }
                            feedback = "밝기 ${(v * 100).toInt()}%"
                        }
                        DragMode.VOLUME -> {
                            val v = (baseVol - totalY / size.height * maxVol * 1.5f).toInt().coerceIn(0, maxVol)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            feedback = "볼륨 $v / $maxVol"
                        }
                        DragMode.NONE -> Unit
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val reserve = if (isVideo && !inPip) with(density) { style.reservedHeight().toPx() } else 0f
        val area = when (style.position) {
            SubtitlePosition.BELOW_VIDEO -> Rect(0f, 0f, w, h - reserve)
            SubtitlePosition.ABOVE_VIDEO -> Rect(0f, reserve, w, h)
            else -> Rect(0f, 0f, w, h)
        }

        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        subtitleView?.visibility = android.view.View.GONE
                        keepScreenOn = true
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = service.player
                    }
                },
                update = { it.resizeMode = resize.value },
                onRelease = { it.player = null },
                modifier = Modifier
                    .offset { IntOffset(area.left.toInt(), area.top.toInt()) }
                    .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() }),
            )
        } else {
            AudioArtwork(entry?.albumArtUri, entry?.title.orEmpty(), entry?.artist ?: entry?.sourceLabel.orEmpty())
        }

        if (isVideo) {
            val videoRect = fitRect(area, state.videoSize, resize)
            SubtitleOverlay(
                lines = subtitleText,
                style = style,
                videoRect = videoRect,
                containerHeightPx = h,
                modifier = Modifier.fillMaxSize(),
                scale = if (inPip) 0.5f else 1f,
                onDragMargin = if (subtitleDragMode) {
                    { d -> marginOverride = (style.marginDp + d).coerceIn(0f, 400f) }
                } else {
                    null
                },
                onDragEnd = {
                    val m = marginOverride ?: return@SubtitleOverlay
                    scope.launch {
                        container.settings.updateSubtitle { it.copy(marginDp = m) }
                        marginOverride = null
                    }
                },
            )
        }

        if (inPip) return@BoxWithConstraints

        feedback?.let {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }

        if (subtitleDragMode) {
            Surface(
                color = Lumen.Surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("자막을 위아래로 끌어 위치를 정하세요", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { subtitleDragMode = false }) { Text("완료") }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !subtitleDragMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (locked) {
                Box(Modifier.fillMaxSize()) {
                    RoundIcon(Icons.Filled.Lock, "화면 잠금 해제", Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                        locked = false
                        touch()
                    }
                }
            } else {
                PlayerControls(
                    service = service,
                    state = state,
                    title = entry?.title.orEmpty(),
                    subtitleLine = mediaInfoLine(state, entry?.sourceLabel.orEmpty()),
                    backgroundPlay = settings.backgroundPlayback,
                    scrubMs = scrubMs,
                    onScrub = { scrubMs = it; touch() },
                    onBack = onClose,
                    onSheet = { sheet = it; touch() },
                    onToggleBackground = {
                        scope.launch { container.settings.update { s -> s.copy(backgroundPlayback = !s.backgroundPlayback) } }
                        feedback = if (settings.backgroundPlayback) "백그라운드 재생 끔" else "백그라운드 재생 켬"
                    },
                    onPip = onEnterPip,
                    onLock = { locked = true; touch() },
                    resizeLabel = resize.label,
                    onResize = {
                        resize = ResizeMode.entries[(resize.ordinal + 1) % ResizeMode.entries.size]
                        feedback = resize.label
                        touch()
                    },
                    onInteract = ::touch,
                )
            }
        }

        state.error?.let { msg ->
            Surface(
                color = Lumen.Surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { state.error = null; player.prepare(); player.play() }) { Text("다시 시도") }
                        if (state.hasNext) TextButton(onClick = { state.error = null; player.seekToNextMediaItem(); player.prepare() }) { Text("다음 항목") }
                    }
                }
            }
        }

        when (sheet) {
            PlayerSheet.NONE -> Unit
            PlayerSheet.SPEED -> SpeedSheet(state.speed, onDismiss = { sheet = PlayerSheet.NONE }) { player.setPlaybackSpeed(it) }
            PlayerSheet.SUBTITLE -> SubtitleSheet(
                service = service,
                state = state,
                onDismiss = { sheet = PlayerSheet.NONE },
                onAdjustPosition = {
                    sheet = PlayerSheet.NONE
                    subtitleDragMode = true
                },
            )
            PlayerSheet.QUEUE -> QueueSheet(service, onDismiss = { sheet = PlayerSheet.NONE })
            PlayerSheet.AUDIO -> AudioTrackSheet(state, onDismiss = { sheet = PlayerSheet.NONE })
        }
    }
}

/** 영상이 실제로 그려지는 사각형. 자막을 영상 가장자리에 붙이는 데 쓴다. */
private fun fitRect(area: Rect, size: VideoSize, mode: ResizeMode): Rect {
    if (size.width <= 0 || size.height <= 0 || mode != ResizeMode.FIT) return area
    val videoRatio = size.width * size.pixelWidthHeightRatio / size.height
    val areaRatio = area.width / area.height
    return if (videoRatio > areaRatio) {
        val hgt = area.width / videoRatio
        Rect(area.left, area.center.y - hgt / 2, area.right, area.center.y + hgt / 2)
    } else {
        val wid = area.height * videoRatio
        Rect(area.center.x - wid / 2, area.top, area.center.x + wid / 2, area.bottom)
    }
}

/** "거실 NAS · HEVC 1080p · DTS 5.1" 처럼 출처와 코덱을 한 줄로 */
@UnstableApi
private fun mediaInfoLine(state: PlayerUiState, source: String): String {
    val parts = mutableListOf(source)
    state.tracks.groups.filter { it.isSelected }.forEach { g ->
        val f = (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } ?: return@forEach
        when (g.type) {
            C.TRACK_TYPE_VIDEO -> parts += listOfNotNull(codecName(f.sampleMimeType), f.height.takeIf { it > 0 }?.let { "${it}p" }).joinToString(" ")
            C.TRACK_TYPE_AUDIO -> parts += listOfNotNull(codecName(f.sampleMimeType), channelName(f.channelCount)).joinToString(" ")
        }
    }
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

private fun codecName(mime: String?): String? = when (mime) {
    "video/hevc" -> "HEVC"
    "video/avc" -> "H.264"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/mp4v-es" -> "MPEG-4"
    "audio/mp4a-latm" -> "AAC"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
    "audio/true-hd" -> "TrueHD"
    "audio/flac" -> "FLAC"
    "audio/opus" -> "Opus"
    "audio/mpeg" -> "MP3"
    null -> null
    else -> mime.substringAfter('/').uppercase()
}

private fun channelName(count: Int) = when (count) {
    1 -> "모노"
    2 -> "스테레오"
    6 -> "5.1"
    8 -> "7.1"
    else -> if (count > 0) "${count}ch" else null
}

@UnstableApi
@Composable
private fun PlayerControls(
    service: PlaybackService,
    state: PlayerUiState,
    title: String,
    subtitleLine: String,
    backgroundPlay: Boolean,
    scrubMs: Long?,
    onScrub: (Long?) -> Unit,
    onBack: () -> Unit,
    onSheet: (PlayerSheet) -> Unit,
    onToggleBackground: () -> Unit,
    onPip: () -> Unit,
    onLock: () -> Unit,
    resizeLabel: String,
    onResize: () -> Unit,
    onInteract: () -> Unit,
) {
    val player = service.player
    val speedLabel = if (state.speed == 1f) "1.0×" else "${"%.2f".format(state.speed).trimEnd('0').trimEnd('.')}×"
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitleLine, style = MaterialTheme.typography.labelSmall, color = Lumen.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                onClick = { onSheet(PlayerSheet.SPEED) },
                shape = RoundedCornerShape(18.dp),
                color = Lumen.Primary.copy(alpha = 0.22f),
                modifier = Modifier.height(36.dp),
            ) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(speedLabel, color = Lumen.OnPrimaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = { onSheet(PlayerSheet.SUBTITLE) }) { Icon(Icons.Filled.ClosedCaption, "자막", tint = Color.White) }
            IconButton(onClick = onToggleBackground) {
                Icon(
                    Icons.Filled.Headphones,
                    if (backgroundPlay) "백그라운드 재생 켜짐" else "백그라운드 재생 꺼짐",
                    tint = if (backgroundPlay) Lumen.Primary else Color.White.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onPip) { Icon(Icons.Filled.PictureInPictureAlt, "화면 속 화면", tint = Color.White) }
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.hasPrevious) {
                IconButton(onClick = { player.seekToPreviousMediaItem(); onInteract() }) { Icon(Icons.Filled.SkipPrevious, "이전 항목", tint = Color.White) }
            }
            IconButton(onClick = { player.seekBack(); onInteract() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Replay10, "10초 뒤로", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Surface(
                onClick = {
                    if (player.isPlaying) player.pause() else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                    onInteract()
                },
                shape = RoundedCornerShape(26.dp),
                color = Lumen.Primary,
                modifier = Modifier.size(76.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "일시정지" else "재생",
                        tint = Lumen.OnPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            IconButton(onClick = { player.seekForward(); onInteract() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Forward10, "10초 앞으로", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            if (state.hasNext) {
                IconButton(onClick = { player.seekToNextMediaItem(); onInteract() }) { Icon(Icons.Filled.SkipNext, "다음 항목", tint = Color.White) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Filled.Lock, "화면 잠금", onLock)
                RowIcon(Icons.Filled.AspectRatio, "화면 비율: $resizeLabel", onResize)
                RowIcon(Icons.Filled.Audiotrack, "오디오 트랙", { onSheet(PlayerSheet.AUDIO) })
                RowIcon(Icons.AutoMirrored.Filled.QueueMusic, "재생 대기열", { onSheet(PlayerSheet.QUEUE) })
                RowIcon(
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    },
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "한 항목 반복"
                        Player.REPEAT_MODE_ALL -> "전체 반복"
                        else -> "반복 안 함"
                    },
                    {
                        player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    },
                    active = state.repeatMode != Player.REPEAT_MODE_OFF,
                )
                RowIcon(Icons.Filled.Shuffle, "무작위 재생", { player.shuffleModeEnabled = !player.shuffleModeEnabled }, active = state.shuffle)
                Spacer(Modifier.weight(1f))
            }
            LumenSeekBar(
                positionMs = scrubMs ?: state.positionMs,
                durationMs = state.durationMs,
                bufferedMs = state.bufferedMs,
                onSeek = { player.seekTo(it); onInteract() },
                onScrub = onScrub,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(scrubMs ?: state.positionMs), style = MaterialTheme.typography.bodySmall, color = Lumen.TextSoft)
                Spacer(Modifier.weight(1f))
                Text("-" + formatDuration(state.durationMs - state.positionMs), style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
                Spacer(Modifier.width(12.dp))
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.bodySmall, color = Lumen.TextSoft)
            }
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (active) Lumen.Primary else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), modifier = modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White) }
    }
}

@Composable
private fun AudioArtwork(art: String?, title: String, artist: String) {
    Column(Modifier.fillMaxSize().background(Lumen.Ground), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(200.dp).clip(RoundedCornerShape(28.dp)).background(Lumen.Amber), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MusicNote, null, tint = Lumen.OnPrimary, modifier = Modifier.size(72.dp))
            if (art != null) AsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 32.dp))
        Text(artist, style = MaterialTheme.typography.bodyMedium, color = Lumen.TextMuted, fontSize = 15.sp)
    }
}
