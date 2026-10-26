package com.lumen.player.subtitle.ai

import com.lumen.player.data.settings.AiSettings
import com.lumen.player.data.settings.SttProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 저장한 키가 실제로 통하는지 공급자에게 가벼운 요청(모델 목록, 계정 정보)을 보내 확인한다.
 * 성공하면 화면에 보여 줄 한 줄 설명을 돌려준다.
 */
class ApiKeyChecker(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun check(provider: SttProvider, key: String, ai: AiSettings): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider) {
                SttProvider.GROQ -> checkModels("https://api.groq.com/openai/v1", key, ai.model(provider))
                SttProvider.OPENAI -> checkModels("https://api.openai.com/v1", key, ai.model(provider))
                SttProvider.CUSTOM -> checkModels(ai.customEndpoint, key, ai.model(provider))
                SttProvider.DEEPGRAM -> {
                    get(Request.Builder().url("https://api.deepgram.com/v1/projects").header("Authorization", "Token $key"))
                    "키가 확인되었습니다"
                }
                SttProvider.ELEVENLABS -> {
                    get(Request.Builder().url("https://api.elevenlabs.io/v1/user").header("xi-api-key", key))
                    "키가 확인되었습니다"
                }
            }
        }
    }

    /** OpenAI 호환 /models. 고른 모델이 목록에 없으면 그 사실도 알려 준다 */
    private fun checkModels(baseUrl: String, key: String, model: String): String {
        val body = get(
            Request.Builder().url(baseUrl.trimEnd('/') + "/models")
                .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") },
        )
        val ids = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }.getOrDefault(emptyList())
        return when {
            ids.isEmpty() || model in ids -> "키가 확인되었습니다 · 모델 $model"
            else -> "키는 맞지만 모델 '$model'이(가) 목록에 없습니다. 사용할 수 있는 음성 모델: " +
                ids.filter { "whisper" in it || "transcribe" in it }.ifEmpty { ids.take(5) }.joinToString()
        }
    }

    private fun get(builder: Request.Builder): String {
        http.newCall(builder.get().build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return when {
                resp.isSuccessful -> text
                resp.code == 401 || resp.code == 403 -> throw IOException("키가 올바르지 않습니다 (${resp.code}). 복사할 때 앞뒤가 잘리지 않았는지 확인하세요")
                else -> throw IOException("확인 요청 실패 (${resp.code}): ${text.take(200)}")
            }
        }
    }
}
