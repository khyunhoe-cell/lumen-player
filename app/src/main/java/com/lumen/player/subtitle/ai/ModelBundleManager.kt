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

/** 기기 안 SenseVoice 모델(SenseVoice int8, 토큰, Silero VAD) */
class SenseVoiceModelManager(context: Context, http: OkHttpClient, scope: CoroutineScope) :
    ModelBundleManager(File(context.filesDir, SenseVoice.DIR_NAME), SenseVoice.FILES, http, scope)

/** 기기 안 화자 분리 모델(pyannote 구간 나누기 + CAM++ 화자 특징) */
class DiarizationModelManager(context: Context, http: OkHttpClient, scope: CoroutineScope) :
    ModelBundleManager(File(context.filesDir, Diarization.DIR_NAME), Diarization.FILES, http, scope)

/**
 * 여러 파일로 된 모델 묶음을 받아 앱 저장소의 [dir]에 둔다.
 * 끊기면 받은 데까지 이어 받고, 파일마다 크기와 SHA-256을 확인한다.
 */
open class ModelBundleManager(
    val dir: File,
    private val files: List<ModelFile>,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    val totalSize = files.sumOf { it.size }
    private var job: Job? = null

    private val _state = MutableStateFlow(ModelState(isDownloaded()))
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun file(spec: ModelFile) = File(dir, spec.name)
    fun isDownloaded() = files.all { file(it).length() == it.size }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = ModelState(false, 0f)
            try {
                dir.mkdirs()
                val total = totalSize
                var before = 0L
                for (spec in files) {
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
