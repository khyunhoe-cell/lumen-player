package com.lumen.player.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import com.lumen.player.LumenApp
import com.lumen.player.data.settings.KeySlot
import com.lumen.player.data.settings.SubtitleFont
import com.lumen.player.data.settings.SubtitlePosition
import com.lumen.player.subtitle.ai.AiJob
import com.lumen.player.subtitle.online.OnlineSubtitle
import com.lumen.player.ui.components.ColorSwatches
import com.lumen.player.ui.components.LabeledSlider
import com.lumen.player.ui.components.LumenChip
import com.lumen.player.ui.settings.ApiKeyField
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.launch
import java.util.Locale

/** 시안 A의 오른쪽 시트: 왼쪽 모서리만 둥근 404dp 패널 */
@Composable
fun SideSheet(onDismiss: () -> Unit, title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Surface(
            color = Lumen.Surface,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(404.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
                }
                Column(Modifier.padding(end = 12.dp), content = content)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(current: Float, onDismiss: () -> Unit, onChange: (Float) -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableFloatStateOf(current) }
    SideSheet(onDismiss, "재생 속도", "%.2f×".format(value)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s ->
                LumenChip(label = "${s}×", selected = kotlin.math.abs(value - s) < 0.01f) {
                    value = s
                    onChange(s)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("세밀하게 조절", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        Slider(
            value = value,
            onValueChange = { value = (it * 20).toInt() / 20f },
            onValueChangeFinished = { onChange(value) },
            valueRange = 0.25f..4f,
        )
        Text("음 높이는 그대로 두고 속도만 바꿉니다", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = {
            scope.launch { LumenApp.container.settings.update { it.copy(defaultSpeed = value) } }
            onDismiss()
        }) { Text("이 속도를 기본값으로 저장") }
    }
}

private enum class SubtitleTab(val label: String) { SOURCE("자막 선택"), STYLE("스타일"), SYNC("싱크") }

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
fun SubtitleSheet(service: PlaybackService, state: PlayerUiState, onDismiss: () -> Unit, onOpenList: () -> Unit, onAdjustPosition: () -> Unit) {
    val container = LumenApp.container
    val entry by service.current.collectAsState()
    val external by service.externalSubtitle.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val job by container.aiSubtitles.job.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SubtitleTab.SOURCE) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val media = entry ?: return

    val textGroups = state.tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val status = when {
        external != null -> external!!.label
        textGroups.isEmpty() -> "이 영상에 자막이 없어요"
        else -> "내장 자막 ${textGroups.size}개"
    }

    var needKey by remember { mutableStateOf<KeySlot?>(null) }
    fun startAi() {
        scope.launch {
            // 방금 저장한 키도 보이도록 화면 상태 대신 저장소에서 최신 설정을 읽는다
            needKey = container.aiSubtitles.missingKey(container.settings.current().ai)
            if (needKey != null) return@launch
            container.aiSubtitles.start(
                media, container.subtitles,
                onPartial = { partial -> scope.launch { if (service.current.value?.uri == media.uri) service.setExternalSubtitle(partial) } },
                onDone = { done -> scope.launch { if (service.current.value?.uri == media.uri) service.setExternalSubtitle(done) } },
            )
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // 다음에 이어볼 때도 쓰도록 앱 저장소에 복사해 둔다
            runCatching {
                val name = container.subtitles.displayName(uri.toString())
                val bytes = container.subtitles.readBytes(uri.toString())
                val file = container.subtitles.saveRaw(media.uri, name, bytes)
                container.subtitles.load(file.absolutePath, name).getOrThrow()
            }.onSuccess { service.setExternalSubtitle(it) }.onFailure { message = it.message }
        }
    }

    SideSheet(onDismiss, "자막", status) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            SubtitleTab.entries.forEach { t -> LumenChip(t.label, selected = tab == t) { tab = t; searching = false } }
        }
        message?.let { Text(it, color = Lumen.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        notice?.let { Text(it, color = Lumen.Primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        if (saving) SaveToVideoDialog(service, onDone = { result -> saving = false; notice = result })
        when (tab) {
            SubtitleTab.SOURCE -> if (searching) {
                OnlineSearchPanel(service, onBack = { searching = false }, onError = { message = it })
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigAction(
                            icon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp)) },
                            title = "온라인 검색",
                            sub = "OpenSubtitles · SubDL",
                            highlighted = false,
                            modifier = Modifier.weight(1f),
                        ) { searching = true; message = null }
                        BigAction(
                            icon = { Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(20.dp)) },
                            title = "AI로 생성",
                            sub = "${settings.ai.provider.label} ${settings.ai.model()} · ${languageName(settings.ai.language)}" +
                                settings.ai.target().let { if (it.isBlank()) "" else " → ${Locale(it).displayLanguage}" },
                            highlighted = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            message = null
                            startAi()
                        }
                    }
                    needKey?.let { provider ->
                        // 키가 없으면 여기서 바로 넣고, 저장되면 이어서 생성을 시작한다
                        Surface(color = Lumen.Ground, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Text("AI 자막을 만들려면 ${provider.label} API 키가 필요합니다", style = MaterialTheme.typography.bodySmall)
                                ApiKeyField(provider, onSaved = { needKey = null; startAi() })
                            }
                        }
                    }
                    job?.takeIf { it.mediaUri == media.uri && needKey == null }?.let { AiJobCard(it, onRetry = ::startAi) }
                    external?.let { sub ->
                        // 지금 자막: 전체 보기·편집, 영상 옆에 저장
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigAction(
                                icon = { Icon(Icons.AutoMirrored.Filled.Subject, null, modifier = Modifier.size(20.dp)) },
                                title = "전체 보기·편집",
                                sub = "${sub.cues.size}줄",
                                highlighted = false,
                                modifier = Modifier.weight(1f),
                                onClick = onOpenList,
                            )
                            BigAction(
                                icon = { Icon(Icons.Filled.SaveAlt, null, modifier = Modifier.size(20.dp)) },
                                title = "영상 위치에 저장",
                                sub = "영상 폴더에 .srt로",
                                highlighted = false,
                                modifier = Modifier.weight(1f),
                            ) { saving = true; notice = null; message = null }
                        }
                    }

                    SourceRow("자막 끄기", selected = external == null && textGroups.none { it.isSelected }) {
                        service.setExternalSubtitle(null)
                        service.setEmbeddedTextEnabled(false)
                    }
                    textGroups.forEach { g ->
                        val f = g.getTrackFormat(0)
                        val name = f.label ?: f.language?.let { Locale(it).displayLanguage } ?: "내장 자막"
                        SourceRow("내장 · $name", selected = external == null && g.isSelected) {
                            service.setExternalSubtitle(null)
                            service.player.trackSelectionParameters = service.player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
                                .build()
                        }
                    }
                    val saved = remember(media.uri, external) { container.subtitles.saved(media.uri) }
                    (saved.map { it.absolutePath to it.name } + media.sidecarSubtitles.map { it to container.subtitles.displayName(it) })
                        .distinctBy { it.first }
                        .forEach { (source, name) ->
                            SourceRow(name, selected = external?.source == source) {
                                scope.launch {
                                    container.subtitles.load(source, name)
                                        .onSuccess { service.setExternalSubtitle(it) }
                                        .onFailure { message = it.message }
                                }
                            }
                        }
                    Surface(onClick = { pickFile.launch(arrayOf("*/*")) }, color = Color.Transparent, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FolderOpen, null, tint = Lumen.Primary)
                            Spacer(Modifier.width(12.dp))
                            Text("파일에서 불러오기 (.srt .ass .smi .vtt)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            SubtitleTab.STYLE -> SubtitleStyleQuick(onAdjustPosition)
            SubtitleTab.SYNC -> SyncPanel(service)
        }
    }
}

