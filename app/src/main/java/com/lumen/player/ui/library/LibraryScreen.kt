package com.lumen.player.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lumen.player.LumenApp
import com.lumen.player.data.db.ServerEntity
import com.lumen.player.data.db.ServerType
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.media.MediaPermissions
import com.lumen.player.player.formatDuration
import com.lumen.player.ui.Routes
import com.lumen.player.ui.components.ContinueCard
import com.lumen.player.ui.components.EmptyState
import com.lumen.player.ui.components.LumenChip
import com.lumen.player.ui.components.MediaRow
import com.lumen.player.ui.components.MenuAction
import com.lumen.player.ui.components.SectionTitle
import com.lumen.player.ui.components.SourceListRow
import com.lumen.player.ui.playlists.AddToPlaylistDialog
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun LibraryScreen(nav: NavController, vm: LibraryViewModel = viewModel()) {
    val data by vm.data.collectAsState()
    val tab by vm.tab.collectAsState()
    val query by vm.query.collectAsState()
    val continueList by vm.continueWatching.collectAsState()
    val servers by vm.servers.collectAsState()
    val progress by vm.progress.collectAsState()
    val context = LocalContext.current
    val container = LumenApp.container
    val scope = rememberCoroutineScope()
    var searchOpen by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf<List<MediaEntry>?>(null) }

    val activity = context as? Activity
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    val requestMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedPermission = true
        vm.refresh()
    }

    // 권한을 허용하고 돌아오거나 새 파일을 받은 뒤 목록을 새로 읽는다
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    fun actionsFor(list: List<MediaEntry>, index: Int) = listOf(
        MenuAction("재생") { container.player.play(context, list, index) },
        MenuAction("대기열에 추가") { container.player.enqueue(listOf(list[index])) },
        MenuAction("재생목록에 추가") { addToPlaylist = listOf(list[index]) },
        MenuAction("이어보기 기록 지우기") { scope.launch { container.db.playbackStates().delete(list[index].uri) } },
    )

    val filteredVideos = data.videos.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
    val filteredMusic = data.music.filter {
        query.isBlank() || it.title.contains(query, true) || it.artist?.contains(query, true) == true || it.album?.contains(query, true) == true
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("라이브러리", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) vm.query.value = "" }) {
                    Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, if (searchOpen) "검색 닫기" else "검색")
                }
                IconButton(onClick = { nav.navigate(Routes.browse(add = true)) }) { Icon(Icons.Filled.Add, "서버 추가") }
            }
        }
        if (searchOpen) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = { Text("제목, 가수, 앨범") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Row(Modifier.padding(start = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryTab.entries.forEach { t -> LumenChip(t.label, selected = tab == t) { vm.tab.value = t } }
                LumenChip("재생목록", selected = false) { nav.navigate(Routes.PLAYLISTS) }
            }
        }

        if (continueList.isNotEmpty() && query.isBlank()) {
            item { SectionTitle("이어서 보기") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continueList, key = { it.uri }) { s ->
                        val left = (s.durationMs - s.positionMs).coerceAtLeast(0)
                        ContinueCard(
                            uri = s.uri,
                            title = s.title,
                            meta = "${s.sourceLabel} · ${formatDuration(left)} 남음",
                            fraction = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f,
                            isVideo = s.isVideo,
                        ) {
                            val entries = continueList.map { MediaEntry(it.uri, it.title, it.isVideo, it.durationMs, sourceLabel = it.sourceLabel) }
                            container.player.play(context, entries, entries.indexOfFirst { it.uri == s.uri })
                        }
                    }
                }
            }
        }

        if (query.isBlank()) {
            item { SectionTitle("소스") }
            item {
                SourceListRow(Icons.Filled.Smartphone, "내 기기", "영상 ${data.videos.size} · 음악 ${data.music.size}", Modifier.padding(horizontal = 12.dp)) {
                    vm.tab.value = LibraryTab.FOLDER
                }
            }
            items(servers, key = { it.id }) { server ->
                SourceListRow(
                    serverIcon(server.type), server.name, serverSubtitle(server), Modifier.padding(horizontal = 12.dp),
                ) { nav.navigate(Routes.remote(server.id, "/", server.name)) }
            }
            if (servers.isEmpty()) {
                item {
                    TextButton(onClick = { nav.navigate(Routes.browse(add = true)) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text("NAS, FTP, WebDAV, 클라우드 연결하기")
                    }
                }
            }
        }

        when {
            data.permissionMissing -> item {
                // 거부를 거듭해 시스템 창이 더 뜨지 않으면 앱 설정의 권한 화면으로 보낸다
                val blocked = askedPermission && activity != null &&
                    MediaPermissions.media.none { activity.shouldShowRequestPermissionRationale(it) }
                EmptyState(
                    "기기의 미디어를 볼 수 없습니다",
                    if (blocked) "설정 › 권한에서 사진 및 동영상, 음악 및 오디오 접근을 허용하세요."
                    else "기기에 저장된 영상과 음악을 보려면 접근을 허용하세요.",
                ) {
                    Button(onClick = {
                        if (blocked) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } else {
                            requestMedia.launch(MediaPermissions.media)
                        }
                    }) { Text(if (blocked) "설정 열기" else "기기 미디어 접근 허용") }
                }
            }
            tab == LibraryTab.VIDEO -> {
                item { SectionTitle(if (query.isBlank()) "최근 추가된 영상" else "검색 결과 ${filteredVideos.size}개") }
                if (!data.loading && filteredVideos.isEmpty()) item { EmptyState("영상이 없습니다", "기기에 영상을 넣거나 네트워크 서버를 연결하세요.") }
                itemsIndexed(filteredVideos, key = { _, e -> e.uri }) { i, e ->
                    MediaRow(e, progress[e.uri], onClick = { container.player.play(context, filteredVideos, i) }, actions = actionsFor(filteredVideos, i))
                }
            }
            tab == LibraryTab.MUSIC -> {
                item {
                    Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("음악 ${filteredMusic.size}곡", Modifier.weight(1f))
                        if (filteredMusic.isNotEmpty()) TextButton(onClick = { container.player.play(context, filteredMusic.shuffled(), 0, openScreen = false) }) { Text("무작위 재생") }
                    }
                }
                itemsIndexed(filteredMusic, key = { _, e -> e.uri }) { i, e ->
                    MediaRow(e, null, onClick = { container.player.play(context, filteredMusic, i, openScreen = false) }, actions = actionsFor(filteredMusic, i), thumbSize = 52.dp)
                }
            }
            tab == LibraryTab.FOLDER -> {
                item { SectionTitle("폴더") }
                items(data.folders, key = { it.path }) { f ->
                    SourceListRow(Icons.Filled.Folder, f.name, "영상 ${f.items.size}개", Modifier.padding(horizontal = 12.dp)) {
                        nav.navigate(Routes.folder(f.name))
                    }
                }
            }
        }
    }

    addToPlaylist?.let { AddToPlaylistDialog(it, onDismiss = { addToPlaylist = null }) }
}

fun serverIcon(type: ServerType) = when (type) {
    ServerType.SAF -> Icons.Filled.Cloud
    ServerType.WEBDAV, ServerType.WEBDAVS -> Icons.Filled.Language
    else -> Icons.Filled.Dns
}

fun serverSubtitle(server: ServerEntity) = when (server.type) {
    ServerType.SAF -> "클라우드 · 문서 폴더"
    else -> "${server.type.label} · ${server.host}"
}

@Composable
fun StatusDot(on: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Lumen.Primary else Lumen.Outline))
}
