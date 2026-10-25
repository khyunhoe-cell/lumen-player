package com.lumen.player.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lumen.player.LumenApp
import com.lumen.player.data.db.PlaylistEntity
import com.lumen.player.data.db.PlaylistItemEntity
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.ui.Routes
import com.lumen.player.ui.components.EmptyState
import com.lumen.player.ui.components.MediaThumb
import com.lumen.player.ui.components.SourceListRow
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(nav: NavController) {
    val dao = LumenApp.container.db.playlists()
    val playlists by dao.observePlaylists().collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("재생목록", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "재생목록 만들기") }
            }
        }
        if (playlists.isEmpty()) {
            item {
                EmptyState("재생목록이 없습니다", "영상이나 음악의 메뉴에서 '재생목록에 추가'를 누르거나 새로 만드세요.") {
                    TextButton(onClick = { creating = true }) { Text("재생목록 만들기") }
                }
            }
        }
        items(playlists, key = { it.id }) { p ->
            val parts = listOfNotNull(
                p.videoCount.takeIf { it > 0 }?.let { "영상 $it" },
                (p.itemCount - p.videoCount).takeIf { it > 0 }?.let { "음악 $it" },
            )
            SourceListRow(Icons.AutoMirrored.Filled.PlaylistPlay, p.name, parts.joinToString(" · ").ifEmpty { "비어 있음" }, Modifier.padding(horizontal = 12.dp)) {
                nav.navigate(Routes.playlist(p.id))
            }
        }
    }
    if (creating) NameDialog("새 재생목록", "", onDismiss = { creating = false }) { name ->
        LumenApp.container.scope.launch { dao.insertPlaylist(PlaylistEntity(name = name)) }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("이름") }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()); onDismiss() }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun PlaylistItemEntity.toEntry() = MediaEntry(uri = uri, title = title, isVideo = isVideo, sourceLabel = sourceLabel)

@UnstableApi
@Composable
fun PlaylistDetailScreen(nav: NavController, id: Long) {
    val container = LumenApp.container
    val dao = container.db.playlists()
    val playlist by dao.observePlaylist(id).collectAsState(initial = null)
    val items by dao.observeItems(id).collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                Text(playlist?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { renaming = true }) { Text("이름 바꾸기") }
                TextButton(onClick = { confirmDelete = true }) { Text("삭제", color = Lumen.Error) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = { if (items.isNotEmpty()) container.player.play(context, items.map { it.toEntry() }, 0) },
                    color = Lumen.Primary, contentColor = Lumen.OnPrimary, shape = MaterialTheme.shapes.extraLarge,
                ) { Text("전체 재생", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) }
                Surface(
                    onClick = { if (items.isNotEmpty()) container.player.play(context, items.map { it.toEntry() }.shuffled(), 0) },
                    color = Color.Transparent, contentColor = Lumen.TextSoft, shape = MaterialTheme.shapes.extraLarge,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Lumen.Outline),
                ) { Text("무작위 재생", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) }
            }
        }
        if (items.isEmpty()) item { EmptyState("비어 있는 재생목록", "라이브러리나 서버 폴더에서 항목을 추가하세요.") }
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = { container.player.play(context, items.map { it.toEntry() }, index) }, color = Color.Transparent, modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MediaThumb(item.uri, item.isVideo, Modifier.padding(end = 12.dp).size(width = 96.dp, height = 56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.sourceLabel, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
                        }
                    }
                }
                IconButton(enabled = index > 0, onClick = {
                    scope.launch { dao.reorder(items.map { it.id }.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                }) { Icon(Icons.Filled.KeyboardArrowUp, "위로") }
                IconButton(enabled = index < items.lastIndex, onClick = {
                    scope.launch { dao.reorder(items.map { it.id }.toMutableList().apply { add(index + 1, removeAt(index)) }) }
                }) { Icon(Icons.Filled.KeyboardArrowDown, "아래로") }
                IconButton(onClick = { scope.launch { dao.deleteItem(item.id) } }) { Icon(Icons.Filled.Close, "빼기", tint = Lumen.TextMuted) }
            }
        }
    }

    if (renaming) NameDialog("이름 바꾸기", playlist?.name.orEmpty(), onDismiss = { renaming = false }) { name ->
        scope.launch { dao.rename(id, name) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("'${playlist?.name}' 재생목록을 삭제할까요?") },
            text = { Text("파일은 지워지지 않고 목록만 사라집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.deletePlaylist(id) }
                    confirmDelete = false
                    nav.popBackStack()
                }) { Text("삭제", color = Lumen.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/** 하나 이상의 항목을 기존 또는 새 재생목록에 넣는다 */
@Composable
fun AddToPlaylistDialog(entries: List<MediaEntry>, onDismiss: () -> Unit) {
    val container = LumenApp.container
    val dao = container.db.playlists()
    val playlists by dao.observePlaylists().collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }

    fun addTo(playlistId: Long) {
        container.scope.launch {
            val start = dao.maxOrder(playlistId) + 1
            dao.insertItems(entries.mapIndexed { i, e ->
                PlaylistItemEntity(playlistId = playlistId, uri = e.uri, title = e.title, isVideo = e.isVideo, sourceLabel = e.sourceLabel, sortOrder = start + i)
            })
        }
        onDismiss()
    }

    if (creating) {
        NameDialog("새 재생목록", "", onDismiss = onDismiss) { name ->
            container.scope.launch {
                val id = dao.insertPlaylist(PlaylistEntity(name = name))
                dao.insertItems(entries.mapIndexed { i, e ->
                    PlaylistItemEntity(playlistId = id, uri = e.uri, title = e.title, isVideo = e.isVideo, sourceLabel = e.sourceLabel, sortOrder = i)
                })
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entries.size == 1) "재생목록에 추가" else "${entries.size}개를 재생목록에 추가") },
        text = {
            LazyColumn {
                item {
                    TextButton(onClick = { creating = true }) { Text("새 재생목록 만들기") }
                }
                items(playlists, key = { it.id }) { p ->
                    Surface(onClick = { addTo(p.id) }, color = Color.Transparent) {
                        Text("${p.name}  (${p.itemCount})", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
