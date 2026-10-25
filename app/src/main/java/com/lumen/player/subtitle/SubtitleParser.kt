package com.lumen.player.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * SRT, WebVTT, SAMI(.smi), ASS/SSA를 직접 파싱한다.
 * 한국 자막은 CP949(EUC-KR)인 경우가 많아 인코딩을 먼저 추정한다.
 */
object SubtitleParser {

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            val fallback = runCatching { Charset.forName("MS949") }.getOrElse { Charset.forName("EUC-KR") }
            String(bytes, fallback)
        }
    }

    /** 확장자가 없거나 틀려도 내용으로 형식을 판단한다. */
    fun parse(bytes: ByteArray, fileName: String = ""): List<SubtitleCue> {
        val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val head = text.take(2048).lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val cues = when {
            "<sami" in head || ext == "smi" || ext == "sami" -> parseSami(text)
            "[script info]" in head || "[events]" in text.lowercase() || ext == "ass" || ext == "ssa" -> parseAss(text)
            head.trimStart().startsWith("webvtt") || ext == "vtt" -> parseSrtLike(text)
            else -> parseSrtLike(text)
        }
        return cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    private val timeRange = Regex(
        """(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})""",
    )

    private fun ms(h: String?, m: String, s: String, frac: String): Long {
        val hours = h?.trimEnd(':')?.toLongOrNull() ?: 0
        val f = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f
    }

    /** SRT와 WebVTT는 시간 줄 다음에 빈 줄까지가 대사라는 점이 같다. */
    private fun parseSrtLike(text: String): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val match = timeRange.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val g = match.groupValues
            val start = ms(g[1].ifEmpty { null }, g[2], g[3], g[4])
            val end = ms(g[5].ifEmpty { null }, g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (timeRange.containsMatchIn(lines[i])) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            out += SubtitleCue(start, end, cleanMarkup(body.toString()))
        }
        return out
    }

    private val syncTag = Regex("""<sync\s+start\s*=\s*["']?(\d+)["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val classAttr = Regex("""<p[^>]*class\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)

    /**
     * SAMI: <SYNC Start=ms> 블록이 이어지고, 다음 SYNC가 앞 대사의 끝이다.
     * 한 파일에 여러 언어(KRCC, ENCC)가 섞이면 처음 나온 언어 클래스만 쓴다.
     */
    private fun parseSami(text: String): List<SubtitleCue> {
        val bodyStart = text.indexOf("<body", ignoreCase = true)
        val body = if (bodyStart >= 0) text.substring(bodyStart) else text
        val syncs = syncTag.findAll(body).toList()
        if (syncs.isEmpty()) return emptyList()
        val primaryClass = classAttr.find(body)?.groupValues?.get(1)?.lowercase()
        data class Block(val start: Long, val cls: String?, val text: String)
        val blocks = syncs.mapIndexed { idx, m ->
            val from = m.range.last + 1
            val to = if (idx + 1 < syncs.size) syncs[idx + 1].range.first else body.length
            val chunk = body.substring(from, to)
            Block(m.groupValues[1].toLong(), classAttr.find(chunk)?.groupValues?.get(1)?.lowercase(), chunk)
        }.filter { primaryClass == null || it.cls == null || it.cls == primaryClass }
        val out = mutableListOf<SubtitleCue>()
        blocks.forEachIndexed { idx, b ->
            val content = cleanMarkup(
                b.text.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n"),
            ).replace(' ', ' ').trim()
            if (content.isEmpty()) return@forEachIndexed
            val end = blocks.getOrNull(idx + 1)?.start ?: (b.start + 5_000)
            out += SubtitleCue(b.start, end, content)
        }
        return out
    }

    private val assTime = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,3})""")

    private fun assMs(s: String): Long? {
        val m = assTime.find(s.trim()) ?: return null
        val (h, mi, se, cs) = m.destructured
        val frac = if (cs.length == 2) cs.toLong() * 10 else cs.padEnd(3, '0').toLong()
        return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + frac
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var inEvents = false
        val out = mutableListOf<SubtitleCue>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            when {
                line.startsWith("Format:", ignoreCase = true) ->
                    format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val fields = line.substringAfter(':').split(',', limit = format.size)
                    val start = assMs(fields.getOrNull(format.indexOf("start")) ?: "") ?: continue
                    val end = assMs(fields.getOrNull(format.indexOf("end")) ?: "") ?: continue
                    val body = fields.getOrNull(format.indexOf("text"))?.trim() ?: continue
                    val clean = body.replace(Regex("""\{[^}]*\}"""), "")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    out += SubtitleCue(start, end, clean.trim())
                }
            }
        }
        return out
    }

    private val tags = Regex("""<[^>]+>""")

    private fun cleanMarkup(s: String): String = tags.replace(s, "")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .lines().joinToString("\n") { it.trim() }.trim()

    fun toSrt(cues: List<SubtitleCue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val f = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, f)
    }
}
