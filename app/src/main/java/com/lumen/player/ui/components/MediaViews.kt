package com.lumen.player.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.player.formatDuration
import com.lumen.player.ui.theme.Lumen

private val placeholderPairs = listOf(
    Color(0xFF284B57) to Color(0xFFF2C37C),
    Color(0xFF4A3A5A) to Color(0xFFB79AD6),
    Color(0xFF2F4A3A) to Color(0xFF9BD6A8),
    Color(0xFF4F3A2E) to Color(0xFFF2A97C),
    Color(0xFF2E3D57) to Color(0xFF9AB8F2),
)

/** 썸네일이 없을 때 시안 A처럼 단색 면 + 둥근 해를 그린다. 같은 파일은 늘 같은 색. */
@Composable
fun MediaThumb(uri: String, isVideo: Boolean, modifier: Modifier = Modifier, artUri: String? = null, overlay: @Composable BoxScope.() -> Unit = {}) {
    val (bg, sun) = placeholderPairs[(uri.hashCode() and 0x7fffffff) % placeholderPairs.size]
    val context = LocalContext.current
    val canDecode = uri.startsWith("content://") || uri.startsWith("file://")
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(bg)) {
        Box(Modifier.padding(start = 20.dp, top = 18.dp).size(40.dp).clip(CircleShape).background(sun))
        if (!isVideo) {
            Icon(Icons.Filled.MusicNote, null, tint = Lumen.OnPrimary, modifier = Modifier.align(Alignment.Center))
        }
        val model = when {
            artUri != null -> artUri
            isVideo && canDecode -> ImageRequest.Builder(context)
                .data(uri)
                .decoderFactory(VideoFrameDecoder.Factory())
                .videoFrameMillis(60_000)
                .crossfade(true)
                .build()
            else -> null
        }
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        overlay()
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3F5B5F))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Lumen.Primary))
    }
}

/** "이어서 보기" 가로 카드 (220dp) */
@Composable
fun ContinueCard(uri: String, title: String, meta: String, fraction: Float, isVideo: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(220.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        MediaThumb(uri, isVideo, Modifier.fillMaxWidth().height(124.dp)) {
            ProgressLine(fraction, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth())
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(meta, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

data class MenuAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    entry: MediaEntry,
    progress: Float?,
    onClick: () -> Unit,
    actions: List<MenuAction>,
    thumbSize: Dp = 64.dp,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumb(
            entry.uri, entry.isVideo,
            Modifier.width(if (entry.isVideo) thumbSize * 1.6f else thumbSize).height(thumbSize),
            artUri = entry.albumArtUri,
        ) {
            if (progress != null && progress > 0.01f) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth())
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                entry.artist,
                entry.durationMs.takeIf { it > 0 }?.let(::formatDuration),
                entry.sizeBytes.takeIf { it > 0 }?.let(::formatSize),
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted, maxLines = 1)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "더 보기", tint = Lumen.TextMuted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.offset(0.dp, 0.dp)) {
                actions.forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = false; a.onClick() })
                }
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1fGB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.0fMB".format(bytes / (1L shl 20).toFloat())
    else -> "%.0fKB".format(bytes / 1024f)
}
