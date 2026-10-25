package com.lumen.player.subtitle.online

import android.content.Context
import android.net.Uri
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.media.MediaTypes
import com.lumen.player.data.settings.SecretBox
import com.lumen.player.data.settings.SettingsRepository
import com.lumen.player.remote.RemoteRegistry
import com.lumen.player.remote.RemoteUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

enum class SubtitleProvider(val label: String) { OPENSUBTITLES("OpenSubtitles"), SUBDL("SubDL") }

data class OnlineSubtitle(
    val provider: SubtitleProvider,
    /** OpenSubtitles는 file_id, SubDL은 zip 경로 */
    val downloadKey: String,
    val title: String,
    val language: String,
    val downloads: Int,
    /** 파일 해시가 일치하면 싱크가 맞을 가능성이 매우 높다 */
    val hashMatch: Boolean,
)

data class ParsedTitle(val query: String, val season: Int?, val episode: Int?, val year: Int?)

class OnlineSubtitleSearch(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val remotes: RemoteRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val userAgent = "Lumen v0.1"
    private var osToken: String? = null
    private var osBase = "https://api.opensubtitles.com/api/v1"

    fun parseTitle(fileName: String): ParsedTitle {
        var name = fileName.substringBeforeLast('.').replace(Regex("""[._]+"""), " ")
        name = name.replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
        val ep = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""").find(name)
        val year = Regex("""\b(19|20)\d{2}\b""").find(name)
        val cut = listOfNotNull(
            ep?.range?.first,
            year?.range?.first,
            Regex("""\b(480p|720p|1080p|2160p|4k|bluray|web-?dl|webrip|hdtv|x26[45]|h ?26[45]|hevc)\b""", RegexOption.IGNORE_CASE).find(name)?.range?.first,
        ).minOrNull()
        val query = (if (cut != null && cut > 0) name.substring(0, cut) else name).trim().replace(Regex("""\s+"""), " ")
        return ParsedTitle(
            query = query,
            season = ep?.groupValues?.get(1)?.toInt(),
            episode = ep?.groupValues?.get(2)?.toInt(),
            year = year?.value?.toInt(),
        )
    }

    suspend fun search(entry: MediaEntry, queryOverride: String? = null): Result<List<OnlineSubtitle>> = runCatching {
        val s = settings.current().online
        val parsed = parseTitle(entry.title).let { if (queryOverride != null) it.copy(query = queryOverride) else it }
        val osKey = SecretBox.open(s.openSubtitlesApiKey)
        val subdlKey = SecretBox.open(s.subdlApiKey)
        if (osKey.isBlank() && subdlKey.isBlank()) {
            throw IllegalStateException("설정 › 자막 검색에서 OpenSubtitles 또는 SubDL API 키를 넣어 주세요")
        }
        coroutineScope {
            val hash = async(Dispatchers.IO) { runCatching { movieHash(entry) }.getOrNull() }
            val os = if (osKey.isNotBlank()) async(Dispatchers.IO) { runCatching { openSubtitles(osKey, parsed, hash.await(), s.languages) } } else null
            val sd = if (subdlKey.isNotBlank()) async(Dispatchers.IO) { runCatching { subdl(subdlKey, parsed, s.languages) } } else null
            val results = listOfNotNull(os?.await(), sd?.await())
            if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
            val langs = s.languages.split(',').map { it.trim().lowercase() }
            results.flatMap { it.getOrDefault(emptyList()) }
                .sortedWith(
                    compareByDescending<OnlineSubtitle> { it.hashMatch }
                        .thenBy { langs.indexOf(it.language.lowercase()).let { i -> if (i < 0) 99 else i } }
                        .thenByDescending { it.downloads },
                )
        }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): JsonObject {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("자막 검색 실패 (${resp.code}): ${body.take(200)}")
            return json.parseToJsonElement(body).jsonObject
        }
    }

    private fun openSubtitles(apiKey: String, title: ParsedTitle, hash: String?, languages: String): List<OnlineSubtitle> {
        val url = "$osBase/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", title.query)
            .addQueryParameter("languages", languages.lowercase().replace(" ", ""))
            .apply {
                hash?.let { addQueryParameter("moviehash", it) }
                title.season?.let { addQueryParameter("season_number", it.toString()) }
                title.episode?.let { addQueryParameter("episode_number", it.toString()) }
                title.year?.let { if (title.season == null) addQueryParameter("year", it.toString()) }
            }.build().toString()
        val root = get(url, mapOf("Api-Key" to apiKey, "Accept" to "application/json"))
        return root["data"]?.jsonArray.orEmpty().mapNotNull { item ->
            val a = item.jsonObject["attributes"]?.jsonObject ?: return@mapNotNull null
            val file = a["files"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            OnlineSubtitle(
                provider = SubtitleProvider.OPENSUBTITLES,
                downloadKey = file["file_id"]?.jsonPrimitive?.longOrNull?.toString() ?: return@mapNotNull null,
                title = a["release"]?.jsonPrimitive?.contentOrNull ?: file["file_name"]?.jsonPrimitive?.contentOrNull ?: title.query,
                language = a["language"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                downloads = a["download_count"]?.jsonPrimitive?.intOrNull ?: 0,
                hashMatch = a["moviehash_match"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }
    }

    private fun subdl(apiKey: String, title: ParsedTitle, languages: String): List<OnlineSubtitle> {
        val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("film_name", title.query)
            .addQueryParameter("languages", languages.uppercase().replace(" ", ""))
            .addQueryParameter("subs_per_page", "30")
            .apply {
                title.season?.let { addQueryParameter("season_number", it.toString()) }
                title.episode?.let { addQueryParameter("episode_number", it.toString()) }
                title.year?.let { addQueryParameter("year", it.toString()) }
            }.build().toString()
        val root = get(url)
        return root["subtitles"]?.jsonArray.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            OnlineSubtitle(
                provider = SubtitleProvider.SUBDL,
                downloadKey = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                title = o["release_name"]?.jsonPrimitive?.contentOrNull ?: o["name"]?.jsonPrimitive?.contentOrNull ?: title.query,
                language = o["lang"]?.jsonPrimitive?.contentOrNull ?: o["language"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                downloads = 0,
                hashMatch = false,
            )
        }
    }

    /** 선택한 자막을 받아 원본 바이트와 파일 이름을 돌려준다. */
    suspend fun download(result: OnlineSubtitle): Result<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        runCatching {
            when (result.provider) {
                SubtitleProvider.OPENSUBTITLES -> downloadOpenSubtitles(result)
                SubtitleProvider.SUBDL -> downloadSubdl(result)
            }
        }
    }

    private suspend fun ensureLogin(apiKey: String) {
        if (osToken != null) return
        val s = settings.current().online
        val user = SecretBox.open(s.openSubtitlesUser)
        val pass = SecretBox.open(s.openSubtitlesPassword)
        if (user.isBlank() || pass.isBlank()) return
        val body = buildJsonObject { put("username", user); put("password", pass) }.toString()
        val req = Request.Builder().url("https://api.opensubtitles.com/api/v1/login")
            .header("Api-Key", apiKey).header("User-Agent", userAgent).header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val o = json.parseToJsonElement(resp.body!!.string()).jsonObject
            osToken = o["token"]?.jsonPrimitive?.contentOrNull
            o["base_url"]?.jsonPrimitive?.contentOrNull?.let { osBase = "https://$it/api/v1" }
        }
    }

    private suspend fun downloadOpenSubtitles(result: OnlineSubtitle): Pair<String, ByteArray> {
        val apiKey = SecretBox.open(settings.current().online.openSubtitlesApiKey)
        ensureLogin(apiKey)
        val body = buildJsonObject { put("file_id", result.downloadKey.toLong()) }.toString()
        val req = Request.Builder().url("$osBase/download")
            .header("Api-Key", apiKey).header("User-Agent", userAgent).header("Accept", "application/json")
            .apply { osToken?.let { header("Authorization", "Bearer $it") } }
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val (link, name) = http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 406) throw IOException("오늘 받을 수 있는 자막 수를 다 썼습니다. OpenSubtitles 계정을 넣으면 한도가 늘어납니다")
            if (!resp.isSuccessful) throw IOException("자막 다운로드 실패 (${resp.code})")
            val o = json.parseToJsonElement(text).jsonObject
            o["link"]!!.jsonPrimitive.content to (o["file_name"]?.jsonPrimitive?.contentOrNull ?: "${result.title}.srt")
        }
        val bytes = http.newCall(Request.Builder().url(link).header("User-Agent", userAgent).build()).execute().use {
            if (!it.isSuccessful) throw IOException("자막 파일을 받지 못했습니다 (${it.code})")
            it.body!!.bytes()
        }
        return name to bytes
    }

    private fun downloadSubdl(result: OnlineSubtitle): Pair<String, ByteArray> {
        val url = "https://dl.subdl.com" + result.downloadKey
        val zip = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use {
            if (!it.isSuccessful) throw IOException("자막 파일을 받지 못했습니다 (${it.code})")
            it.body!!.bytes()
        }
        ZipInputStream(zip.inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.forEach { e ->
                if (!e.isDirectory && MediaTypes.isSubtitle(e.name)) return e.name.substringAfterLast('/') to zin.readBytes()
            }
        }
        throw IOException("압축 파일 안에 자막이 없습니다")
    }

    /**
     * OpenSubtitles 해시: 파일 크기 + 앞뒤 64KB를 8바이트 리틀엔디언 정수로 더한 값.
     * 네트워크 파일도 앞뒤만 읽으므로 빠르다.
     */
    private fun movieHash(entry: MediaEntry): String? {
        val chunk = 65536
        val uri = Uri.parse(entry.uri)
        val (size, head, tail) = if (RemoteUris.isRemote(uri)) {
            val fs = remotes.getBlocking(RemoteUris.serverId(uri))
            val path = RemoteUris.path(uri)
            val total = fs.size(path)
            if (total < chunk * 2) return null
            Triple(
                total,
                fs.open(path, 0).use { it.readExactly(chunk) },
                fs.open(path, total - chunk).use { it.readExactly(chunk) },
            )
        } else {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
            afd.use {
                val total = it.length
                if (total < chunk * 2) return null
                val channel = java.io.FileInputStream(it.fileDescriptor).channel
                val h = ByteArray(chunk).also { b -> channel.read(ByteBuffer.wrap(b), it.startOffset) }
                val t = ByteArray(chunk).also { b -> channel.read(ByteBuffer.wrap(b), it.startOffset + total - chunk) }
                Triple(total, h, t)
            }
        }
        var hash = size
        for (block in listOf(head, tail)) {
            val lb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
            while (lb.hasRemaining()) hash += lb.get()
        }
        return "%016x".format(hash)
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf
    }
}
