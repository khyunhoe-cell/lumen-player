package com.lumen.player.subtitle.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Claude(Anthropic Messages API) 번역 통로. 공식 anthropic-java SDK를 쓴다.
 * 자막 줄은 짧아 생각을 깊게 할 필요가 없으므로 effort를 low로 두고,
 * 안전 분류기가 거절하면 서버가 다른 모델로 다시 시도하게 한다(Opus 5.5, Sonnet 5.5).
 */
class ClaudeChatBackend(
    private val apiKey: String,
    /** 테스트에서 가짜 서버로 보낼 때만 쓴다 */
    private val baseUrl: String? = null,
) : ChatBackend {
    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).apply { baseUrl?.let { baseUrl(it) } }.build()
    }
    private var created = false

    override suspend fun complete(model: String, system: String, user: String, jsonMode: Boolean): String? =
        withContext(Dispatchers.IO) {
            created = true
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(system)
                .addUserMessage(user)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            if (ClaudeModels.supportsServerFallback(model)) {
                builder.addBeta(FALLBACK_BETA)
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            val response = try {
                client.beta().messages().create(builder.build())
            } catch (e: UnauthorizedException) {
                throw ApiKeyRejectedException("자막 번역: Anthropic API 키가 올바르지 않습니다 (401)")
            } catch (e: PermissionDeniedException) {
                throw ApiKeyRejectedException("자막 번역: 이 Anthropic 키로는 쓸 수 없습니다 (403)")
            } catch (e: NotFoundException) {
                throw ModelNotFoundException("자막 번역 모델을 쓸 수 없습니다 (404): ${e.message?.take(200)}")
            } catch (e: com.anthropic.errors.RateLimitException) {
                throw RateLimitException("자막 번역 사용 한도에 걸렸습니다. 잠시 뒤 다시 시도하세요")
            } catch (e: AnthropicServiceException) {
                throw IOException("자막 번역 요청 실패 (${e.statusCode()}): ${e.message?.take(300)}", e)
            }
            if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                throw IOException("Claude가 이 대사의 번역을 거절했습니다")
            }
            response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").takeIf { it.isNotBlank() }
        }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        created = true
        client.models().list().autoPager().map { it.id() }
    }

    override fun close() {
        if (created) client.close()
    }

    companion object {
        /** 40줄 묶음의 번역과 생각(thinking) 토큰이 함께 들어가므로 넉넉히 둔다 */
        private const val MAX_TOKENS = 16_000L
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}

/** 고를 수 있는 Claude 모델 */
object ClaudeModels {
    val suggestions = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")

    /** 서버 쪽 거절 대체(fallbacks: "default")를 받는 모델 */
    fun supportsServerFallback(id: String) = id == "claude-opus-5-5" || id == "claude-sonnet-5-5"
}
