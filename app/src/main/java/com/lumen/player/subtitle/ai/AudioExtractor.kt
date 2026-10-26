package com.lumen.player.subtitle.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.lumen.player.remote.RemoteFileSystem
import com.lumen.player.remote.RemoteRegistry
import com.lumen.player.remote.RemoteUris
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

data class AudioChunk(val file: File, val startMs: Long, val durationMs: Long)

/**
 * 영상의 오디오 트랙을 16kHz 모노 16bit WAV 조각으로 만든다.
 * Whisper 계열 API의 25MB 제한을 넘지 않도록 약 10분마다, 가능하면 조용한 지점에서 자른다.
 */
class AudioExtractor(private val context: Context, private val remotes: RemoteRegistry) {

    suspend fun extract(
        mediaUri: String,
        outDir: File,
        onProgress: (Float) -> Unit,
        onChunk: suspend (AudioChunk) -> Unit,
        /** 트랙에 길이 정보가 없을 때 진행률 계산에 쓴다 */
        durationHintMs: Long = 0,
    ) {
        outDir.mkdirs()
        val extractor = MediaExtractor()
        val uri = Uri.parse(mediaUri)
        var remoteSource: RemoteMediaDataSource? = null
        if (RemoteUris.isRemote(uri)) {
            remoteSource = RemoteMediaDataSource(remotes.getBlocking(RemoteUris.serverId(uri)), RemoteUris.path(uri))
            extractor.setDataSource(remoteSource)
        } else {
            extractor.setDataSource(context, uri, null)
        }
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("오디오 트랙이 없습니다")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L)
                .takeIf { it > 0 } ?: (durationHintMs * 1000)
            val codec = runCatching { MediaCodec.createDecoderByType(mime) }
                .getOrElse { error("이 기기는 $mime 오디오를 해독하지 못합니다") }
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var resampler = Resampler(sampleRate, TARGET_RATE)
            val writer = ChunkWriter(outDir, onChunk)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    var progressed = false
                    // 입력 버퍼가 비어 있는 만큼 한꺼번에 채운다
                    while (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(0)
                        if (inIdx < 0) break
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                    // 나온 출력도 모두 꺼낸다. 아무 진척이 없을 때만 잠깐 기다린다
                    while (!outputDone) {
                        val outIdx = codec.dequeueOutputBuffer(info, if (progressed) 0 else 5_000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val of = codec.outputFormat
                                sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                    of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                                resampler = Resampler(sampleRate, TARGET_RATE)
                            }
                            outIdx >= 0 -> {
                                val out = codec.getOutputBuffer(outIdx)!!
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val mono = toMono(out.slice().order(ByteOrder.nativeOrder()), channels, floatPcm)
                                codec.releaseOutputBuffer(outIdx, false)
                                writer.write(resampler.process(mono))
                                if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                                progressed = true
                            }
                            else -> break // INFO_TRY_AGAIN_LATER
                        }
                    }
                }
                writer.finish()
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
            remoteSource?.close()
        }
    }

    private fun toMono(buf: ByteBuffer, channels: Int, isFloat: Boolean): FloatArray {
        val ch = channels.coerceAtLeast(1)
        return if (isFloat) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until ch) sum += fb.get(i * ch + c)
                sum / ch
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / ch
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until ch) sum += sb.get(i * ch + c) / 32768f
                sum / ch
            }
        }
    }

    /** 이동 평균으로 고역을 줄인 뒤 선형 보간으로 샘플레이트를 바꾼다. 음성 인식에는 충분하다. */
    private class Resampler(srcRate: Int, dstRate: Int) {
        private val step = srcRate.toDouble() / dstRate
        private val taps = if (step >= 2) step.toInt() else 1
        private val history = FloatArray(taps)
        private var historyPos = 0
        private var historySum = 0f
        private var t = 0.0
        private var prev = 0f

        fun process(input: FloatArray): FloatArray {
            val filtered = if (taps == 1) input else FloatArray(input.size) { i ->
                historySum += input[i] - history[historyPos]
                history[historyPos] = input[i]
                historyPos = (historyPos + 1) % taps
                historySum / taps
            }
            val n = filtered.size
            if (n == 0) return filtered
            val out = FloatArray(((n - t) / step).toInt() + 2)
            var count = 0
            while (t + 1 < n) {
                val idx = kotlin.math.floor(t).toInt()
                val frac = (t - idx).toFloat()
                val s0 = if (idx < 0) prev else filtered[idx]
                val s1 = filtered[idx + 1]
                out[count++] = s0 + (s1 - s0) * frac
                t += step
            }
            t -= n
            prev = filtered[n - 1]
            return if (count == out.size) out else out.copyOf(count)
        }
    }

    private class ChunkWriter(private val dir: File, private val onChunk: suspend (AudioChunk) -> Unit) {
        private var index = 0
        private var file: RandomAccessFile? = null
        private var current: File? = null
        private var samplesInChunk = 0L
        private var chunkStartSample = 0L
        private var energyAcc = 0.0
        private var energyCount = 0

        suspend fun write(samples: FloatArray) {
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                if (file == null) open()
                val v = (s.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                bytes.putShort(v)
                samplesInChunk++
                energyAcc += (s * s).toDouble()
                energyCount++
                if (energyCount == WINDOW) {
                    val rms = kotlin.math.sqrt(energyAcc / WINDOW)
                    energyAcc = 0.0
                    energyCount = 0
                    // 첫 조각은 짧게 잘라 첫 자막이 빨리 나오게 한다
                    val minLen = if (index == 1) FIRST_MIN_CHUNK else MIN_CHUNK
                    val maxLen = if (index == 1) FIRST_MAX_CHUNK else MAX_CHUNK
                    val quietCut = samplesInChunk >= minLen && rms < SILENCE_RMS
                    if (quietCut || samplesInChunk >= maxLen) {
                        file!!.write(bytes.array(), 0, bytes.position())
                        bytes.clear()
                        close()
                    }
                }
            }
            file?.write(bytes.array(), 0, bytes.position())
        }

        private fun open() {
            current = File(dir, "chunk_%03d.wav".format(index++))
            file = RandomAccessFile(current, "rw").apply {
                setLength(0)
                write(ByteArray(44))
            }
            samplesInChunk = 0
        }

        private suspend fun close() {
            val f = file ?: return
            val dataLen = samplesInChunk * 2
            f.seek(0)
            f.write(wavHeader(dataLen))
            f.close()
            file = null
            val chunk = AudioChunk(current!!, chunkStartSample * 1000 / TARGET_RATE, samplesInChunk * 1000 / TARGET_RATE)
            chunkStartSample += samplesInChunk
            onChunk(chunk)
        }

        suspend fun finish() = close()

        private fun wavHeader(dataLen: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(1)
            putInt(TARGET_RATE); putInt(TARGET_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen.toInt())
        }.array()
    }

    companion object {
        const val TARGET_RATE = 16_000
        private const val WINDOW = 1_600 // 100ms
        private const val FIRST_MIN_CHUNK = TARGET_RATE * 90L // 1분 30초
        private const val FIRST_MAX_CHUNK = TARGET_RATE * 120L // 2분
        private const val MIN_CHUNK = TARGET_RATE * 60L * 9 // 9분이 지나면 조용한 곳을 찾는다
        private const val MAX_CHUNK = TARGET_RATE * 60L * 10 // 10분이면 무조건 자른다 (약 19MB)
        private const val SILENCE_RMS = 0.01
    }
}

/** MediaExtractor가 SMB/FTP/WebDAV/NFS 파일을 임의 위치로 읽을 수 있게 한다. */
private class RemoteMediaDataSource(private val fs: RemoteFileSystem, private val path: String) : MediaDataSource() {
    private val total = fs.size(path)
    private var stream: InputStream? = null
    private var streamPos = -1L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (total in 0..position) return -1
        if (stream == null || streamPos != position) {
            stream?.close()
            stream = fs.open(path, position)
            streamPos = position
        }
        val n = stream!!.read(buffer, offset, size)
        if (n > 0) streamPos += n
        return n
    }

    override fun getSize(): Long = total

    @Synchronized
    override fun close() {
        stream?.close()
        stream = null
    }
}