private fun languageName(code: String) = if (code.isBlank()) "언어 자동 감지" else Locale(code).displayLanguage

@Composable
private fun BigAction(icon: @Composable () -> Unit, title: String, sub: String, highlighted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (highlighted) Lumen.PrimaryContainer else Lumen.SurfaceHigh,
        contentColor = if (highlighted) Lumen.OnPrimaryContainer else Lumen.Text,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.height(72.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = if (highlighted) Color(0xFFA8D9D0) else Lumen.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AiJobCard(job: AiJob, onRetry: () -> Unit) {
    val container = LumenApp.container
    Surface(color = Lumen.Ground, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = if (job.stage == AiJob.Stage.FAILED) Lumen.Error else Lumen.Text)
                if (job.isActive) {
                    Text("${(job.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Lumen.Primary)
                    TextButton(onClick = { container.aiSubtitles.cancel() }) { Text("취소") }
                } else {
                    if (job.stage == AiJob.Stage.FAILED || job.stage == AiJob.Stage.CANCELLED) {
                        TextButton(onClick = onRetry) { Text("다시 시도") }
                    }
                    TextButton(onClick = { container.aiSubtitles.dismiss() }) { Text("닫기") }
                }
            }
            if (job.isActive) {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = Lumen.Primary,
                    trackColor = Lumen.Hairline,
                )
                Spacer(Modifier.height(6.dp))
                Text("만들어진 부분부터 바로 화면에 나옵니다", style = MaterialTheme.typography.labelSmall, color = Lumen.TextMuted)
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (selected) Lumen.PrimaryContainer else Color.Transparent, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(Icons.Filled.Check, "선택됨", tint = Lumen.Primary)
        }
    }
}

