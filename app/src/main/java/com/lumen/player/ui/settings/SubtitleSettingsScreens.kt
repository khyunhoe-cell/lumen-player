package com.lumen.player.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lumen.player.LumenApp
import com.lumen.player.data.settings.AiSettings
import com.lumen.player.data.settings.KeySlot
import com.lumen.player.data.settings.SecretBox
import com.lumen.player.data.settings.SttProvider
import com.lumen.player.data.settings.SubtitleFont
import com.lumen.player.data.settings.SubtitlePosition
import com.lumen.player.data.settings.SubtitleStyle
import com.lumen.player.data.settings.TranslationProvider
import com.lumen.player.data.settings.WhisperModel
import com.lumen.player.subtitle.ai.ModelState
import com.lumen.player.subtitle.ui.SubtitlePreview
import com.lumen.player.ui.components.ColorSwatches
import com.lumen.player.ui.components.LabeledSlider
import com.lumen.player.ui.components.LumenChip
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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
            if (ai.provider == SttProvider.ON_DEVICE) {
                OnDeviceWhisperSection(ai)
            } else if (ai.provider == SttProvider.SENSE_VOICE) {
                SenseVoiceSection(ai)
            } else {
                ApiKeyField(ai.provider)

                var model by remember(ai.provider, ai.models) { mutableStateOf(ai.model()) }
                OutlinedTextField(model, { model = it }, singleLine = true, label = { Text("모델") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    modelSuggestions(ai.provider).forEach { m -> LumenChip(m, model == m) { model = m } }
                }
                TextButton(onClick = { scope.launch { repo.updateAi { it.copy(models = it.models + (ai.provider to model.trim())) } } }) { Text("모델 저장") }
            }

            Label("인식 옵션")
            RecognitionOptions(ai)

            Label("화자 구분")
            SpeakerSection(ai)

            TranslationSection(ai)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 인식 언어로 고를 수 있는 주요 언어. Whisper·Groq·OpenAI·Deepgram·ElevenLabs가 모두 지원한다 */
private val recognitionLanguages = listOf("ko", "en", "ja", "zh", "es", "fr", "de", "ru", "pt", "it", "vi", "th", "id", "ar", "hi")

private fun languageLabel(code: String) = Locale(code).getDisplayLanguage(Locale.KOREAN).ifBlank { code }

private data class SaveStatus(val text: String, val ok: Boolean)

/**
 * 인식 언어(칩으로 고르고, 목록에 없으면 직접 입력)와 용어집.
 * 저장하면 저장소에서 다시 읽어 실제로 저장됐는지 확인하고 결과를 보여 준다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecognitionOptions(ai: AiSettings) {
    val repo = LumenApp.container.settings
    val scope = rememberCoroutineScope()
    var language by remember(ai.language) { mutableStateOf(ai.language) }
    var otherCode by remember(ai.language) { mutableStateOf(if (ai.language in recognitionLanguages) "" else ai.language) }
    var vocab by remember(ai.vocabularyPrompt) { mutableStateOf(ai.vocabularyPrompt) }
    var status by remember { mutableStateOf<SaveStatus?>(null) }
    val codeValid = otherCode.isBlank() || Regex("^[a-z]{2,3}$").matches(otherCode)
    val dirty = language != ai.language || vocab.trim() != ai.vocabularyPrompt

    Text("인식 언어", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LumenChip("자동 감지", language.isBlank()) { language = ""; otherCode = ""; status = null }
        recognitionLanguages.forEach { code ->
            LumenChip(languageLabel(code), language == code) { language = code; otherCode = ""; status = null }
        }
    }
    OutlinedTextField(
        otherCode,
        { v ->
            otherCode = v.lowercase().filter { it in 'a'..'z' }.take(3)
            status = null
            if (Regex("^[a-z]{2,3}$").matches(otherCode)) language = otherCode
        },
        singleLine = true,
        isError = !codeValid,
        label = { Text("목록에 없는 언어 코드 (예: nl, pl, tr)") },
        supportingText = {
            Text(
                when {
                    !codeValid -> "영문 소문자 2~3자로 입력하세요"
                    otherCode.isNotBlank() -> languageLabel(otherCode)
                    else -> "ISO 639-1 코드"
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "언어를 정해 주면 자동 감지보다 정확하고, 앞부분이 다른 언어로 잘못 인식되는 일이 줄어듭니다.",
        style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
    )
    OutlinedTextField(
        vocab, { vocab = it; status = null }, label = { Text("용어집 (인물 이름, 고유명사를 쉼표로)") },
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    LabeledSlider("한 줄 최대 글자 수", ai.maxCharsPerLine.toFloat(), 16f..48f, "${ai.maxCharsPerLine}자") { v ->
        scope.launch { repo.updateAi { it.copy(maxCharsPerLine = v.toInt()) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = codeValid && dirty, onClick = {
            val lang = language.trim()
            val words = vocab.trim()
            scope.launch {
                status = runCatching {
                    repo.updateAi { it.copy(language = lang, vocabularyPrompt = words) }
                    // 저장소에서 다시 읽어 실제로 들어갔는지 확인한다
                    val saved = repo.current().ai
                    check(saved.language == lang && saved.vocabularyPrompt == words) { "저장한 값이 다시 읽히지 않습니다" }
                    val terms = words.split(',', '\n').count { it.isNotBlank() }
                    SaveStatus(
                        "저장했습니다 · 인식 언어 ${if (lang.isBlank()) "자동 감지" else languageLabel(lang)}" +
                            if (terms > 0) " · 용어 ${terms}개" else "",
                        ok = true,
                    )
                }.getOrElse { SaveStatus("저장하지 못했습니다: ${it.message}", ok = false) }
            }
        }) { Text("인식 옵션 저장") }
        when {
            status != null -> Text(
                status!!.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (status!!.ok) Lumen.Primary else Lumen.Error,
                modifier = Modifier.weight(1f),
            )
            dirty -> Text(
                "저장하지 않은 변경이 있습니다",
                style = MaterialTheme.typography.bodySmall,
                color = Lumen.Amber,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val translationTargets = listOf("ko", "en", "ja", "zh", "es", "fr", "de", "vi", "th", "id")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationSection(ai: AiSettings) {
    val repo = LumenApp.container.settings
    val scope = rememberCoroutineScope()
    val target = ai.targetLanguage.ifBlank { if (ai.translateToEnglish) "en" else "" }
    fun setTarget(code: String) = scope.launch {
        repo.updateAi { it.copy(targetLanguage = code, translateToEnglish = false) }
    }

    Label("자막 번역")
    Text(
        "인식한 대사를 원하는 언어로 번역해 자막을 만듭니다. 시간은 원래 대사에 맞추고, 조각마다 번역이 끝나는 대로 화면에 보여 줍니다.",
        style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LumenChip("번역 안 함", target.isBlank()) { setTarget("") }
        translationTargets.forEach { code ->
            LumenChip(Locale.forLanguageTag(code).displayLanguage, target == code) { setTarget(code) }
        }
    }
    var otherCode by remember(target) { mutableStateOf(if (target in translationTargets) "" else target) }
    OutlinedTextField(
        otherCode, { otherCode = it.lowercase().take(5) }, singleLine = true,
        label = { Text("다른 언어 코드 (예: pt, ru, it)") }, modifier = Modifier.fillMaxWidth(),
    )
    TextButton(enabled = otherCode.isNotBlank(), onClick = { setTarget(otherCode.trim()) }) { Text("이 언어로 번역") }
    if (target.isBlank()) return
    if (target == ai.language) {
        Text("인식 언어와 번역 언어가 같아 번역하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
    }

    Label("번역 엔진")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TranslationProvider.entries.forEach { p ->
            LumenChip(p.label, ai.translator == p) { scope.launch { repo.updateAi { it.copy(translator = p) } } }
        }
    }
    Text(ai.translator.note, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
    if (ai.translator == TranslationProvider.CUSTOM) {
        var endpoint by remember(ai.translationEndpoint) { mutableStateOf(ai.translationEndpoint) }
        OutlinedTextField(
            endpoint, { endpoint = it }, singleLine = true, label = { Text("번역 서버 주소 (OpenAI 호환 /v1)") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { scope.launch { repo.updateAi { it.copy(translationEndpoint = endpoint.trim()) } } }) { Text("주소 저장") }
    }
    if (ai.translator == TranslationProvider.ON_DEVICE) {
        OnDeviceTranslationPrefetch(source = ai.language, target = target)
        return
    }
    if (ai.translator == TranslationProvider.LOCAL_LLM) {
        LocalLlmSection(ai)
        return
    }
    val slot = ai.translator.keySlot
    if (!ai.translator.needsKey) {
        // 내 서버는 키 없이도 쓸 수 있다
    } else if (slot is KeySlot.Stt && slot.provider == ai.provider) {
        Text("음성 인식에 넣은 ${slot.label} 키를 함께 씁니다.", style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
    } else {
        ApiKeyField(slot)
    }
    var model by remember(ai.translator, ai.translationModels) { mutableStateOf(ai.translationModel()) }
    OutlinedTextField(model, { model = it }, singleLine = true, label = { Text("번역 모델") }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        translationModelSuggestions(ai.translator).forEach { m -> LumenChip(m, model == m) { model = m } }
    }
    TextButton(onClick = {
        scope.launch { repo.updateAi { it.copy(translationModels = it.translationModels + (ai.translator to model.trim())) } }
    }) { Text("모델 저장") }
}

/** 인터넷이 없을 때도 쓰도록 번역 모델을 미리 받는다 */
@Composable
private fun OnDeviceTranslationPrefetch(source: String, target: String) {
    val scope = rememberCoroutineScope()
    var status by remember(source, target) { mutableStateOf<String?>(null) }
    if (source.isBlank()) {
        Text(
            "인식 언어를 자동 감지로 두면 첫 대사로 언어를 알아낸 뒤 모델을 받습니다. 인식 언어를 정하면 여기서 미리 받아 둘 수 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
        )
        return
    }
    TextButton(onClick = {
        status = "${Locale(source).displayLanguage} → ${Locale(target).displayLanguage} 모델을 받는 중"
        scope.launch {
            status = runCatching { com.lumen.player.subtitle.ai.MlKitTranslator.prefetch(source, target) }
                .fold({ "번역 모델을 받았습니다. 이제 인터넷 없이 번역합니다" }, { "모델을 받지 못했습니다: ${it.message}" })
        }
    }) { Text("번역 모델 미리 받기") }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted) }
}

