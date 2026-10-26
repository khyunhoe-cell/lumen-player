package com.lumen.player.subtitle.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 로컬 AI 번역: LiteRT-LM으로 휴대폰에서 직접 Gemma 4 E2B·Qwen 2.5 1.5B를 돌린다. 무료, 인터넷 불필요.
 * 작은 모델은 여러 줄 JSON을 잘 지키지 못하므로 한 줄씩 앞 대사 몇 줄을 문맥으로 붙여 번역한다.
 * 결과에 다른 언어 글자가 섞이면 한 번 고쳐 쓰게 하고, 그래도 섞이면 [fallback](ML Kit)으로 그 줄만 번역한다.
 */
class LocalLlmTranslator(
    private val context: Context,
    private val model: LocalLlmModels.Model,
    private val modelFile: File,
    private val onStatus: (String) -> Unit,
    private val fallback: TranslationEngine? = null,
) : TranslationEngine {
    private var engine: Engine? = null
    private val history = ArrayDeque<Pair<String, String>>()

    override var failedLines = 0
        private set
    override var lastError: String? = null
        private set

    override suspend fun translate(
        lines: List<String>,
        target: String,
        source: String,
        glossary: String,
        context: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val eng = engine ?: load().also { engine = it }
        val system = LocalLlmPrompt.system(target)
        lines.mapIndexed { i, text ->
            coroutineContext.ensureActive()
            onStatus("${model.label}(으)로 번역하는 중 · ${i + 1}/${lines.size}줄")
            val out = runCatching { translateLine(eng, system, text, source, target, glossary) }
                .getOrElse {
                    Log.w(TAG, "${model.id} 번역 실패: $text", it)
                    lastError = it.message ?: it.javaClass.simpleName
                    null
                }
            val final = out ?: fallbackLine(text, target, source)
            if (final == null) {
                failedLines++
                text
            } else {
                history.addLast(text to final)
                while (history.size > LocalLlmPrompt.CONTEXT_LINES) history.removeFirst()
                final
            }
        }
    }

    /** 대상 언어로만 쓰인 번역을 돌려준다. 고쳐 써도 다른 글자가 섞이면 null */
    private suspend fun translateLine(eng: Engine, system: String, text: String, source: String, target: String, glossary: String): String? {
        val draft = ScriptCheck.repair(generate(eng, system, LocalLlmPrompt.user(text, source, glossary, history.toList())), target)
        if (draft.isNotEmpty() && ScriptCheck.matchesTarget(draft, target)) return draft
        Log.w(TAG, "${model.id} 대상 언어가 아닌 글자: $draft")
        val (fixSystem, fixUser) = LocalLlmPrompt.fixDraft(text, source, target, draft)
        val fixed = ScriptCheck.repair(generate(eng, fixSystem, fixUser), target)
        return fixed.takeIf { it.isNotEmpty() && ScriptCheck.matchesTarget(it, target) }
    }

    private suspend fun fallbackLine(text: String, target: String, source: String): String? {
        val fb = fallback ?: return null
        return runCatching { fb.translate(listOf(text), target, source).firstOrNull() }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != text }
    }

    /** 시스템 지시문과 요청 하나로 한 줄을 생성한다 */
    private fun generate(eng: Engine, system: String, user: String): String {
        // 번역은 정답에 가까운 한 가지 결과가 좋으므로 거의 탐욕적으로 고른다
        val sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1)
        val raw = if (model.useBundledTemplate) {
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = sampler,
                maxOutputToken = MAX_OUTPUT_TOKENS,
            )
            val reply = eng.createConversation(config).use { conv -> conv.sendMessage(user) }
            reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } else {
            eng.createSession(SessionConfig(samplerConfig = sampler)).use { session ->
                generateLine(session, LocalLlmPrompt.chatMl(system, user))
            }
        }
        return LocalLlmPrompt.clean(raw)
    }

    /** 템플릿을 거치지 않는 Session API로 프롬프트를 넣고, 자막 한 줄이 끝나면 생성을 멈춘다 */
    private fun generateLine(session: Session, prompt: String): String {
        val out = StringBuilder()
        val done = CountDownLatch(1)
        var error: Throwable? = null
        var stopped = false
        session.generateContentStream(listOf(InputData.Text(prompt)), object : ResponseCallback {
            override fun onNext(response: String) {
                synchronized(out) {
                    out.append(response)
                    if (!stopped && LocalLlmPrompt.shouldStop(out.toString())) {
                        stopped = true
                        session.cancelProcess()
                    }
                }
            }

            override fun onDone() = done.countDown()

            override fun onError(throwable: Throwable) {
                // 우리가 멈춘 경우의 취소 오류는 정상 종료로 본다
                if (!stopped) error = throwable
                done.countDown()
            }
        })
        if (!done.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            session.cancelProcess()
            throw IllegalStateException("${model.label} 응답이 ${TIMEOUT_SEC}초 안에 오지 않았습니다")
        }
        error?.let { throw it }
        return synchronized(out) { out.toString() }
    }

    private fun load(): Engine {
        require(modelFile.length() == model.file.size) {
            "${model.label} 모델이 없습니다. 설정 › AI 자막 › 자막 번역에서 모델을 먼저 받으세요"
        }
        onStatus("${model.label} 모델을 불러오는 중")
        val backends = if (model.tryGpu) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        var last: Throwable? = null
        for (backend in backends) {
            try {
                val eng = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = backend, cacheDir = context.cacheDir.path))
                eng.initialize()
                Log.i(TAG, "${model.id} 로드 완료 (${backend.name})")
                return eng
            } catch (e: Throwable) {
                Log.w(TAG, "${model.id} ${backend.name} 로드 실패", e)
                last = e
            }
        }
        throw IllegalStateException("${model.label} 모델을 불러오지 못했습니다: ${last?.message}", last)
    }

    override fun close() {
        engine?.close()
        engine = null
        fallback?.close()
    }

    companion object {
        private const val TAG = "LocalLlm"
        private const val TIMEOUT_SEC = 60L
        private const val MAX_OUTPUT_TOKENS = 128
    }
}