@UnstableApi
@Composable
private fun OnlineSearchPanel(service: PlaybackService, onBack: () -> Unit, onError: (String) -> Unit) {
    val container = LumenApp.container
    val media = service.current.collectAsState().value ?: return
    val scope = rememberCoroutineScope()
    var query by remember(media.uri) { mutableStateOf(container.onlineSubtitles.parseTitle(media.title).query) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<OnlineSubtitle?>(null) }

    fun runSearch() {
        loading = true
        scope.launch {
            container.onlineSubtitles.search(media, query)
                .onSuccess { results = it }
                .onFailure { onError(it.message ?: "검색 실패") }
            loading = false
        }
    }
    LaunchedEffect(media.uri) { runSearch() }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("제목") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { runSearch() }) { Icon(Icons.Filled.Search, "검색") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("돌아가기") }
            Spacer(Modifier.weight(1f))
            when {
                loading -> Text("찾는 중", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
                results != null -> Text("${results!!.size}개 찾음", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
            }
        }
        if (results?.isEmpty() == true) {
            Text("맞는 자막이 없습니다. 제목을 바꿔 검색하거나 AI로 생성해 보세요.", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(results.orEmpty()) { _, r ->
                Surface(
                    onClick = {
                        downloading = r
                        scope.launch {
                            container.onlineSubtitles.download(r)
                                .mapCatching { (name, bytes) ->
                                    val file = container.subtitles.saveRaw(media.uri, name, bytes)
                                    container.subtitles.load(file.absolutePath, name).getOrThrow()
                                }
                                .onSuccess { service.setExternalSubtitle(it); onBack() }
                                .onFailure { onError(it.message ?: "다운로드 실패") }
                            downloading = null
                        }
                    },
                    color = if (downloading == r) Lumen.PrimaryContainer else Lumen.SurfaceHigh,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(r.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                r.language.uppercase(),
                                r.provider.label,
                                r.downloads.takeIf { it > 0 }?.let { "다운로드 $it" },
                                if (r.hashMatch) "파일 일치" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (r.hashMatch) Lumen.Primary else Lumen.TextMuted,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleStyleQuick(onAdjustPosition: () -> Unit) {
    val container = LumenApp.container
    val style = container.settings.settings.collectAsState().value.subtitle
    val scope = rememberCoroutineScope()
    fun edit(t: (com.lumen.player.data.settings.SubtitleStyle) -> com.lumen.player.data.settings.SubtitleStyle) {
        scope.launch { container.settings.updateSubtitle(t) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("위치", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SubtitlePosition.entries.forEach { p -> LumenChip(p.label, selected = style.position == p) { edit { it.copy(position = p) } } }
            LumenChip("직접 끌어 놓기", selected = false, onClick = onAdjustPosition)
        }
        Text("글꼴", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SubtitleFont.entries.filter { it != SubtitleFont.CUSTOM || style.customFontPath != null }.forEach { f ->
                LumenChip(f.label, selected = style.font == f) { edit { it.copy(font = f) } }
            }
            LumenChip("굵게", selected = style.bold) { edit { it.copy(bold = !it.bold) } }
        }
        LabeledSlider("크기", style.sizeSp, 12f..48f, "${style.sizeSp.toInt()}") { v -> edit { it.copy(sizeSp = v) } }
        LabeledSlider("줄 간격", style.lineSpacing, 1f..2.2f, "%.1f".format(style.lineSpacing)) { v -> edit { it.copy(lineSpacing = v) } }
        LabeledSlider("외곽선", style.outlineWidth, 0f..6f, "%.1fdp".format(style.outlineWidth)) { v -> edit { it.copy(outlineWidth = v) } }
        LabeledSlider("그림자", style.shadowRadius, 0f..12f, "%.0f".format(style.shadowRadius)) { v -> edit { it.copy(shadowRadius = v, shadowOffset = v / 2) } }
        val bgAlpha = ((style.backgroundColor ushr 24) and 0xFF).toFloat() / 255f
        LabeledSlider("배경", bgAlpha, 0f..1f, "${(bgAlpha * 100).toInt()}%") { v ->
            edit { it.copy(backgroundColor = ((v * 255).toLong() shl 24) or (it.backgroundColor and 0xFFFFFF)) }
        }
        Text("글자 색", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        ColorSwatches(
            colors = listOf(0xFFFFFFFF, 0xFFFFE9A8, 0xFFA8E8DE, 0xFFF5B3C6, 0xFFFFD400, 0xFF9AD0FF),
            selected = style.textColor,
        ) { c -> edit { it.copy(textColor = c) } }
        Text("외곽선 색", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        ColorSwatches(colors = listOf(0xFF000000, 0xFF183641, 0xFF3A2A00, 0xFFFFFFFF), selected = style.outlineColor) { c ->
            edit { it.copy(outlineColor = c) }
        }
        Text("더 많은 설정은 설정 › 자막 스타일에 있습니다", style = MaterialTheme.typography.labelSmall, color = Lumen.TextMuted)
    }
}

@UnstableApi
@Composable
private fun SyncPanel(service: PlaybackService) {
    val delay by service.subtitleDelayMs.collectAsState()
    val external by service.externalSubtitle.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (external == null) {
            Text("싱크 조절은 외부 자막(파일·검색·AI)에 적용됩니다", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        }
        val label = when {
            delay == 0L -> "맞춤"
            delay > 0 -> "%.1f초 늦게".format(delay / 1000f)
            else -> "%.1f초 빨리".format(-delay / 1000f)
        }
        Text(label, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-1000L, -100L, 100L, 1000L).forEach { d ->
                Surface(
                    onClick = { service.subtitleDelayMs.value = delay + d },
                    color = Lumen.SurfaceHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text((if (d > 0) "+" else "") + "%.1f".format(d / 1000f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text("대사가 소리보다 먼저 나오면 +, 늦게 나오면 - 를 누르세요", style = MaterialTheme.typography.labelSmall, color = Lumen.TextMuted)
        TextButton(onClick = { service.subtitleDelayMs.value = 0 }) { Text("초기화") }
    }
}

@UnstableApi
@Composable
fun QueueSheet(service: PlaybackService, onDismiss: () -> Unit) {
    val queue by service.queueFlow.collectAsState()
    val current by service.current.collectAsState()
    SideSheet(onDismiss, "재생 대기열", "${queue.size}개") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(queue) { index, item ->
                val playing = current?.uri == item.uri && service.player.currentMediaItemIndex == index
                Surface(
                    onClick = { service.player.seekToDefaultPosition(index); service.player.play() },
                    color = if (playing) Lumen.PrimaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!playing) IconButton(onClick = { service.removeFromQueue(index) }) { Icon(Icons.Filled.Close, "대기열에서 빼기", tint = Lumen.TextMuted) }
                        else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).background(Lumen.Primary, CircleShape)) }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun AudioTrackSheet(state: PlayerUiState, onDismiss: () -> Unit) {
    val player = state.player
    val groups = state.tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    SideSheet(onDismiss, "오디오 트랙", "${groups.sumOf { it.length }}개") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { g ->
                (0 until g.length).forEach { i ->
                    val f = g.getTrackFormat(i)
                    val name = listOfNotNull(
                        f.label ?: f.language?.let { Locale(it).displayLanguage },
                        f.sampleMimeType?.substringAfter('/')?.uppercase(),
                        f.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                    ).joinToString(" · ")
                    SourceRow(name.ifBlank { "트랙 ${i + 1}" }, selected = g.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
            if (groups.isEmpty()) Text("오디오 트랙이 없습니다", color = Lumen.TextMuted)
        }
    }
}
