package com.lumen.player.ui.browse

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.lumen.player.LumenApp
import com.lumen.player.data.db.ServerEntity
import com.lumen.player.data.db.ServerType
import com.lumen.player.ui.Routes
import com.lumen.player.ui.components.LumenChip
import com.lumen.player.ui.components.SectionTitle
import com.lumen.player.ui.components.SourceListRow
import com.lumen.player.ui.library.serverIcon
import com.lumen.player.ui.library.serverSubtitle
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowseScreen(nav: NavController, openAdd: Boolean) {
    val container = LumenApp.container
    val servers by container.db.servers().observeAll().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(openAdd) }
    var editTarget by remember { mutableStateOf<ServerEntity?>(null) }

    // Google Drive, OneDrive, Dropbox 앱이 설치되어 있으면 이 선택 창에 함께 나온다
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: DocumentsContract.getTreeDocumentId(uri)
        scope.launch {
            container.remotes.save(ServerEntity(type = ServerType.SAF, name = name, host = uri.authority.orEmpty(), port = 0, basePath = uri.toString()), null)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("탐색", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { editTarget = null; editing = true }) { Icon(Icons.Filled.Add, "서버 추가") }
            }
        }
        item { SectionTitle("이 기기") }
        item {
            SourceListRow(Icons.Filled.Smartphone, "내 기기 영상 폴더", "기기에 저장된 영상과 음악", Modifier.padding(horizontal = 12.dp)) {
                nav.navigate(Routes.LIBRARY)
            }
        }
        item {
            SourceListRow(Icons.Filled.Cloud, "클라우드 폴더 연결", "Google Drive, OneDrive, Dropbox, Nextcloud 앱의 폴더", Modifier.padding(horizontal = 12.dp)) {
                pickTree.launch(null)
            }
        }
        item { SectionTitle("네트워크 서버") }
        if (servers.isEmpty()) {
            item {
                Text(
                    "집 NAS나 PC의 공유 폴더를 연결하면 여기에 나옵니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lumen.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(servers, key = { it.id }) { s ->
            var menu by remember { mutableStateOf(false) }
            SourceListRow(
                serverIcon(s.type), s.name, serverSubtitle(s), Modifier.padding(horizontal = 12.dp),
                trailing = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "서버 메뉴", tint = Lumen.TextMuted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (s.type != ServerType.SAF) {
                            DropdownMenuItem(text = { Text("수정") }, onClick = { menu = false; editTarget = s; editing = true })
                        }
                        DropdownMenuItem(text = { Text("삭제") }, onClick = {
                            menu = false
                            scope.launch {
                                container.db.servers().delete(s.id)
                                container.remotes.invalidate(s.id)
                            }
                        })
                    }
                },
            ) { nav.navigate(Routes.remote(s.id, "/", s.name)) }
        }
    }

    if (editing) {
        ServerDialog(initial = editTarget, onDismiss = { editing = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerDialog(initial: ServerEntity?, onDismiss: () -> Unit) {
    val container = LumenApp.container
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initial?.type ?: ServerType.SMB) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf(initial?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var path by remember { mutableStateOf(initial?.basePath.orEmpty()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    // 기존 비밀번호는 보여 주지 않는다. 비워 두면 그대로 유지한다
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pathLabel = when (type) {
        ServerType.SMB -> "공유 이름 (예: video 또는 video/영화)"
        ServerType.NFS -> "export 경로 (예: /volume1/video)"
        ServerType.WEBDAV, ServerType.WEBDAVS -> "경로 (예: remote.php/dav/files/me)"
        else -> "시작 폴더 (비우면 최상위)"
    }
    val userLabel = if (type == ServerType.NFS) "uid:gid (비우면 0:0)" else "사용자 이름 (비우면 게스트)"

    fun draft() = ServerEntity(
        id = initial?.id ?: 0,
        type = type,
        name = name.ifBlank { host },
        host = host.trim(),
        port = port.toIntOrNull() ?: type.defaultPort,
        basePath = path.trim(),
        username = user.trim(),
        password = initial?.password.orEmpty(),
        domain = domain.trim(),
    )
    fun plainPassword(): String? = password.takeIf { it.isNotEmpty() || initial == null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "서버 추가" else "서버 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ServerType.entries.filter { it != ServerType.SAF }.forEach { t ->
                        LumenChip(t.label, selected = type == t) { type = t }
                    }
                }
                OutlinedTextField(host, { host = it }, label = { Text("주소 (예: 192.168.0.10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) },
                    label = { Text("포트 (기본 ${type.defaultPort})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(path, { path = it }, label = { Text(pathLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text(userLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type != ServerType.NFS) {
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text(if (initial == null) "비밀번호" else "비밀번호 (바꿀 때만 입력)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == ServerType.SMB) {
                    OutlinedTextField(domain, { domain = it }, label = { Text("도메인 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(name, { name = it }, label = { Text("표시 이름 (예: 거실 NAS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("연결됨")) Lumen.Primary else Lumen.Error) }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    enabled = host.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        status = "연결하는 중"
                        scope.launch {
                            val pass = plainPassword()
                                ?: com.lumen.player.data.settings.SecretBox.open(initial?.password.orEmpty())
                            val r = withContext(Dispatchers.IO) { container.remotes.test(draft(), pass) }
                            status = r.fold({ "연결됨 · 항목 ${it}개" }, { "연결 실패: ${it.message ?: it.javaClass.simpleName}" })
                            busy = false
                        }
                    },
                ) { Text("연결 시험") }
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank(), onClick = {
                scope.launch {
                    container.remotes.save(draft(), plainPassword())
                    onDismiss()
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
