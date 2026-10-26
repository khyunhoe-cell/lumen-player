package com.lumen.player.subtitle.ai

import java.lang.Character.UnicodeScript
import java.util.Locale

/**
 * 휴대폰에서 LiteRT-LM으로 직접 돌리는 로컬 AI 번역 모델(.litertlm). 모델은 APK에 넣지 않고 사용자가 받는다.
 * Google 공식 변환본(litert-community, Apache-2.0)을 커밋 해시로 고정하고 크기·SHA-256을 확인한다.
 * 실시간 자막 번역 앱(LiveSubtitle)에서 쓰던 모델·설정을 그대로 가져왔다.
 */
object LocalLlmModels {
    data class Model(
        val id: String,
        val label: String,
        val note: String,
        val file: ModelFile,
        /** GPU로 먼저 시도할지. 실패하면 CPU로 실행한다 */
        val tryGpu: Boolean,
        /**
         * 모델 파일에 내장된 대화 템플릿(Conversation API)을 쓸지. false면 템플릿을 거치지 않고
         * ChatML을 직접 만들어 넣는다(Qwen 변환본의 템플릿은 LiteRT-LM과 맞지 않는다).
         */
        val useBundledTemplate: Boolean = false,
    )

    val GEMMA4_E2B = Model(
        id = "gemma4-e2b",
        label = "Gemma 4 E2B",
        note = "다국어 AI · 2.0GB · 추천",
        file = ModelFile(
            "gemma-4-E2B-it-gpu.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it-gpu.litertlm",
            2_008_432_640,
            "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
        ),
        tryGpu = true,
        // Google이 LiteRT-LM용으로 만든 파일이라 내장 템플릿을 그대로 쓴다
        useBundledTemplate = true,
    )

    val QWEN25 = Model(
        id = "qwen2.5-1.5b",
        label = "Qwen 2.5 1.5B",
        note = "범용 AI · 1.6GB · 가볍지만 일본어→한국어 품질 낮음",
        file = ModelFile(
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/" +
                "19edb84c69a0212f29a6ef17ba0d6f278b6a1614/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            1_597_931_520,
            "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9",
        ),
        tryGpu = true,
    )

    val all = listOf(GEMMA4_E2B, QWEN25)

    fun find(id: String) = all.firstOrNull { it.id == id } ?: GEMMA4_E2B
}

/** 로컬 AI 번역 요청 문구와 응답 정리. 작은 모델은 여러 줄 JSON을 잘 못 지키므로 한 줄씩 보낸다 */
object LocalLlmPrompt {
    const val CONTEXT_LINES = 4
    private val END_TOKENS = listOf("<|im_end|>", "<|endoftext|>", "<|im_start|>", "<end_of_turn>")

    fun englishName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    fun system(target: String): String {
        val name = englishName(target)
        return """
            You translate subtitles for a video into $name.
            The source text comes from automatic speech recognition of the video's audio, so it may contain misheard words, missing punctuation, or a sentence cut off mid-way. Use the earlier lines and common sense to infer what was actually said, and translate that meaning.
            Write the way professional $name subtitles read: natural, concise, and matching the speaker's tone (casual speech stays casual, polite speech stays polite).
            Reply with only the translated subtitle text. No quotes, notes, romanization, or explanations.
            Write the whole subtitle in $name, including people's names (transliterate them into $name script); never answer in another language.
            If the line is already in $name, reply with it unchanged.
        """.trimIndent()
    }

    fun user(text: String, source: String, glossary: String, context: List<Pair<String, String>>): String = buildString {
        if (glossary.isNotBlank()) append("Glossary (proper nouns and terms to keep consistent): ").append(glossary.trim()).append("\n\n")
        if (context.isNotEmpty()) {
            append("Earlier lines (original → translation), for context only:\n")
            for ((src, tr) in context.takeLast(CONTEXT_LINES)) append("- ").append(src).append(" → ").append(tr).append('\n')
            append('\n')
        }
        if (source.isNotBlank()) append("Source language: ").append(englishName(source)).append('\n')
        append("Translate this line:\n<line>").append(text).append("</line>")
    }