/** 로컬 AI: 모델을 고르고 받고 지운다. 받는 중에는 진행률을 보여 준다 */
@Composable
private fun LocalLlmSection(ai: AiSettings) {
    val container = LumenApp.container
    val repo = container.settings
    val scope = rememberCoroutineScope()
    val states by container.localLlmModels.states.collectAsState()
    val selected = com.lumen.player.subtitle.ai.LocalLlmModels.find(ai.translationModel())
    Text(
        "모델을 한 번 받아 두면 인터넷 없이 휴대폰에서 번역합니다. 앞 대사 몇 줄을 문맥으로 보고 한 줄씩 번역해 클라우드보다 느리고, " +
            "배터리를 많이 쓰고 휴대폰이 뜨거워질 수 있습니다. 다른 언어 글자가 섞여 나온 줄은 기기 안 번역(ML Kit)으로 대신합니다. 와이파이에서 받으세요.",
        style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
    )
    com.lumen.player.subtitle.ai.LocalLlmModels.all.forEach { m ->
        val st = states[m.id] ?: ModelState(false)
        Surface(
            onClick = { scope.launch { repo.updateAi { it.copy(translationModels = it.translationModels + (TranslationProvider.LOCAL_LLM to m.id)) } } },
            color = if (selected == m) Lumen.PrimaryContainer else Lumen.Surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("${m.label} · 약 ${m.file.size / 1_000_000}MB", style = MaterialTheme.typography.titleSmall)
                Text(m.note, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
                val progress = st.progress
                when {
                    progress != null -> {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = Lumen.Primary,
                            trackColor = Lumen.Hairline,
                        )
                        Row {
                            Text("받는 중 ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
                            TextButton(onClick = { container.localLlmModels.cancel(m) }) { Text("취소") }
                        }
                    }
                    st.downloaded -> Row {
                        Text("받아 둠", style = MaterialTheme.typography.bodySmall, color = Lumen.Primary, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { container.localLlmModels.delete(m) }) { Text("지우기") }
                    }
                    else -> Row {
                        Text(st.error ?: "아직 받지 않음", style = MaterialTheme.typography.bodySmall, color = if (st.error != null) Lumen.Error else Lumen.TextMuted, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { container.localLlmModels.download(m) }) { Text("받기") }
                    }
                }
            }
        }
    }
}

