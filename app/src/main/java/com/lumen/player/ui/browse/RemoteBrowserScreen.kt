package com.lumen.player.ui.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lumen.player.LumenApp
import com.lumen.player.data.db.ServerType
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.media.MediaTypes
import com.lumen.player.remote.RemoteEntry
import com.lumen.player.remote.RemoteUris
import com.lumen.player.remote.SafFileSystem
import com.lumen.player.ui.Routes
import com.lumen.player.ui.components.EmptyState
import com.lumen.player.ui.components.MediaRow
import com.lumen.player.ui.components.MenuAction
import com.lumen.player.ui.components.SourceListRow
import com.lumen.player.ui.components.formatSize
import com.lumen.player.ui.playlists.AddToPlaylistDialog
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
fun RemoteBrowserScreen(nav: NavController, serverId: Long, path: String, title: String) {
    val container = LumenApp.container
    val context = LocalContext.current
    var dirs by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var media by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var addToPlaylist by remember { mutableStateOf<List<MediaEntry>?>(null) }
    val progressMap by container.db.playbackStates().observeAll().collectAsState(initial = emptyList())

    LaunchedEffect(serverId, path, reload) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val server = container.remotes.server(serverId) ?: throw IllegalStateException("서버가 삭제되었습니다")
                val fs = container.remotes.get(serverId)
                val entries = fs.list(path).filterNot { it.name.startsWith(".") }
                // SAF는 문서 URI로, 나머지는 lumen:// 로 재생한다
                fun playUri(e: RemoteEntry): String =
                    if (server.type == ServerType.SAF) (fs as SafFileSystem).documentUri(e.path).toString()
                    else RemoteUris.build(serverId, e.path).toString()
                val siblings = entries.filter { !it.isDirectory }.map { it.name to playUri(it) }
                val mediaEntries = entries.filter { !it.isDirectory && MediaTypes.isMedia(it.name) }
                    .sortedBy { it.name.lowercase() }
                    .map { e ->
                        MediaEntry(
                            uri = playUri(e),
                            title = e.name,
                            isVideo = MediaTypes.isVideo(e.name),
                            sizeBytes = e.size.coerceAtLeast(0),
                            sourceLabel = server.name,
                            sidecarSubtitles = com.lumen.player.subtitle.SubtitleRepository.sidecarsFor(e.name, siblings),
                        )
                    }
                entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() } to mediaEntries
            }
        }
        result.onSuccess { (d, m) -> dirs = d; media = m }.onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    val progress = remember(progressMap) {
        progressMap.filter { it.durationMs > 0 }.associate { it.uri to it.positionMs.toFloat() / it.durationMs }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                Text(
                    if (path == "/" || path.isEmpty()) title else path.trimEnd('/').substringAfterLast('/').let { if (it.contains(':')) it.substringAfterLast(':').substringAfterLast('/') else it },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "새로 고침") }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Lumen.Primary) }
        error?.let { msg ->
            item {
                EmptyState("폴더를 열지 못했습니다", msg) {
                    TextButton(onClick = { reload++ }) { Text("다시 시도") }
                }
            }
        }
        if (!loading && error == null && dirs.isEmpty() && media.isEmpty()) {
            item { EmptyState("재생할 수 있는 파일이 없습니다", "다른 폴더를 열어 보세요.") }
        }
        items(dirs, key = { "d:" + it.path }) { d ->
            SourceListRow(Icons.Filled.Folder, d.name, "", Modifier.padding(horizontal = 12.dp)) {
                nav.navigate(Routes.remote(serverId, d.path, title))
            }
        }
        if (media.isNotEmpty()) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("파일 ${media.size}개 · ${formatSize(media.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { addToPlaylist = media }) { Text("모두 재생목록에") }
                    TextButton(onClick = { container.player.play(context, media, 0) }) { Text("모두 재생") }
                }
            }
        }
        itemsIndexed(media, key = { _, m -> m.uri }) { i, m ->
            MediaRow(
                m, progress[m.uri],
                onClick = { container.player.play(context, media, i) },
                actions = listOf(
                    MenuAction("재생") { container.player.play(context, media, i) },
                    MenuAction("여기부터 이어서 재생") { container.player.play(context, media.drop(i), 0) },
                    MenuAction("대기열에 추가") { container.player.enqueue(listOf(m)) },
                    MenuAction("재생목록에 추가") { addToPlaylist = listOf(m) },
                ),
            )
        }
    }
    addToPlaylist?.let { AddToPlaylistDialog(it, onDismiss = { addToPlaylist = null }) }
}

@UnstableApi
@Composable
fun LocalFolderScreen(nav: NavController, name: String) {
    val container = LumenApp.container
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var addToPlaylist by remember { mutableStateOf<List<MediaEntry>?>(null) }
    LaunchedEffect(name) {
        items = runCatching { container.localMedia.videos().filter { it.sourceLabel == name } }.getOrDefault(emptyList())
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 4.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (items.isNotEmpty()) container.player.play(context, items, 0) }) { Text("모두 재생") }
            }
        }
        itemsIndexed(items, key = { _, m -> m.uri }) { i, m ->
            MediaRow(
                m, null,
                onClick = { container.player.play(context, items, i) },
                actions = listOf(
                    MenuAction("대기열에 추가") { container.player.enqueue(listOf(m)) },
                    MenuAction("재생목록에 추가") { addToPlaylist = listOf(m) },
                ),
            )
        }
    }
    addToPlaylist?.let { AddToPlaylistDialog(it, onDismiss = { addToPlaylist = null }) }
}
