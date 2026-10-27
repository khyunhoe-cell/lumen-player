package com.lumen.player.subtitle.ai

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 한 화자가 말한 구간(조각 처음부터 센 밀리초) */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int)

/**
 * 기기 안 화자 분리에 쓰는 sherpa-onnx 모델.
 * 출처: k2-fsa/sherpa-onnx 공식 배포(pyannote segmentation 3.0은 Hugging Face csukuangfj 저장소를 커밋 해시로 고정,
 * 3D-Speaker CAM++는 GitHub speaker-recongition-models 릴리스). 둘 다 언어와 상관없이 목소리만 본다.
 */
object Diarization {
    const val DIR_NAME = "sherpa/diarization-pyannote3-campplus"

    val SEGMENTATION = ModelFile(
        "segmentation.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/" +
            "9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.onnx",
        5_992_913,
        "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079",
    )
    val EMBEDDING = ModelFile(
        "embedding.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        28_281_164,
        "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2",
    )
    val FILES = listOf(SEGMENTATION, EMBEDDING)
}

/**
 * sherpa-onnx OfflineSpeakerDiarization으로 조각 하나의 화자 구간을 구한다.
 * 화자 수는 모르므로 거리 기준(threshold)으로 묶는다. 조각마다 화자 번호는 새로 매겨지지만,
 * 자막을 나누는 데는 화자가 "바뀌었는지"만 쓰므로 조각 사이에서 번호가 달라도 괜찮다.
 */
class DeviceDiarizer(private val modelDir: File) {
    private var sd: OfflineSpeakerDiarization? = null

    private fun ensureLoaded(): OfflineSpeakerDiarization = sd ?: try {
        val threads = WhisperNative.defaultThreads()
        OfflineSpeakerDiarization(
            assetManager = null,
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(modelDir, Diarization.SEGMENTATION.name).absolutePath),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = File(modelDir, Diarization.EMBEDDING.name).absolutePath,
                    numThreads = threads,
                ),
                clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        ).also { sd = it }
    } catch (e: Throwable) {
        throw IOException("화자 분리 모델을 열지 못했습니다. 설정에서 모델을 지우고 다시 받아 주세요 (${e.message})")
    }

    suspend fun process(chunk: File): List<SpeakerTurn> = withContext(Dispatchers.Default) {
        val d = ensureLoaded()
        check(d.sampleRate() == SenseVoice.SAMPLE_RATE) { "화자 분리 모델의 표본율이 다릅니다 (${d.sampleRate()})" }
        d.process(OnDeviceWhisperClient.readWav(chunk)).map {
            SpeakerTurn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker)
        }
    }

    fun close() {
        sd?.release()
        sd = null
    }

    private companion object {
        /** 클수록 화자를 적게 나눈다. sherpa-onnx 예제의 기본값 */
        const val CLUSTER_THRESHOLD = 0.5f
    }
}

/**
 * 인식한 구간을 화자가 바뀌는 곳에서 나누고 구간마다 화자를 매긴다.
 * - 단어에 화자가 붙어 오면(Deepgram·ElevenLabs) 그대로 쓴다.
 * - 화자 구간([SpeakerTurn])이 있으면 단어나 구간에 가장 많이 겹치는 화자를 매긴다.
 * - 단어 시간이 없는 구간(SenseVoice, 기기 안 Whisper)은 화자가 충분히 길게 바뀔 때만 글자를 나눈다.
 */
object SpeakerSplitter {
    /** 이보다 짧게 끼어든 화자는 구간을 나누지 않는다 */
    private const val MIN_PIECE_MS = 700L

    fun apply(segments: List<Segment>, turns: List<SpeakerTurn>? = null): List<Segment> =
        segments.flatMap { split(it, turns) }

    private fun split(seg: Segment, turns: List<SpeakerTurn>?): List<Segment> {
        val words = if (turns.isNullOrEmpty()) seg.words else seg.words.map { w -> w.copy(speaker = speakerAt(turns, w.startMs, w.endMs) ?: w.speaker) }
        if (words.any { it.speaker != null }) return splitByWords(seg, words)
        if (turns.isNullOrEmpty()) return listOf(seg)
        return splitByTurns(seg, turns)
    }

    /** 가장 많이 겹치는 화자. 겹치는 구간이 없으면 가장 가까운 화자 */
    fun speakerAt(turns: List<SpeakerTurn>, start: Long, end: Long): Int? {
        if (turns.isEmpty()) return null
        val overlap = turns.groupBy { it.speaker }
            .mapValues { (_, ts) -> ts.sumOf { (minOf(end, it.endMs) - maxOf(start, it.startMs)).coerceAtLeast(0) } }
            .filterValues { it > 0 }
        if (overlap.isNotEmpty()) return overlap.maxBy { it.value }.key
        val mid = (start + end) / 2
        return turns.minBy { minOf(kotlin.math.abs(it.startMs - mid), kotlin.math.abs(it.endMs - mid)) }.speaker
    }

