package com.lumen.player.data.settings

import kotlinx.serialization.Serializable

@Serializable
enum class SubtitleFont(val label: String) {
    PLEX("IBM Plex Sans KR"),
    SANS("시스템 고딕"),
    SERIF("명조"),
    MONO("고정폭"),
    CUSTOM("내 글꼴 파일"),
}

@Serializable
enum class SubtitlePosition(val label: String) {
    INSIDE_BOTTOM("영상 안 아래"),
    INSIDE_TOP("영상 안 위"),
    BELOW_VIDEO("영상 밖 아래"),
    ABOVE_VIDEO("영상 밖 위"),
}

@Serializable
data class SubtitleStyle(
    val font: SubtitleFont = SubtitleFont.PLEX,
    val customFontPath: String? = null,
    val sizeSp: Float = 22f,
    val bold: Boolean = false,
    /** 줄 높이 배율. 1.0 = 글자 크기와 같음 */
    val lineSpacing: Float = 1.3f,
    val textColor: Long = 0xFFFFFFFF,
    val outlineWidth: Float = 2f,
    val outlineColor: Long = 0xFF000000,
    val shadowRadius: Float = 4f,
    val shadowOffset: Float = 2f,
    val shadowColor: Long = 0xCC000000,
    /** 0이면 배경 없음 */
    val backgroundColor: Long = 0x00000000,
    val backgroundPadding: Float = 6f,
    val position: SubtitlePosition = SubtitlePosition.INSIDE_BOTTOM,
    /** 영상 가장자리에서 떨어진 거리(dp). 재생 화면에서 끌어서 바꿀 수 있다. */
    val marginDp: Float = 24f,
    /** 영상 밖 위치일 때 확보할 줄 수 */
    val reservedLines: Int = 2,
)

@Serializable
enum class SttProvider(
    val label: String,
    val free: Boolean,
    val defaultModel: String,
    val note: String,
    val needsKey: Boolean = true,
) {
    GROQ("Groq", true, "whisper-large-v3", "무료 등급 제공 · Whisper large-v3 / turbo"),
    OPENAI("OpenAI", false, "whisper-1", "타임스탬프가 필요한 자막에는 whisper-1"),
    DEEPGRAM("Deepgram", false, "nova-3", "가입 크레딧 제공 · 빠른 처리"),
    ELEVENLABS("ElevenLabs", false, "scribe_v1", "단어 단위 타임스탬프 · 높은 정확도"),
    CUSTOM("내 서버", true, "whisper-large-v3", "whisper.cpp, faster-whisper 등 OpenAI 호환 서버", needsKey = false),
    ON_DEVICE(
        "기기 안 Whisper", true, WhisperModel.TURBO_Q5.id,
        "인터넷 없이 휴대폰에서 인식 · 모델을 한 번 받아 둠 · 클라우드보다 느림", needsKey = false,
    ),
    SENSE_VOICE(
        "기기 안 SenseVoice", true, "sense-voice-int8",
        "인터넷 없이 휴대폰에서 빠르게 인식 · 한국어·영어·일본어·중국어·광둥어만 · 모델 약 240MB", needsKey = false,
    ),
}

/**
 * 기기 안 인식에 쓰는 whisper.cpp ggml 모델. 크기는 내려받을 파일 크기(대략).
 * 속도는 Snapdragon 855급, 4스레드 기준 어림값이다.
 */
@Serializable
enum class WhisperModel(val id: String, val fileName: String, val sizeMb: Int, val note: String) {
    BASE("base", "ggml-base.bin", 148, "가장 빠름 · 한국어 정확도 낮음"),
    SMALL_Q5("small-q5_1", "ggml-small-q5_1.bin", 190, "빠름 · 보통 정확도"),
    MEDIUM_Q5("medium-q5_0", "ggml-medium-q5_0.bin", 539, "느림 · 높은 정확도"),
    TURBO_Q5("large-v3-turbo-q5_0", "ggml-large-v3-turbo-q5_0.bin", 574, "권장 · 가장 정확함 · 영상 길이보다 오래 걸릴 수 있음"),
    ;

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id } ?: TURBO_Q5
    }
}

/**
 * 자막 번역 엔진. ON_DEVICE는 Google ML Kit, LOCAL_LLM은 LiteRT-LM으로 기기 안에서 번역하고,
 * CLAUDE는 Anthropic API, 나머지는 OpenAI 호환 chat API를 쓴다.
 * keyOf가 있으면 같은 공급자의 음성 인식 키를 함께 쓰고, null이면 번역 전용 키를 따로 둔다.
 */
