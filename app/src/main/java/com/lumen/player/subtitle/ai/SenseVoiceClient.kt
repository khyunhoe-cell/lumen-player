package com.lumen.player.subtitle.ai

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * sherpa-onnx로 휴대폰 안에서 인식한다: Silero VAD로 조각을 발화 단위로 나누고 SenseVoice int8로 받아 적는다.
 * 발화마다 따로 인식하므로 Whisper보다 훨씬 빠르고 무음 구간에 문장을 지어내지 않는다.
 * 인식기는 작업마다 한 번 불러와 조각들에 재사용하고 close()에서 놓는다. 취소는 발화 사이에서 확인한다.
 */
class SenseVoiceClient(
    private val modelDir: File,
    /** SenseVoice 언어 코드("auto", "ko", "en" …) */
    private val language: String,
) : SttClient {
    private var recognizer: OfflineRecognizer? = null

    private fun ensureLoaded(): OfflineRecognizer = recognizer ?: try {
        OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SenseVoice.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(modelDir, SenseVoice.MODEL.name).absolutePath,
                        language = language,
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(modelDir, SenseVoice.TOKENS.name).absolutePath,
                    numThreads = WhisperNative.defaultThreads(),
                    provider = "cpu",
                    debug = false,
                ),
            ),
        ).also { recognizer = it }
    } catch (e: Throwable) {
        throw IOException("SenseVoice 모델을 열지 못했습니다. 설정에서 모델을 지우고 다시 받아 주세요 (${e.message})")
    }

    // VAD는 샘플 위치를 처음부터 세므로 조각마다 새로 만든다 (모델이 작아 금방 열린다)
    private fun createVad() = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = File(modelDir, SenseVoice.VAD.name).absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.4f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW_SIZE,
                // 자막 한 줄 길이에 맞춰 긴 발화는 10초에서 끊는다
                maxSpeechDuration = 10f,
            ),
            sampleRate = SenseVoice.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    override suspend fun transcribe(chunk: File, previousText: String): List<Segment> = withContext(Dispatchers.Default) {
        val pcm = OnDeviceWhisperClient.readWav(chunk)
        val rec = ensureLoaded()
        val progress = coroutineContext[SttProgressListener]
        val vad = createVad()
        val out = mutableListOf<Segment>()
        var lastPct = -1
        try {
            suspend fun drain() {
                while (!vad.empty()) {
                    coroutineContext.ensureActive()
                    val seg = vad.front()
                    vad.pop()
                    SenseVoice.toSegment(seg.start, seg.samples.size, decode(rec, seg.samples))?.let { out += it }
                }
            }
            var pos = 0
            while (pos + WINDOW_SIZE <= pcm.size) {
                vad.acceptWaveform(pcm.copyOfRange(pos, pos + WINDOW_SIZE))
                pos += WINDOW_SIZE
                drain()
                val pct = (pos.toLong() * 100 / pcm.size).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    progress?.onProgress?.invoke(pct)
                }
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        out
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SenseVoice.SAMPLE_RATE)
            rec.decode(stream)
            val r = rec.getResult(stream)
            return if (r.lang.contains("nospeech")) "" else r.text
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    private companion object {
        const val WINDOW_SIZE = 512
    }
}
