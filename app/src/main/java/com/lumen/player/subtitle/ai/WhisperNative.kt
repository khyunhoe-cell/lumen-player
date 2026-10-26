package com.lumen.player.subtitle.ai

import android.os.Build
import java.io.File

/** whisper.cpp JNI. 라이브러리는 app/src/main/cpp에서 NDK로 빌드한다. */
object WhisperNative {
    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    /** 라이브러리를 못 불러왔으면 그 이유, 괜찮으면 null */
    private val loadError: String? by lazy {
        unsupportedCpuReason() ?: try {
            System.loadLibrary("lumen_whisper")
            null
        } catch (t: Throwable) {
            "기기 안 인식 엔진을 불러오지 못했습니다: ${t.message}"
        }
    }

    fun unavailableReason(): String? = loadError

    /**
     * arm64 빌드는 ARMv8.2 fp16 명령을 쓰므로, 그 명령이 없는 CPU(대략 2017년 이전 기기)에서는
     * 라이브러리를 불러오기 전에 막는다. 불러오면 그 자리에서 앱이 죽는다.
     */
    private fun unsupportedCpuReason(): String? {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return null
        val features = runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty()
        }.getOrDefault("")
        if (features.isEmpty()) return null
        return if ("fphp" in features && "asimdhp" in features) null
        else "이 휴대폰의 CPU는 기기 안 인식에 필요한 연산(ARMv8.2 fp16)을 지원하지 않습니다. 클라우드 엔진을 써 주세요"
    }

    /** 성능 코어 수에 맞춰 스레드를 정한다. 너무 많으면 작은 코어가 끼어 오히려 느려진다 */
    fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeAbort(handle: Long)
    external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        language: String,
        prompt: String,
        threads: Int,
        beamSize: Int,
        listener: ProgressListener?,
    ): Array<ByteArray>?

    external fun nativeSystemInfo(): String
}
