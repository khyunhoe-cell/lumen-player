package com.lumen.player.subtitle.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

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

    private suspend fun fetch(spec: ModelFile, onProgress: (Long) -> Unit) {
        val part = File(dir, spec.name + ".part")
        if (part.length() > spec.size) part.delete()
        val already = part.length()
        val req = Request.Builder().url(spec.url)
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("${spec.name}을(를) 받지 못했습니다 (${resp.code})")
            val resumed = resp.code == 206
            val body = resp.body ?: throw IOException("빈 응답")
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
                        if (done - lastReport > (1 shl 20)) {
                            lastReport = done
                            onProgress(done)
                        }
                    }
                }
            }
        }
        if (part.length() != spec.size) {
            val len = part.length()
            part.delete()
            throw IOException("${spec.name}: 크기가 다릅니다 ($len / ${spec.size}). 다시 받아 주세요")
        }
        if (!sha256(part).equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${spec.name}: 체크섬이 맞지 않습니다. 다시 받아 주세요")
        }
        if (!part.renameTo(file(spec))) throw IOException("${spec.name}: 파일을 저장하지 못했습니다")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
