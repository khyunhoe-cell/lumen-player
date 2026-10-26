package com.lumen.player.subtitle.ai

/** 내려받을 모델 파일. 받은 뒤 크기와 SHA-256을 확인한다 */
data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

/**
 * 기기 안 SenseVoice 인식에 쓰는 sherpa-onnx 모델(SenseVoice Small int8 + Silero VAD)과 순수 계산 부분.
 * 출처: k2-fsa/sherpa-onnx 공식 배포(Hugging Face csukuangfj 저장소는 커밋 해시로 고정, GitHub asr-models 릴리스).
 * 같은 저장소의 int8-2025-09-09 모델은 광둥어 전용 미세 조정본(WSYue)이라 모든 말을 광둥어로 받아 적으므로 쓰지 않는다.
 */
object SenseVoice {
    const val SAMPLE_RATE = 16_000
    const val DIR_NAME = "sherpa/sense-voice-int8-2024-07-17"

    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17" +
        "/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"

    val MODEL = ModelFile(
        "model.int8.onnx", "$HF/model.int8.onnx", 239_233_841,
        "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
    )
    val TOKENS = ModelFile(
        "tokens.txt", "$HF/tokens.txt", 315_894,
        "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
    )
    val VAD = ModelFile(
        "silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 643_854,
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    )
    val FILES = listOf(TOKENS, VAD, MODEL)
    val totalSize get() = FILES.sumOf { it.size }

    /** SenseVoice가 직접 인식하는 언어 */
    val supported = setOf("zh", "en", "ja", "ko", "yue")

    /**
     * 인식 언어 설정(ISO-639-1, 빈 값이면 자동)을 SenseVoice 언어 코드로 바꾼다.
     * 지원하지 않는 언어면 null.
     */
    fun modelLanguage(setting: String): String? {
        val code = setting.trim().lowercase().substringBefore('-').substringBefore('_')
        return when {
            code.isEmpty() || code == "auto" -> "auto"
            code in supported -> code
            else -> null
        }
    }

    /** VAD 구간(조각 처음부터 센 샘플 위치와 길이)을 조각 기준 밀리초 Segment로 바꾼다. 빈 대사면 null */
    fun toSegment(startSample: Int, sampleCount: Int, text: String): Segment? {
        val t = text.trim()
        if (t.isEmpty() || HallucinationFilter.isHallucination(t)) return null
        val start = startSample.toLong() * 1000 / SAMPLE_RATE
        val end = (startSample.toLong() + sampleCount) * 1000 / SAMPLE_RATE
        return Segment(start, end, t)
    }
}