private fun translationModelSuggestions(p: TranslationProvider) = when (p) {
    TranslationProvider.GROQ -> listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")
    TranslationProvider.OPENAI -> listOf("gpt-4.1-mini", "gpt-4.1")
    TranslationProvider.DEEPSEEK -> listOf("deepseek-flash", "deepseek-v4-pro")
    TranslationProvider.CLAUDE -> com.lumen.player.subtitle.ai.ClaudeModels.suggestions
    TranslationProvider.CUSTOM -> listOf("qwen2.5:7b", "gemma3:12b")
    TranslationProvider.LOCAL_LLM, TranslationProvider.ON_DEVICE -> emptyList()
}

private fun modelSuggestions(p: SttProvider) = when (p) {
    SttProvider.GROQ -> listOf("whisper-large-v3", "whisper-large-v3-turbo")
    SttProvider.OPENAI -> listOf("whisper-1")
    SttProvider.DEEPGRAM -> listOf("nova-3", "nova-2")
    SttProvider.ELEVENLABS -> listOf("scribe_v1")
    SttProvider.CUSTOM -> listOf("whisper-large-v3", "large-v3", "Systran/faster-whisper-large-v3")
    SttProvider.ON_DEVICE -> WhisperModel.entries.map { it.id }
    SttProvider.SENSE_VOICE -> emptyList()
}

