package com.lumen.player.subtitle.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * 기기 안 SenseVoice 모델(SenseVoice int8, 토큰, Silero VAD)을 받아 앱 저장소에 둔다.
 * 끊기면 받은 데까지 이어 받고, 파일마다 크기와 SHA-256을 확인한다.
 */
class SenseVoiceModelManager(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    val dir = File(context.filesDir, SenseVoice.DIR_NAME)
    private var job: Job? = null

    private val _state = MutableStateFlow(ModelState(isDownloaded()))
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun file(spec: ModelFile) = File(dir, spec.name)
    fun isDownloaded() = SenseVoice.FILES.all { file(it).length() == it.size }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = ModelState(false, 0f)
            try {
                dir.mkdirs()
                val total = SenseVoice.totalSize
                var before = 0L
                for (spec in SenseVoice.FILES) {
                    if (file(spec).length() != spec.size) fetch(spec) { done -> _state.value = ModelState(false, (before + done).toFloat() / total) }
                    before += spec.size
                }
                _state.value = ModelState(true)
            } catch (e: CancellationException) {
                _state.value = ModelState(false)
                throw e
            } catch (e: Exception) {
                _state.value = ModelState(false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun delete() {
        cancel()
        dir.deleteRecursively()
        _state.value = ModelState(false)
    }

    private suspend fun fetch(spec: ModelFile, onProgress: (Long) -> Unit) = ModelFetcher.fetch(http, dir, spec, onProgress)
}