@Serializable
enum class TranslationProvider(
    val label: String,
    val defaultModel: String,
    val keyOf: SttProvider?,
    val note: String,
    val needsKey: Boolean = true,
) {
    // llama-3.3-70b-versatile은 2026-08-16에 무료·개발자 등급에서 종료됐다
    GROQ("Groq", "openai/gpt-oss-120b", SttProvider.GROQ, "무료 등급 제공"),
    OPENAI("OpenAI", "gpt-4.1-mini", SttProvider.OPENAI, "유료 · 번역 품질이 안정적"),
    DEEPSEEK("DeepSeek", "deepseek-flash", null, "유료 · 저렴함 · 중국어·일본어에 강함"),
    CLAUDE("Claude", "claude-opus-5-5", null, "유료 · 문맥과 말투를 가장 잘 살림 · Anthropic API 키 필요"),
    CUSTOM("내 서버", "qwen2.5:7b", SttProvider.CUSTOM, "Ollama, LM Studio 등 OpenAI 호환 서버", needsKey = false),
    LOCAL_LLM(
        "로컬 AI", "gemma4-e2b", null,
        "인터넷 없이 휴대폰에서 Gemma 4·Qwen 2.5로 번역 · 모델 1.6~2.0GB를 한 번 받음 · 한 줄씩 번역해 느리고 배터리를 많이 씀",
        needsKey = false,
    ),
    ON_DEVICE(
        "기기 안 번역", "ML Kit", SttProvider.CUSTOM,
        "인터넷 없이 휴대폰에서 번역 · 언어마다 약 30MB 모델을 처음 한 번 받음 · 문맥·용어집은 반영하지 않음",
        needsKey = false,
    ),
    ;

    /** 이 엔진의 키를 두는 자리 */
    val keySlot: KeySlot get() = keyOf?.let { KeySlot.Stt(it) } ?: KeySlot.Translation(this)
}

/** API 키를 두는 자리. 음성 인식 공급자의 키이거나, 번역만 하는 공급자(DeepSeek, Claude)의 키 */
sealed interface KeySlot {
    val label: String

    data class Stt(val provider: SttProvider) : KeySlot {
        override val label get() = provider.label
    }

    data class Translation(val provider: TranslationProvider) : KeySlot {
        override val label get() = if (provider == TranslationProvider.CLAUDE) "Anthropic" else provider.label
    }
}

@Serializable
data class AiSettings(
    val provider: SttProvider = SttProvider.GROQ,
    /** 공급자별 키. SecretBox로 암호화된 값을 담는다. */
    val sealedKeys: Map<SttProvider, String> = emptyMap(),
    /** 번역만 하는 공급자의 키(SecretBox로 암호화) */
    val sealedTranslationKeys: Map<TranslationProvider, String> = emptyMap(),
    val models: Map<SttProvider, String> = emptyMap(),
    val customEndpoint: String = "http://192.168.0.10:8080/v1",
    /** ISO-639-1. 빈 문자열이면 자동 감지 */
    val language: String = "",
    /** 예전 "영어로 번역" 설정. 이제는 targetLanguage = "en"과 같다 */
    val translateToEnglish: Boolean = false,
    /** 자막을 이 언어(ISO-639-1)로 번역한다. 빈 문자열이면 인식한 언어 그대로 */
    val targetLanguage: String = "",
    val translator: TranslationProvider = TranslationProvider.GROQ,
    val translationModels: Map<TranslationProvider, String> = emptyMap(),
    val translationEndpoint: String = "http://192.168.0.10:11434/v1",
    /** 고유명사·용어를 적어 두면 인식 정확도가 오른다 */
    val vocabularyPrompt: String = "",
    val maxCharsPerLine: Int = 32,
    /** 기기 안 인식에서 빔 서치(5)를 쓸지. 더 정확하지만 2~3배 느리다 */
    val onDeviceAccurate: Boolean = false,
) {
    fun model(p: SttProvider = provider) = models[p]?.takeIf { it.isNotBlank() } ?: p.defaultModel

    fun translationModel(p: TranslationProvider = translator) =
        translationModels[p]?.takeIf { it.isNotBlank() } ?: p.defaultModel

    /** 번역할 언어. 인식 언어와 같으면 번역하지 않는다. */
    fun target(): String = targetLanguage.ifBlank { if (translateToEnglish) "en" else "" }
        .takeIf { it != language }.orEmpty()
}

@Serializable
/** 키와 비밀번호는 SecretBox로 암호화된 값이다. */
data class OnlineSubtitleSettings(
    val languages: String = "ko,en",
    val openSubtitlesApiKey: String = "",
    val openSubtitlesUser: String = "",
    val openSubtitlesPassword: String = "",
    val subdlApiKey: String = "",
)

@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val resumePlayback: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val defaultSpeed: Float = 1f,
    val seekStepSec: Int = 10,
    val autoPip: Boolean = true,
    val autoLoadSubtitles: Boolean = true,
    val subtitle: SubtitleStyle = SubtitleStyle(),
    val ai: AiSettings = AiSettings(),
    val online: OnlineSubtitleSettings = OnlineSubtitleSettings(),
)
