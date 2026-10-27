package com.lumen.player.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lumen.player.LumenApp
import com.lumen.player.player.PlayerActivity
import com.lumen.player.player.rememberPlayerUiState
import com.lumen.player.ui.theme.Lumen

/**
 * 시안 A의 미니 플레이어: 64dp, 모서리 20dp, 하단 메뉴 바로 위.
 * 음악처럼 화면 없이 듣는 동안에만 보인다. 영상은 [ResumeVideoButton]으로 돌아간다.
 */
@UnstableApi
@Composable
fun MiniPlayer() {
    val service by LumenApp.container.player.service.collectAsState()
    val svc = service ?: return
    val entry by svc.current.collectAsState()
    val current = entry?.takeIf { !it.isVideo } ?: return
    val state = rememberPlayerUiState(svc.player)
    val context = LocalContext.current
    Surface(
        onClick = { context.startActivity(Intent(context, PlayerActivity::class.java)) },
        color = Lumen.SurfaceHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(64.dp),
    ) {
        Box {
            Row(Modifier.padding(start = 10.dp, end = 4.dp).fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaThumb(current.uri, current.isVideo, Modifier.size(44.dp), artUri = current.albumArtUri)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val speed = "%.2f".format(state.speed).trimEnd('0').trimEnd('.').let { if ('.' in it) it else "$it.0" }
                    Text(
                        (if (state.isPlaying) "백그라운드 재생 중" else "일시정지됨") + " · ${speed}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lumen.TextMuted,
                    )
                }
                IconButton(onClick = { if (svc.player.isPlaying) svc.player.pause() else svc.player.play() }) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "일시정지" else "재생")
                }
                if (state.hasNext) {
                    IconButton(onClick = { svc.player.seekToNextMediaItem() }) { Icon(Icons.Filled.SkipNext, "다음") }
                }
            }
            if (state.durationMs > 0) {
                ProgressLine(
                    state.positionMs.toFloat() / state.durationMs,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).fillMaxWidth().height(2.dp),
                )
            }
        }
    }
}

/**
 * 보던 영상으로 돌아가는 작은 둥근 버튼(화면 오른쪽 아래). 썸네일 위에 재생 표시와 진행 고리를 그린다.
 * 모서리의 X를 누르면 영상을 열지 않고 재생을 끝낸다.
 */
@UnstableApi
@Composable
fun ResumeVideoButton() {
    val service by LumenApp.container.player.service.collectAsState()
    val svc = service ?: return
    val entry by svc.current.collectAsState()
    val current = entry?.takeIf { it.isVideo } ?: return
    val state = rememberPlayerUiState(svc.player)
    val context = LocalContext.current
    Box(Modifier.size(64.dp)) {
        Surface(
            onClick = { context.startActivity(Intent(context, PlayerActivity::class.java)) },
            shape = CircleShape,
            color = Lumen.SurfaceHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomStart).size(56.dp).semantics { contentDescription = "영상으로 돌아가기 · ${current.title}" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                MediaThumb(current.uri, current.isVideo, Modifier.fillMaxSize().clip(CircleShape), artUri = current.albumArtUri)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                if (state.durationMs > 0) {
                    CircularProgressIndicator(
                        progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(),
                        color = Lumen.Primary,
                        trackColor = Color.Transparent,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
        Surface(
            onClick = { svc.stopPlayback() },
            shape = CircleShape,
            color = Lumen.SurfaceHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "재생 종료", tint = Lumen.TextMuted, modifier = Modifier.size(14.dp))
            }
        }
    }
}
