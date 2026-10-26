package com.lumen.player.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("settings")

/** 설정 전체를 JSON 한 덩어리로 저장한다. 항목이 늘어도 마이그레이션이 필요 없다. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("app_settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map { prefs -> prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = context.dataStore.data.first()[key]
        ?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val now = prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
            prefs[key] = json.encodeToString(AppSettings.serializer(), transform(now))
        }
    }

    suspend fun updateSubtitle(transform: (SubtitleStyle) -> SubtitleStyle) =
        update { it.copy(subtitle = transform(it.subtitle)) }

    suspend fun updateAi(transform: (AiSettings) -> AiSettings) = update { it.copy(ai = transform(it.ai)) }

    suspend fun updateOnline(transform: (OnlineSubtitleSettings) -> OnlineSubtitleSettings) =
        update { it.copy(online = transform(it.online)) }

    fun apiKey(ai: AiSettings, provider: SttProvider = ai.provider): String =
        ai.sealedKeys[provider]?.let(SecretBox::open).orEmpty()

    fun apiKey(ai: AiSettings, slot: KeySlot): String = when (slot) {
        is KeySlot.Stt -> apiKey(ai, slot.provider)
        is KeySlot.Translation -> ai.sealedTranslationKeys[slot.provider]?.let(SecretBox::open).orEmpty()
    }

    /** 저장한 뒤 다시 읽어 같은 값이 나오는지까지 확인한다. */
    suspend fun setApiKey(provider: SttProvider, plain: String): Boolean = setApiKey(KeySlot.Stt(provider), plain)

    suspend fun setApiKey(slot: KeySlot, plain: String): Boolean {
        val key = plain.trim()
        val sealed = SecretBox.seal(key)
        updateAi {
            when (slot) {
                is KeySlot.Stt -> it.copy(sealedKeys = it.sealedKeys + (slot.provider to sealed))
                is KeySlot.Translation -> it.copy(sealedTranslationKeys = it.sealedTranslationKeys + (slot.provider to sealed))
            }
        }
        return apiKey(current().ai, slot) == key
    }
}