    /** 다른 언어 글자가 섞인 초안을 대상 언어로만 고쳐 쓰게 하는 요청(시스템 지시문, 요청) */
    fun fixDraft(text: String, source: String, target: String, draft: String): Pair<String, String> {
        val t = englishName(target)
        val s = if (source.isBlank()) "original" else englishName(source)
        return "You are a $t subtitle editor. You correct subtitles so they are written entirely in $t." to
            "Original ($s): $text\nDraft $t subtitle: $draft\n" +
            "The draft contains words that are not $t. Rewrite the draft entirely in $t, replacing those words " +
            "with the right $t words and keeping everything else. Reply with only the corrected subtitle."
    }

    /**
     * 모델에 그대로 넣을 Qwen 대화 형식(ChatML). 모델 파일에 내장된 템플릿은 LiteRT-LM과 맞지 않아
     * 템플릿을 거치지 않고(Session API) 이 문자열을 직접 넣는다.
     */
    fun chatMl(system: String, user: String): String =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    /** 자막 한 줄이면 충분하므로 끝 토큰이 나오거나, 첫 줄이 끝났거나, 너무 길어지면 생성을 멈춘다 */
    fun shouldStop(generated: String, maxChars: Int = 300): Boolean {
        if (END_TOKENS.any { generated.contains(it) }) return true
        val trimmed = generated.trimStart()
        if (trimmed.contains('\n') && trimmed.substringBefore('\n').isNotBlank()) return true
        return generated.length > maxChars
    }

    /** 작은 모델이 붙이는 끝 토큰·따옴표·태그·설명을 걷어 내고 첫 줄만 남긴다 */
    fun clean(raw: String): String {
        var s = raw
        for (t in END_TOKENS) s = s.substringBefore(t)
        s = s.trim().removePrefix("<line>").removeSuffix("</line>")
        s = s.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        val quotes = listOf("\"" to "\"", "“" to "”", "「" to "」", "'" to "'")
        for ((open, close) in quotes) {
            if (s.length >= 2 && s.startsWith(open) && s.endsWith(close)) s = s.substring(open.length, s.length - close.length)
        }
        return s.trim()
    }
}

/**
 * 번역 결과가 대상 언어의 글자로 쓰였는지 검사한다. 작은 온디바이스 모델은 짧은 문장에서
 * 다른 언어로 새는 일이 있다(예: "어느 부서ก็ได้ 재미있었네", "요리ちゃん").
 */
object ScriptCheck {
    private val expected: Map<String, Set<UnicodeScript>> = mapOf(
        "ko" to setOf(UnicodeScript.HANGUL),
        "ja" to setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN),
        "zh" to setOf(UnicodeScript.HAN),
        "ru" to setOf(UnicodeScript.CYRILLIC),
        "th" to setOf(UnicodeScript.THAI),
        "ar" to setOf(UnicodeScript.ARABIC),
        "hi" to setOf(UnicodeScript.DEVANAGARI),
    )
    private val cjk = setOf("ko", "ja", "zh")
    private val lowercaseLatinWord = Regex("(?<![A-Za-z])[a-z]{3,}(?![A-Za-z])")

    private fun normalize(code: String) = code.lowercase().substringBefore('-').substringBefore('_').takeIf { it.isNotBlank() }

    /**
     * 대상 언어 글자가 글자의 절반 이상이고, 대상 언어도 라틴 문자도 아닌 글자가 없으면 통과.
     * 라틴 문자는 고유명사로 흔히 섞이므로 비율로만 보되, 한중일 번역에 섞인 소문자 영어 단어는 샌 것으로 본다.
     */
    fun matchesTarget(text: String, target: String): Boolean {
        val lang = normalize(target) ?: return true
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        val scripts = letters.map { UnicodeScript.of(it.code) }
        val want = expected[lang] ?: setOf(UnicodeScript.LATIN)
        if (scripts.any { it !in want && it != UnicodeScript.LATIN }) return false
        if (lang in cjk && lowercaseLatinWord.containsMatchIn(text)) return false
        return scripts.count { it in want } * 2 >= scripts.size
    }

    /** 한국어 번역에 일본어 호칭이 그대로 남는 일이 잦다(「よりちゃん」 → "요리ちゃん") */
    private val jaHonorificsToKo = listOf(
        "ちゃん" to "짱", "さん" to "씨", "くん" to "군", "君" to "군", "さま" to "님", "様" to "님",
        "先輩" to "선배", "せんぱい" to "선배", "先生" to "선생님",
    )

    fun repair(text: String, target: String): String {
        if (normalize(target) != "ko") return text
        var s = text
        for ((ja, ko) in jaHonorificsToKo) s = s.replace(ja, ko)
        return s
    }
}
