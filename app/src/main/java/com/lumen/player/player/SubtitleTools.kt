package com.lumen.player.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lumen.player.LumenApp
import com.lumen.player.subtitle.SubtitleCue
import com.lumen.player.subtitle.SubtitleParser
import com.lumen.player.subtitle.SubtitleRepository
import com.lumen.player.subtitle.SubtitleRepository.SaveLocation
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 외부 자막 전체를 시간순으로 보여 준다. 줄을 누르면 그 대사로 이동하고, 연필을 누르면 시간과 글을 고친다.
 * 고친 내용은 바로 화면에 반영되고 앱 저장소에 저장된다. 영상 옆에 저장하는 것은 [SaveToVideoDialog]가 맡는다.
 */
@UnstableApi
@Composable
fun SubtitleListSheet(service: PlaybackService, onDismiss: () -> Unit) {
    val repo = LumenApp.container.subtitles
    val entry by service.current.collectAsState()
    val external by service.externalSubtitle.collectAsState()
    val delayMs by service.subtitleDelayMs.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val media = entry ?: return
    val sub = external
    val cues = sub?.ordered.orEmpty()

    SideSheet(onDismiss, "자막 전체", sub?.let { "${cues.size}줄 · ${it.label}" }) {
        if (sub == null) {
            Text(
                "불러온 외부 자막이 없습니다. 자막 › 자막 선택에서 파일·검색·AI 자막을 먼저 고르세요. 영상에 들어 있는 내장 자막은 편집할 수 없습니다.",
                style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
            )
            return@SideSheet
        }
        // 재생 위치를 따라 지금 대사를 표시하고 목록을 맞춰 굴린다
        var current by remember { mutableIntStateOf(-1) }
        LaunchedEffect(sub, delayMs) {
            while (true) {
                current = sub.indexAt(service.player.currentPosition - delayMs)
                delay(300)
            }
        }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (sub.indexAt(service.player.currentPosition - delayMs) - 2).coerceAtLeast(0))
        LaunchedEffect(current) {
            if (current >= 0 && !listState.isScrollInProgress) {
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                if (current !in visible.drop(1).dropLast(1)) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("대사 추가")
            }
            TextButton(onClick = { saving = true; message = null }) {
                Icon(Icons.Filled.SaveAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("영상 위치에 저장")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lumen.Primary, modifier = Modifier.padding(bottom = 6.dp)) }

        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(cues) { i, cue ->
                Surface(
                    onClick = { service.player.seekTo(cue.startMs + delayMs) },
                    color = if (i == current) Lumen.PrimaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${SubtitleParser.srtTime(cue.startMs)}  →  ${SubtitleParser.srtTime(cue.endMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (i == current) Lumen.OnPrimaryContainer else Lumen.TextMuted,
                            )
                            Text(cue.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { editing = i }) { Icon(Icons.Filled.Edit, "대사 고치기", tint = Lumen.TextMuted, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }

        fun apply(newCues: List<SubtitleCue>) {
            scope.launch {
                runCatching { repo.saveEdited(media.uri, sub, newCues.sortedBy { it.startMs }) }
                    .onSuccess { service.setExternalSubtitle(it); message = "고친 내용을 저장했습니다" }
                    .onFailure { message = "저장하지 못했습니다: ${it.message}" }
            }
        }

        editing?.let { index ->
            val original = cues.getOrNull(index)
            if (original == null) {
                editing = null
            } else {
                CueEditDialog(
                    title = "대사 고치기",
                    initial = original,
                    nowMs = { service.player.currentPosition - delayMs },
                    onDismiss = { editing = null },
                    onDelete = {
                        apply(cues.filterIndexed { j, _ -> j != index })
                        editing = null
                    },
                    onSave = { edited ->
                        apply(cues.mapIndexed { j, c -> if (j == index) edited else c })
                        editing = null
                    },
                )
            }
        }
        if (adding) {
            val start = (service.player.currentPosition - delayMs).coerceAtLeast(0)
            CueEditDialog(
                title = "대사 추가",
                initial = SubtitleCue(start, start + 2_000, ""),
                nowMs = { service.player.currentPosition - delayMs },
                onDismiss = { adding = false },
                onDelete = null,
                onSave = { added ->
                    apply(cues + added)
                    adding = false
                },
            )
        }
        if (saving) {
            SaveToVideoDialog(service, onDone = { result -> saving = false; result?.let { message = it } })
        }
    }
}

/** 대사 하나의 시작·끝 시각과 글을 고친다. "지금"은 재생 위치를 넣는다 */
@Composable
private fun CueEditDialog(
    title: String,
    initial: SubtitleCue,
    nowMs: () -> Long,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SubtitleCue) -> Unit,
) {
    var start by remember { mutableStateOf(SubtitleParser.srtTime(initial.startMs)) }
    var end by remember { mutableStateOf(SubtitleParser.srtTime(initial.endMs)) }
    var text by remember { mutableStateOf(initial.text) }
    val startMs = SubtitleParser.parseTime(start)
    val endMs = SubtitleParser.parseTime(end)
    val error = when {
        startMs == null || endMs == null -> "시각은 00:01:02,345처럼 적어 주세요"
        endMs <= startMs -> "끝 시각이 시작보다 늦어야 합니다"
        text.isBlank() -> "대사를 적어 주세요"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("시작" to start, "끝" to end).forEach { (label, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value, { v -> if (label == "시작") start = v else end = v },
                            singleLine = true, label = { Text(label) }, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            val t = SubtitleParser.srtTime(nowMs().coerceAtLeast(0))
                            if (label == "시작") start = t else end = t
                        }) { Text("지금") }
                    }
                }
                OutlinedTextField(text, { text = it }, label = { Text("대사") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lumen.Error) }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { onSave(SubtitleCue(startMs!!, endMs!!, text.trim())) }) { Text("저장") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("삭제", color = Lumen.Error) } }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

private sealed interface SaveStep {
    data object Finding : SaveStep
    data class Access(val wanted: SaveLocation.NeedsAccess) : SaveStep
    data class Name(val location: SaveLocation, val name: String) : SaveStep
    data class Conflict(val location: SaveLocation, val name: String, val existing: Set<String>) : SaveStep
    data object Writing : SaveStep
    data class Failed(val message: String) : SaveStep
}

/**
 * 지금 자막을 영상이 있는 폴더(휴대폰 폴더 또는 서버)에 SRT로 저장한다. 싱크 조절값은 시각에 반영한다.
 * 같은 이름의 파일이 있으면 덮어쓸지, 다른 이름으로 저장할지 묻는다. 끝나면 [onDone]에 결과 문구(취소면 null)를 준다.
 */
@UnstableApi
@Composable
fun SaveToVideoDialog(service: PlaybackService, onDone: (String?) -> Unit) {
    val repo = LumenApp.container.subtitles
    val entry = service.current.collectAsState().value
    val sub = service.externalSubtitle.collectAsState().value
    val delayMs = service.subtitleDelayMs.collectAsState().value
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<SaveStep>(SaveStep.Finding) }
    if (entry == null || sub == null) {
        LaunchedEffect(Unit) { onDone(null) }
        return
    }
    val defaultName = remember(entry.uri, sub.source) { SubtitleRepository.defaultExportName(entry.title, repo.displayName(sub.source)) }

    fun askName(location: SaveLocation, name: String = defaultName) {
        step = SaveStep.Name(location, name)
    }

    LaunchedEffect(Unit) {
        step = when (val loc = runCatching { repo.saveLocation(entry) }.getOrElse { SaveLocation.Unsupported(it.message ?: "저장 위치를 찾지 못했습니다") }) {
            is SaveLocation.NeedsAccess -> SaveStep.Access(loc)
            is SaveLocation.Unsupported -> SaveStep.Failed(loc.reason)
            else -> SaveStep.Name(loc, defaultName)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        val wanted = (step as? SaveStep.Access)?.wanted
        if (tree == null || wanted == null) {
            onDone(null)
            return@rememberLauncherForActivityResult
        }
        runCatching { repo.locationFromPickedTree(tree, wanted) }
            .onSuccess { askName(it) }
            .onFailure { step = SaveStep.Failed("폴더 접근을 허락받지 못했습니다: ${it.message}") }
    }

    fun write(location: SaveLocation, name: String) {
        step = SaveStep.Writing
        scope.launch {
            // 화면에서 맞춘 싱크(지연)를 시각에 반영해 다른 플레이어에서도 그대로 맞게 한다
            val cues = sub.ordered.map { it.copy(startMs = (it.startMs + delayMs).coerceAtLeast(0), endMs = (it.endMs + delayMs).coerceAtLeast(0)) }
            runCatching { repo.writeTo(location, name, SubtitleParser.toSrt(cues).toByteArray()) }
                .onSuccess { onDone("${location.label}에 $name(으)로 저장했습니다") }
                .onFailure { step = SaveStep.Failed("저장하지 못했습니다: ${it.message}") }
        }
    }

    fun trySave(location: SaveLocation, rawName: String) {
        val name = rawName.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").let { if (it.lowercase().endsWith(".srt")) it else "$it.srt" }
        step = SaveStep.Writing
        scope.launch {
            runCatching { repo.existingNames(location) }
                .onSuccess { existing -> if (name.lowercase() in existing) step = SaveStep.Conflict(location, name, existing) else write(location, name) }
                .onFailure { step = SaveStep.Failed("저장 위치를 읽지 못했습니다: ${it.message}") }
        }
    }

    when (val s = step) {
        SaveStep.Finding, SaveStep.Writing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("영상 위치에 저장") },
            text = { Text(if (s == SaveStep.Finding) "저장할 폴더를 찾는 중…" else "저장하는 중…") },
            confirmButton = {},
        )
        is SaveStep.Access -> AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text("폴더 접근 허락") },
            text = {
                Text(
                    "영상이 있는 폴더(${s.wanted.label})에 자막을 저장하려면 이 폴더 접근을 한 번 허락해야 합니다. " +
                        "다음 화면에서 폴더가 열리면 아래의 \"이 폴더 사용\"을 누르세요. 한 번 허락하면 다음부터는 묻지 않고, " +
                        "이 폴더에 있는 자막도 자동으로 불러옵니다.",
                )
            },
            confirmButton = { TextButton(onClick = { pickFolder.launch(s.wanted.initialUri) }) { Text("폴더 선택") } },
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text("취소") } },
        )
        is SaveStep.Name -> {
            var name by remember(s) { mutableStateOf(s.name) }
            AlertDialog(
                onDismissRequest = { onDone(null) },
                title = { Text("영상 위치에 저장") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("저장할 곳: ${s.location.label}", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
                        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("파일 이름") }, modifier = Modifier.fillMaxWidth())
                        Text(
                            buildString {
                                append("SRT 형식으로 저장합니다. 영상과 이름이 같게 시작하면 다른 플레이어에서도 자막을 자동으로 불러옵니다.")
                                if (delayMs != 0L) append(" 싱크 조절(%+.1f초)도 시각에 반영합니다.".format(delayMs / 1000f))
                            },
                            style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
                        )
                    }
                },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { trySave(s.location, name) }) { Text("저장") } },
                dismissButton = { TextButton(onClick = { onDone(null) }) { Text("취소") } },
            )
        }
        is SaveStep.Conflict -> AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text("같은 이름의 자막이 있습니다") },
            text = { Text("${s.location.label}에 '${s.name}' 파일이 이미 있습니다. 덮어쓸까요, 다른 이름으로 저장할까요?") },
            confirmButton = { TextButton(onClick = { write(s.location, s.name) }) { Text("덮어쓰기", color = Lumen.Error) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askName(s.location, SubtitleRepository.alternativeName(s.name, s.existing)) }) { Text("다른 이름으로") }
                    TextButton(onClick = { onDone(null) }) { Text("취소") }
                }
            },
        )
        is SaveStep.Failed -> AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text("영상 위치에 저장") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { onDone(null) }) { Text("닫기") } },
        )
    }
}
