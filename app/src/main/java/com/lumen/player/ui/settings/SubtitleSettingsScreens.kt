package com.lumen.player.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lumen.player.LumenApp
import com.lumen.player.data.settings.SecretBox
import com.lumen.player.data.settings.SttProvider
import com.lumen.player.data.settings.SubtitleFont
import com.lumen.player.data.settings.SubtitlePosition
import com.lumen.player.data.settings.SubtitleStyle
import com.lumen.player.subtitle.ui.SubtitlePreview
import com.lumen.player.ui.components.ColorSwatches
import com.lumen.player.ui.components.LabeledSlider
import com.lumen.player.ui.components.LumenChip
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val textColors = listOf(0xFFFFFFFF, 0xFFFFE9A8, 0xFFFFD400, 0xFFA8E8DE, 0xFF9AD0FF, 0xFFF5B3C6, 0xFFB8F28A)
private val darkColors = listOf(0xFF000000, 0xFF183641, 0xFF2B1D00, 0xFF3A0F1E, 0xFFFFFFFF)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStyleScreen(nav: NavController) {
    val repo = LumenApp.container.settings
    val style = repo.settings.collectAsState().value.subtitle
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun edit(t: (SubtitleStyle) -> SubtitleStyle) = scope.launch { repo.updateSubtitle(t) }

    // 고른 글꼴 파일을 앱 저장소로 복사해 둔다(원본 권한이 사라져도 쓸 수 있게)
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                val out = File(dir, "custom_${System.currentTimeMillis()}.ttf")
                context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                out
            }
            repo.updateSubtitle { it.copy(font = SubtitleFont.CUSTOM, customFontPath = file.absolutePath) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SubPageHeader(nav, "자막 스타일")
        // 미리보기는 고정하고 아래 설정만 스크롤한다
        SubtitlePreview(
            style,
            "그 여름, 우리는 끝을 몰랐다.\n두 번째 줄도 이렇게 보입니다",
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)),
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label("위치")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SubtitlePosition.entries.forEach { p -> LumenChip(p.label, style.position == p) { edit { it.copy(position = p) } } }
            }
            LabeledSlider("가장자리와의 거리", style.marginDp, 0f..160f, "${style.marginDp.toInt()}dp") { v -> edit { it.copy(marginDp = v) } }
            if (style.position == SubtitlePosition.BELOW_VIDEO || style.position == SubtitlePosition.ABOVE_VIDEO) {
                LabeledSlider("영상 밖에 비울 줄 수", style.reservedLines.toFloat(), 1f..4f, "${style.reservedLines}줄") { v ->
                    edit { it.copy(reservedLines = v.toInt().coerceIn(1, 4)) }
                }
            }

            Label("글꼴")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SubtitleFont.entries.filter { it != SubtitleFont.CUSTOM || style.customFontPath != null }.forEach { f ->
                    LumenChip(f.label, style.font == f) { edit { it.copy(font = f) } }
                }
                LumenChip("글꼴 파일 추가 (.ttf .otf)", false) { pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream")) }
                LumenChip("굵게", style.bold) { edit { it.copy(bold = !it.bold) } }
            }
            LabeledSlider("글자 크기", style.sizeSp, 12f..48f, "${style.sizeSp.toInt()}sp") { v -> edit { it.copy(sizeSp = v) } }
            LabeledSlider("줄 간격", style.lineSpacing, 1f..2.2f, "%.2f".format(style.lineSpacing)) { v -> edit { it.copy(lineSpacing = v) } }

            Label("글자 색")
            ColorSwatches(textColors, style.textColor) { c -> edit { it.copy(textColor = c) } }

            Label("외곽선")
            LabeledSlider("두께", style.outlineWidth, 0f..6f, "%.1fdp".format(style.outlineWidth)) { v -> edit { it.copy(outlineWidth = v) } }
            ColorSwatches(darkColors, style.outlineColor) { c -> edit { it.copy(outlineColor = c) } }

            Label("그림자")
            LabeledSlider("흐림", style.shadowRadius, 0f..16f, "%.0f".format(style.shadowRadius)) { v -> edit { it.copy(shadowRadius = v) } }
            LabeledSlider("거리", style.shadowOffset, 0f..8f, "%.0f".format(style.shadowOffset)) { v -> edit { it.copy(shadowOffset = v) } }
            ColorSwatches(darkColors.map { (it and 0xFFFFFF) or 0xCC000000 }, style.shadowColor) { c -> edit { it.copy(shadowColor = c) } }

            Label("배경 상자")
            val alpha = ((style.backgroundColor ushr 24) and 0xFF) / 255f
            LabeledSlider("불투명도", alpha, 0f..1f, "${(alpha * 100).toInt()}%") { v ->
                edit { it.copy(backgroundColor = ((v * 255).toLong() shl 24) or (it.backgroundColor and 0xFFFFFF)) }
            }
            ColorSwatches(darkColors, style.backgroundColor or 0xFF000000) { c ->
                edit { it.copy(backgroundColor = (it.backgroundColor and 0xFF000000).let { a -> if (a == 0L) 0x99000000 else a } or (c and 0xFFFFFF)) }
            }
            LabeledSlider("여백", style.backgroundPadding, 0f..16f, "${style.backgroundPadding.toInt()}dp") { v -> edit { it.copy(backgroundPadding = v) } }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { edit { SubtitleStyle() } }) { Text("기본값으로 되돌리기") }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(nav: NavController) {
    val repo = LumenApp.container.settings
    val ai = repo.settings.collectAsState().value.ai
    val scope = rememberCoroutineScope()
    var keyInput by remember(ai.provider) { mutableStateOf("") }
    val hasKey = repo.apiKey(ai).isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SubPageHeader(nav, "AI 자막 생성")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "영상의 음성을 받아 적어 자막을 만듭니다. 오디오는 10분 단위로 나누어 선택한 공급자에게 보내고, 만들어진 부분부터 바로 화면에 표시합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Lumen.TextMuted,
            )
            Label("음성 인식 엔진")
            SttProvider.entries.forEach { p ->
                Surface(
                    onClick = { scope.launch { repo.updateAi { it.copy(provider = p) } } },
                    color = if (ai.provider == p) Lumen.PrimaryContainer else Lumen.Surface,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(p.label + if (p.free) "  · 무료로 쓸 수 있음" else "  · 유료", style = MaterialTheme.typography.titleSmall)
                        Text("${p.note} · 기본 모델 ${p.defaultModel}", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
                    }
                }
            }

            Label("${ai.provider.label} 설정")
            if (ai.provider == SttProvider.CUSTOM) {
                var endpoint by remember(ai.customEndpoint) { mutableStateOf(ai.customEndpoint) }
                OutlinedTextField(
                    endpoint, { endpoint = it }, singleLine = true, label = { Text("서버 주소 (OpenAI 호환 /v1)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { scope.launch { repo.updateAi { it.copy(customEndpoint = endpoint.trim()) } } }) { Text("주소 저장") }
                Text(
                    "PC나 NAS에서 faster-whisper-server, whisper.cpp server 등을 돌리면 무료로 large-v3 모델을 쓸 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
                )
            }
            OutlinedTextField(
                keyInput, { keyInput = it },
                singleLine = true,
                label = { Text(if (hasKey) "API 키 (저장됨 · 바꿀 때만 입력)" else "API 키") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(enabled = keyInput.isNotBlank(), onClick = {
                scope.launch { repo.setApiKey(ai.provider, keyInput) }
                keyInput = ""
            }) { Text("키 저장") }

            var model by remember(ai.provider, ai.models) { mutableStateOf(ai.model()) }
            OutlinedTextField(model, { model = it }, singleLine = true, label = { Text("모델") }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                modelSuggestions(ai.provider).forEach { m -> LumenChip(m, model == m) { model = m } }
            }
            TextButton(onClick = { scope.launch { repo.updateAi { it.copy(models = it.models + (ai.provider to model.trim())) } } }) { Text("모델 저장") }

            Label("인식 옵션")
            var language by remember(ai.language) { mutableStateOf(ai.language) }
            OutlinedTextField(
                language, { language = it.lowercase().take(5) }, singleLine = true,
                label = { Text("언어 코드 (ko, en, ja… 비우면 자동 감지)") }, modifier = Modifier.fillMaxWidth(),
            )
            Text("언어를 정해 주면 자동 감지보다 정확하고, 앞부분이 다른 언어로 잘못 인식되는 일이 줄어듭니다.", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
            var vocab by remember(ai.vocabularyPrompt) { mutableStateOf(ai.vocabularyPrompt) }
            OutlinedTextField(
                vocab, { vocab = it }, label = { Text("용어집 (인물 이름, 고유명사를 쉼표로)") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            LabeledSlider("한 줄 최대 글자 수", ai.maxCharsPerLine.toFloat(), 16f..48f, "${ai.maxCharsPerLine}자") { v ->
                scope.launch { repo.updateAi { it.copy(maxCharsPerLine = v.toInt()) } }
            }
            ToggleRow("영어로 번역", "Whisper 번역 모드 (Groq·OpenAI·내 서버)", ai.translateToEnglish) { v ->
                scope.launch { repo.updateAi { it.copy(translateToEnglish = v) } }
            }
            TextButton(onClick = {
                scope.launch { repo.updateAi { it.copy(language = language.trim(), vocabularyPrompt = vocab.trim()) } }
            }) { Text("인식 옵션 저장") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun modelSuggestions(p: SttProvider) = when (p) {
    SttProvider.GROQ -> listOf("whisper-large-v3", "whisper-large-v3-turbo")
    SttProvider.OPENAI -> listOf("whisper-1")
    SttProvider.DEEPGRAM -> listOf("nova-3", "nova-2")
    SttProvider.ELEVENLABS -> listOf("scribe_v1")
    SttProvider.CUSTOM -> listOf("whisper-large-v3", "large-v3", "Systran/faster-whisper-large-v3")
}

@Composable
fun OnlineSubtitleSettingsScreen(nav: NavController) {
    val repo = LumenApp.container.settings
    val online = repo.settings.collectAsState().value.online
    val scope = rememberCoroutineScope()
    var languages by remember(online.languages) { mutableStateOf(online.languages) }
    var osKey by remember { mutableStateOf("") }
    var osUser by remember { mutableStateOf(SecretBox.open(online.openSubtitlesUser)) }
    var osPass by remember { mutableStateOf("") }
    var subdlKey by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SubPageHeader(nav, "온라인 자막 검색")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(languages, { languages = it }, singleLine = true, label = { Text("찾을 언어 (우선순위 순, 예: ko,en)") }, modifier = Modifier.fillMaxWidth())
            Label("OpenSubtitles")
            Text("opensubtitles.com › API consumers 에서 무료 API 키를 만들 수 있습니다. 계정을 넣으면 하루 다운로드 한도가 늘어납니다.", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
            SecretField(osKey, { osKey = it }, if (online.openSubtitlesApiKey.isNotBlank()) "API 키 (저장됨)" else "API 키")
            OutlinedTextField(osUser, { osUser = it }, singleLine = true, label = { Text("아이디 (선택)") }, modifier = Modifier.fillMaxWidth())
            SecretField(osPass, { osPass = it }, if (online.openSubtitlesPassword.isNotBlank()) "비밀번호 (저장됨)" else "비밀번호 (선택)")
            Label("SubDL")
            Text("subdl.com 에서 무료 API 키를 받을 수 있습니다. 한국어 자막이 많습니다.", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
            SecretField(subdlKey, { subdlKey = it }, if (online.subdlApiKey.isNotBlank()) "API 키 (저장됨)" else "API 키")
            TextButton(onClick = {
                scope.launch {
                    repo.updateOnline {
                        it.copy(
                            languages = languages.replace(" ", ""),
                            openSubtitlesApiKey = if (osKey.isNotBlank()) SecretBox.seal(osKey.trim()) else it.openSubtitlesApiKey,
                            openSubtitlesUser = SecretBox.seal(osUser.trim()),
                            openSubtitlesPassword = if (osPass.isNotBlank()) SecretBox.seal(osPass) else it.openSubtitlesPassword,
                            subdlApiKey = if (subdlKey.isNotBlank()) SecretBox.seal(subdlKey.trim()) else it.subdlApiKey,
                        )
                    }
                    osKey = ""; osPass = ""; subdlKey = ""
                }
            }) { Text("저장") }
        }
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, singleLine = true, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