    private fun splitByWords(seg: Segment, words: List<Word>): List<Segment> {
        // 화자를 모르는 단어는 앞 단어의 화자를 따른다
        val groups = mutableListOf<MutableList<Word>>()
        var current: Int? = null
        for (w in words) {
            val sp = w.speaker ?: current
            if (groups.isEmpty() || (sp != null && current != null && sp != current)) groups += mutableListOf<Word>()
            groups.last() += w.copy(speaker = sp)
            if (sp != null) current = sp
        }
        if (groups.size == 1) return listOf(seg.copy(words = groups[0], speaker = groups[0].firstNotNullOfOrNull { it.speaker }))
        val texts = cutText(seg.text, groups)
        return groups.mapIndexed { i, g ->
            Segment(g.first().startMs, g.last().endMs, texts[i], g, g.firstNotNullOfOrNull { it.speaker })
        }.filter { it.text.isNotBlank() }
    }

    /**
     * 구간 글을 단어 묶음마다 나눈다. 원문의 띄어쓰기·문장 부호를 살리려고 단어를 원문에서 차례로 찾고,
     * 찾지 못하면 단어를 띄어 이어 붙인다.
     */
    internal fun cutText(text: String, groups: List<List<Word>>): List<String> {
        val cuts = mutableListOf<Int>()
        var pos = 0
        for (g in groups.dropLast(1)) {
            for (w in g) {
                val t = w.text.trim()
                if (t.isEmpty()) continue
                val idx = text.indexOf(t, pos)
                if (idx < 0) return groups.map { gr -> gr.joinToString(" ") { it.text.trim() }.trim() }
                pos = idx + t.length
            }
            // 단어 뒤에 붙은 문장 부호는 앞 화자 몫이다
            while (pos < text.length && !text[pos].isWhitespace() && !text[pos].isLetterOrDigit()) pos++
            cuts += pos
        }
        val bounds = listOf(0) + cuts + text.length
        return bounds.zipWithNext { a, b -> text.substring(a, b).trim() }
    }

    private fun splitByTurns(seg: Segment, turns: List<SpeakerTurn>): List<Segment> {
        // 구간 안의 화자 구간을 잘라 붙이고, 너무 짧은 조각은 이웃에 합친다
        val pieces = mutableListOf<SpeakerTurn>()
        turns.filter { it.endMs > seg.startMs && it.startMs < seg.endMs }.sortedBy { it.startMs }.forEach { t ->
            val p = SpeakerTurn(maxOf(t.startMs, seg.startMs), minOf(t.endMs, seg.endMs), t.speaker)
            val last = pieces.lastOrNull()
            if (last != null && last.speaker == p.speaker) pieces[pieces.lastIndex] = last.copy(endMs = maxOf(last.endMs, p.endMs))
            else pieces += p
        }
        val long = pieces.filter { it.endMs - it.startMs >= MIN_PIECE_MS }
        val merged = mutableListOf<SpeakerTurn>()
        long.forEach { p -> if (merged.lastOrNull()?.speaker == p.speaker) merged[merged.lastIndex] = merged.last().copy(endMs = p.endMs) else merged += p }
        if (merged.size < 2) return listOf(seg.copy(speaker = speakerAt(turns, seg.startMs, seg.endMs)))

        // 화자가 바뀌는 시각을 글자 위치로 옮기고, 가까운 문장 부호나 띄어쓰기에 맞춘다
        val text = seg.text
        val single = listOf(seg.copy(speaker = speakerAt(turns, seg.startMs, seg.endMs)))
        if (text.length < 4) return single
        val dur = (seg.endMs - seg.startMs).coerceAtLeast(1)
        val times = (1 until merged.size).map { i -> (merged[i - 1].endMs + merged[i].startMs) / 2 }
        val cuts = times.map { t -> snap(text, ((t - seg.startMs) * text.length / dur).toInt().coerceIn(1, text.length - 1)) }
        if (cuts.zipWithNext().any { (a, b) -> a >= b }) return single
        val texts = (listOf(0) + cuts + text.length).zipWithNext { a, b -> text.substring(a, b).trim() }
        if (texts.any { it.isEmpty() }) return single
        val bounds = listOf(seg.startMs) + times + seg.endMs
        return merged.mapIndexed { i, p -> Segment(bounds[i], bounds[i + 1], texts[i], emptyList(), p.speaker) }
    }

    /** 글자 위치 [at] 근처(글 길이의 25% 안)에서 문장 부호 > 쉼표 > 띄어쓰기 뒤를 고른다. 없으면 그대로 */
    internal fun snap(text: String, at: Int): Int {
        val reach = (text.length / 4).coerceAtLeast(2)
        for (marks in listOf(".!?。？！…", ",，、", " ")) {
            val best = text.indices.filter { text[it] in marks && kotlin.math.abs(it + 1 - at) <= reach && it + 1 in 1 until text.length }
                .minByOrNull { kotlin.math.abs(it + 1 - at) }
            if (best != null) return best + 1
        }
        // 띄어 쓰는 언어에서는 단어 가운데를 자르지 않는다
        if (' ' in text) return text.indices.filter { text[it] == ' ' }.minBy { kotlin.math.abs(it - at) } + 1
        return at
    }
}
