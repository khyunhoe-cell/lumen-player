package com.lumen.player.subtitle.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** 로컬 AI 번역 모델(.litertlm)을 모델마다 받고 지운다. 받는 중에는 진행률을 알린다 */
class LocalLlmModelManager(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val root = File(context.filesDir, "llm")
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow(LocalLlmModels.all.associate { it.id to ModelState(isDownloaded(it)) })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    fun dir(model: LocalLlmModels.Model) = File(root, model.id)
    fun file(model: LocalLlmModels.Model) = File(dir(model), model.file.name)
    fun isDownloaded(model: LocalLlmModels.Model) = file(model).length() == model.file.size

    private fun set(model: LocalLlmModels.Model, state: ModelState) = _states.update { it + (model.id to state) }

    fun download(model: LocalLlmModels.Model) {
        if (jobs[model.id]?.isActive == true) return
        jobs[model.id] = scope.launch(Dispatchers.IO) {
            set(model, ModelState(false, 0f))
            try {
                ModelFetcher.fetch(http, dir(model), model.file) { done ->
                    set(model, ModelState(false, done.toFloat() / model.file.size))
                }
                set(model, ModelState(true))
            } catch (e: CancellationException) {
                set(model, ModelState(false))
                throw e
            } catch (e: Exception) {
                set(model, ModelState(false, error = e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancel(model: LocalLlmModels.Model) {
        jobs.remove(model.id)?.cancel()
    }

    fun delete(model: LocalLlmModels.Model) {
        cancel(model)
        dir(model).deleteRecursively()
        set(model, ModelState(false))
    }
}
