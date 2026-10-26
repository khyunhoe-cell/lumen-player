package com.lumen.player.subtitle.ai

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Google ML Kit로 휴대폰 안에서 번역한다. 언어마다 약 30MB 모델을 처음 한 번 받고, 그 뒤로는 인터넷이 필요 없다.
 * 줄 단위로 번역하므로 줄 수는 항상 그대로다. 문맥과 용어집은 ML Kit가 받지 않아 쓰지 않는다.
 */
class MlKitTranslator(private val onStatus: (String) -> Unit = {}) : TranslationEngine {
    override var failedLines = 0
        private set
    override var lastError: String? = null
        private set

    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private var detectedSource: String? = null

    override suspend fun translate(
        lines: List<String>,
        target: String,
        source: String,
        glossary: String,
        context: List<String>,
    ): List<String> {
        if (lines.isEmpty()) return lines
        val tgt = mlKitLanguage(target) ?: throw IllegalStateException("기기 안 번역은 '$target' 언어를 지원하지 않습니다")
        val src = mlKitLanguage(source.ifBlank { detectSource(lines) })
            ?: throw IllegalStateException("기기 안 번역이 원래 언어를 알아내지 못했습니다. 설정 › AI 자막에서 인식 언어를 정해 주세요")
        if (src == tgt) return lines
        val translator = translators.getOrPut(src to tgt) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        }
        onStatus("기기 안 번역 모델을 준비하는 중 (${src} → ${tgt}, 처음 한 번 약 30MB)")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return lines.map { line ->
            if (line.isBlank()) return@map line
            runCatching { translator.translate(line).await() }.getOrElse {
                failedLines++
                lastError = it.message ?: it.javaClass.simpleName
                line
            }
        }
    }

    /** 인식 언어를 자동 감지로 둔 경우, 첫 묶음의 대사로 언어를 한 번 알아낸다 */
    private suspend fun detectSource(lines: List<String>): String {
        detectedSource?.let { return it }
        val sample = lines.joinToString(" ").take(1000)
        val client = LanguageIdentification.getClient()
        return try {
            client.identifyLanguage(sample).await().takeIf { it != "und" }.orEmpty().also { if (it.isNotEmpty()) detectedSource = it }
        } finally {
            client.close()
        }
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    companion object {
        /** "zh-Hant", "pt-BR" 같은 태그도 ML Kit 언어 코드로 맞춘다. 지원하지 않으면 null */
        fun mlKitLanguage(tag: String): String? {
            if (tag.isBlank()) return null
            return TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
        }

        /** 설정 화면의 "미리 받기"에 쓴다 */
        suspend fun prefetch(source: String, target: String) {
            val src = mlKitLanguage(source) ?: throw IllegalStateException("'$source' 언어는 기기 안 번역이 지원하지 않습니다")
            val tgt = mlKitLanguage(target) ?: throw IllegalStateException("'$target' 언어는 기기 안 번역이 지원하지 않습니다")
            val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
            try {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } finally {
                t.close()
            }
        }
    }
}
