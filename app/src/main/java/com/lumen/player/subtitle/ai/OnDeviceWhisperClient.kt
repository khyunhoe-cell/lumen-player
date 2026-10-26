package com.lumen.player.subtitle.ai

import com.lumen.player.data.settings.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 기기 안 인식 진행률(조각 안에서 0..100)을 받고 싶으면 코루틴 컨텍스트에 넣는다 */
class SttProgressListener(val onProgress: (Int) -> Unit) : AbstractCoroutineContextElement(SttProgressListener) {
    companion object Key : CoroutineContext.Key<SttProgressListener>
}

/**
 * whisper.cpp로 휴대폰 안에서 인식한다. 모델은 작업마다 한 번 불러와 조각들에 재사용하고 close()에서 놓는다.
 * 취소하면 whisper의 abort 콜백으로 바로 멈춘다.
 */
class OnDeviceWhisperClient(
    private val modelFile: File,
    private val settings: AiSettings,
) : SttClient {
    private var handle = 0L

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        WhisperNative.unavailableReason()?.let { throw IllegalStateException(it) }
        handle = WhisperNative.nativeInit(modelFile.absolutePath)
        if (handle == 0L) throw IOException("Whisper 모델을 열지 못했습니다. 설정에서 모델을 지우고 다시 받아 주세요")
        return handle
    }

    override suspend fun transcribe(chunk: File, previousText: String): List<Segment> = coroutineScope {
        val pcm = withContext(Dispatchers.IO) { readWav(chunk) }
        val h = withContext(Dispatchers.Default) { ensureLoaded() }
        val progress = coroutineContext[SttProgressListener]
        var finished = false
        // 작업이 취소되면 네이티브 인식을 멈춘다
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished) WhisperNative.nativeAbort(h)
            }
        }
        val prompt = listOf(settings.vocabularyPrompt.trim(), previousText.takeLast(200)).filter { it.isNotEmpty() }.joinToString(" ")
        val raw = try {
            withContext(Dispatchers.Default) {
                WhisperNative.nativeTranscribe(
                    h, pcm, settings.language, prompt,
                    WhisperNative.defaultThreads(),
                    if (settings.onDeviceAccurate) 5 else 1,
                    progress?.let { p -> WhisperNative.ProgressListener { p.onProgress(it) } },
                )
            }
        } finally {
            finished = true
            watcher.cancel()
        }
        ensureActive()
        raw ?: throw IOException("기기 안 인식에 실패했습니다")
        parseSegments(raw.drop(1).map { String(it, Charsets.UTF_8) })
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        /** AudioExtractor가 만든 16kHz 모노 16bit WAV(헤더 44바이트)를 -1..1 실수로 읽는다 */
        fun readWav(file: File): FloatArray {
            val bytes = file.readBytes()
            val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(data.remaining()) { data.get(it) / 32768f }
        }

        /** "t0\tt1\ttext" 줄들을 Segment로 바꾸고, 빈 대사와 흔한 환각 문구를 거른다 */
        fun parseSegments(lines: List<String>): List<Segment> = lines.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val text = parts[2].replace('�', ' ').trim()
            Segment(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1].toLongOrNull() ?: return@mapNotNull null, text)
        }.filterNot { it.text.isBlank() || HallucinationFilter.isHallucination(it.text) }
    }
}