/** 기기 안 SenseVoice: 모델 하나를 받고 지운다. 받는 중에는 진행률을 보여 준다 */
@Composable
private fun SenseVoiceSection(ai: AiSettings) {
    val models = LumenApp.container.senseVoiceModels
    val mb = com.lumen.player.subtitle.ai.SenseVoice.totalSize / 1_000_000
    Text(
        "모델(약 ${mb}MB)을 한 번 받아 두면 인터넷 없이 인식합니다. 말소리 구간만 골라 인식해 기기 안 Whisper보다 훨씬 빠릅니다. " +
            "한국어·영어·일본어·중국어·광둥어만 인식하며, 인식 언어를 비워 두면 언어를 자동으로 알아냅니다. 와이파이에서 받으세요.",
        style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
    )
    if (com.lumen.player.subtitle.ai.SenseVoice.modelLanguage(ai.language) == null) {
        Text(
            "지금 인식 언어(${languageLabel(ai.language)})는 SenseVoice가 인식하지 못합니다. 아래 인식 옵션에서 언어를 바꿔 주세요.",
            style = MaterialTheme.typography.bodySmall, color = Lumen.Error,
        )
    }
    ModelBundleCard("SenseVoice int8 + Silero VAD · 약 ${mb}MB", models)
}

/** 화자 구분: 켜면 화자가 바뀔 때 다음 자막으로 넘기고, 짧은 주고받기는 대시 두 줄로 묶는다 */
@Composable
private fun SpeakerSection(ai: AiSettings) {
    val container = LumenApp.container
    val scope = rememberCoroutineScope()
    ToggleRow(
        "화자 구분",
        "화자가 바뀌면 다음 자막으로 넘기고, 짧게 주고받는 대사는 대시(-) 두 줄로 보여 줍니다",
        ai.diarize,
    ) { v -> scope.launch { container.settings.updateAi { it.copy(diarize = v) } } }
    if (!ai.diarize) return
    if (ai.provider.diarizesItself) {
        Text(
            "${ai.provider.label}이(가) 인식하면서 화자를 함께 구분합니다. 따로 받을 모델이 없습니다.",
            style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
        )
        return
    }
    val models = container.diarizationModels
    val mb = models.totalSize / 1_000_000
    Text(
        "휴대폰 안에서 목소리로 화자를 나눕니다. 모델(약 ${mb}MB)을 한 번 받아 두면 인터넷 없이 쓸 수 있고, " +
            "10분 조각마다 몇십 초 정도 더 걸립니다. 목소리가 비슷하거나 대사가 겹치면 잘못 나눌 수 있습니다.",
        style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
    )
    ModelBundleCard("pyannote 구간 나누기 + CAM++ 화자 특징 · 약 ${mb}MB", models)
}

