package com.lumen.player.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lumen.player.LumenApp
import com.lumen.player.player.PlayerActivity
import com.lumen.player.player.rememberPlayerUiState
import com.lumen.player.ui.theme.Lumen

/** 시안 A의 미니 플레이어: 64dp, 모서리 20dp, 하단 메뉴 바로 위 */
@UnstableApi
@Composable
fun MiniPlayer() {
    val service by LumenApp.container.player.service.collectAsState()
    val svc = service ?: return
    val entry by svc.current.collectAsState()
    val current = entry ?: return
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
