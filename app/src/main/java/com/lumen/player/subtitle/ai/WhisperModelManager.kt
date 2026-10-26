package com.lumen.player.subtitle.ai

import android.content.Context
import com.lumen.player.data.settings.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class ModelState(
    val downloaded: Boolean,
    /** 받는 중이면 0..1 */
    val progress: Float? = null,
    val error: String? = null,
)

/**
 * 기기 안 인식 모델(ggml)을 Hugging Face의 whisper.cpp 저장소에서 받아 앱 저장소에 둔다.
 * 끊기면 받은 데까지 이어 받고, 다 받으면 파일 앞의 ggml 표식을 확인한다.
 */
class WhisperModelManager(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "whisper").apply { mkdirs() }
    private val jobs = mutableMapOf<WhisperModel, Job>()

    private val _states = MutableStateFlow(WhisperModel.entries.associateWith { ModelState(file(it).exists()) })
    val states: StateFlow<Map<WhisperModel, ModelState>> = _states.asStateFlow()

    fun file(model: WhisperModel) = File(dir, model.fileName)
    fun isDownloaded(model: WhisperModel) = file(model).exists()

    private fun set(model: WhisperModel, state: ModelState) = _states.update { it + (model to state) }

    fun download(model: WhisperModel) {
        if (jobs[model]?.isActive == true) return
        jobs[model] = scope.launch(Dispatchers.IO) {
            set(model, ModelState(false, 0f))
            try {
                fetch(model)
                set(model, ModelState(true))
            } catch (e: CancellationException) {
                set(model, ModelState(false))
                throw e
            } catch (e: Exception) {
                set(model, ModelState(false, error = e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancel(model: WhisperModel) {
        jobs.remove(model)?.cancel()
    }

    fun delete(model: WhisperModel) {
        cancel(model)
        file(model).delete()
        File(dir, model.fileName + ".part").delete()
        set(model, ModelState(false))
    }

    private suspend fun fetch(model: WhisperModel) {
        val part = File(dir, model.fileName + ".part")
        val already = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(model.url)
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("모델을 받지 못했습니다 (${resp.code})")
            val resumed = resp.code == 206
            val body = resp.body ?: throw IOException("빈 응답")
            val total = (if (resumed) already else 0L) + body.contentLength().coerceAtLeast(0)
            var done = if (resumed) already else 0L
            FileOutputStream(part, resumed).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > (1 shl 20)) {
                            lastReport = done
                            set(model, ModelState(false, done.toFloat() / total))
                        }
                    }
                }
            }
        }
        // ggml 파일은 "lmgg"(0x67676d6c, 리틀엔디언)로 시작한다. 오류 페이지를 모델로 착각하지 않게 확인한다
        val magic = part.inputStream().use { it.readNBytesCompat(4) }
        if (!magic.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))) {
            part.delete()
            throw IOException("받은 파일이 Whisper 모델이 아닙니다. 다시 받아 주세요")
        }
        if (!part.renameTo(file(model))) throw IOException("모델 파일을 저장하지 못했습니다")
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return b
    }
}