/** 여러 파일로 된 모델 묶음 하나를 받고 지운다. 받는 중에는 진행률을 보여 준다 */
@Composable
private fun ModelBundleCard(title: String, models: com.lumen.player.subtitle.ai.ModelBundleManager) {
    val st by models.state.collectAsState()
    Surface(color = Lumen.Surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val progress = st.progress
            when {
                progress != null -> {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = Lumen.Primary,
                        trackColor = Lumen.Hairline,
                    )
                    Row {
                        Text("받는 중 ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { models.cancel() }) { Text("취소") }
                    }
                }
                st.downloaded -> Row {
                    Text("받아 둠", style = MaterialTheme.typography.bodySmall, color = Lumen.Primary, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { models.delete() }) { Text("지우기") }
                }
                else -> Row {
                    Text(st.error ?: "아직 받지 않음", style = MaterialTheme.typography.bodySmall, color = if (st.error != null) Lumen.Error else Lumen.TextMuted, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { models.download() }) { Text("받기") }
                }
            }
        }
    }
}

/** 기기 안 Whisper: 모델을 고르고 받고 지운다. 받는 중에는 진행률을 보여 준다 */
@Composable
private fun OnDeviceWhisperSection(ai: AiSettings) {
    val container = LumenApp.container
    val repo = container.settings
    val scope = rememberCoroutineScope()
    val states by container.whisperModels.states.collectAsState()
    val selected = WhisperModel.byId(ai.model())
    val unavailable = remember { com.lumen.player.subtitle.ai.WhisperNative.unavailableReason() }

    if (unavailable != null) {
        Text(unavailable, style = MaterialTheme.typography.bodySmall, color = Lumen.Error)
        return
    }
    Text(
        "모델을 한 번 받아 두면 인터넷 없이 인식합니다. 큰 모델일수록 정확하지만 느리고, 영상 길이보다 오래 걸릴 수 있습니다. 와이파이에서 받으세요.",
        style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted,
    )
    WhisperModel.entries.forEach { m ->
        val st = states[m] ?: ModelState(false)
        Surface(
            onClick = { scope.launch { repo.updateAi { it.copy(models = it.models + (SttProvider.ON_DEVICE to m.id)) } } },
            color = if (selected == m) Lumen.PrimaryContainer else Lumen.Surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("${m.id} · 약 ${m.sizeMb}MB", style = MaterialTheme.typography.titleSmall)
                Text(m.note, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
                when {
                    st.progress != null -> {
                        LinearProgressIndicator(
                            progress = { st.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = Lumen.Primary,
                            trackColor = Lumen.Hairline,
                        )
                        Row {
                            Text("받는 중 ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
                            TextButton(onClick = { container.whisperModels.cancel(m) }) { Text("취소") }
                        }
                    }
                    st.downloaded -> Row {
                        Text("받아 둠", style = MaterialTheme.typography.bodySmall, color = Lumen.Primary, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { container.whisperModels.delete(m) }) { Text("지우기") }
                    }
                    else -> Row {
                        Text(st.error ?: "아직 받지 않음", style = MaterialTheme.typography.bodySmall, color = if (st.error != null) Lumen.Error else Lumen.TextMuted, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { container.whisperModels.download(m) }) { Text("받기") }
                    }
                }
            }
        }
    }
    ToggleRow("정확도 우선", "빔 서치로 더 정확하게 인식합니다 · 2~3배 느려짐", ai.onDeviceAccurate) { v ->
        scope.launch { repo.updateAi { it.copy(onDeviceAccurate = v) } }
    }
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
