package com.lumen.player.subtitle.ai

import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 모델 파일 하나를 [dir]에 받는다(기기 안 SenseVoice, 로컬 AI 번역 공용).
 * 끊기면 .part 파일에 받은 데까지 이어 받고, 다 받으면 크기와 SHA-256을 확인한 뒤 이름을 바꾼다.
 */
internal object ModelFetcher {
    suspend fun fetch(http: OkHttpClient, dir: File, spec: ModelFile, onProgress: (Long) -> Unit) {
        dir.mkdirs()
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
        if (!part.renameTo(File(dir, spec.name))) throw IOException("${spec.name}: 파일을 저장하지 못했습니다")
    }

    fun sha256(f: File): String {
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
