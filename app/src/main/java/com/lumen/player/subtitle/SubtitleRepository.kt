package com.lumen.player.subtitle

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.media.MediaTypes
import com.lumen.player.remote.RemoteRegistry
import com.lumen.player.remote.RemoteUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

class SubtitleRepository(private val context: Context, private val remotes: RemoteRegistry) {

    /** 다운로드·AI 생성 자막을 미디어별 폴더에 보관한다. */
    fun storageDir(mediaUri: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(mediaUri.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return File(context.filesDir, "subtitles/$hash").apply { mkdirs() }
    }

    fun saved(mediaUri: String): List<File> =
        storageDir(mediaUri).listFiles()?.filter { MediaTypes.isSubtitle(it.name) }?.sortedByDescending { it.lastModified() }.orEmpty()

    suspend fun saveSrt(mediaUri: String, name: String, cues: List<SubtitleCue>): File = withContext(Dispatchers.IO) {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        File(storageDir(mediaUri), "$safe.srt").apply { writeText(SubtitleParser.toSrt(cues)) }
    }

    suspend fun saveRaw(mediaUri: String, name: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        File(storageDir(mediaUri), safe).apply { writeBytes(bytes) }
    }

    suspend fun readBytes(source: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        when {
            source.startsWith("/") -> File(source).readBytes()
            uri.scheme == "file" -> File(uri.path!!).readBytes()
            RemoteUris.isRemote(uri) ->
                remotes.get(RemoteUris.serverId(uri)).open(RemoteUris.path(uri), 0).use { it.readBytes() }
            uri.scheme == "content" -> context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            uri.scheme == "http" || uri.scheme == "https" -> URL(source).openStream().use { it.readBytes() }
            else -> error("지원하지 않는 자막 위치: $source")
        }
    }

    suspend fun load(source: String, label: String? = null): Result<LoadedSubtitle> = runCatching {
        val name = label ?: displayName(source)
        val cues = withContext(Dispatchers.Default) { SubtitleParser.parse(readBytes(source), name) }
        require(cues.isNotEmpty()) { "자막 내용을 읽지 못했습니다: $name" }
        LoadedSubtitle(name, source, cues)
    }

    fun displayName(source: String): String {
        val uri = Uri.parse(source)
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            }
        }
        return Uri.decode(source.substringAfterLast('/'))
    }

    /**
     * 같은 이름의 자막을 찾는다. 원격 목록에서 미리 찾아 둔 후보가 있으면 그것을,
     * 로컬 파일은 실제 경로를 읽을 수 있을 때 같은 폴더를 본다. 선호 언어 태그(.ko.srt)를 먼저 고른다.
     */
    suspend fun findSidecar(entry: MediaEntry, preferredLanguages: String): String? = withContext(Dispatchers.IO) {
        val saved = saved(entry.uri).firstOrNull()
        if (saved != null) return@withContext saved.absolutePath
        val candidates = entry.sidecarSubtitles.ifEmpty { localSidecars(entry) }
        if (candidates.isEmpty()) return@withContext null
        val langs = preferredLanguages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        candidates.minByOrNull { candidate ->
            val name = Uri.decode(candidate.substringAfterLast('/')).lowercase()
            val langIdx = langs.indexOfFirst { l -> ".$l." in name || "_$l." in name || name.contains(".${l}-") }
            (if (langIdx >= 0) langIdx else langs.size) * 10 + if (name.endsWith(".smi")) 1 else 0
        }
    }

    private fun localSidecars(entry: MediaEntry): List<String> {
        val uri = entry.androidUri
        val path = when (uri.scheme) {
            "file" -> uri.path
            "content" -> runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            else -> null
        } ?: return emptyList()
        val file = File(path)
        val base = file.nameWithoutExtension.lowercase()
        // Android 11+에서는 '모든 파일 접근' 권한이 없으면 자막 파일이 보이지 않는다
        return file.parentFile?.listFiles()
            ?.filter { MediaTypes.isSubtitle(it.name) && it.name.lowercase().startsWith(base) }
            ?.map { it.absolutePath }
            .orEmpty()
    }

    companion object {
        /** 원격 폴더 목록에서 미디어 파일마다 자막 후보를 붙인다. */
        fun sidecarsFor(mediaName: String, siblings: List<Pair<String, String>>): List<String> {
            val base = mediaName.substringBeforeLast('.').lowercase()
            return siblings.filter { (name, _) -> MediaTypes.isSubtitle(name) && name.lowercase().startsWith(base) }
                .map { it.second }
        }
    }
}
