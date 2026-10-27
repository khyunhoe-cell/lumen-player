package com.lumen.player.subtitle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.media.MediaTypes
import com.lumen.player.remote.RemoteRegistry
import com.lumen.player.remote.RemoteUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

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

    /**
     * 편집한 대사를 앱 저장소에 SRT로 저장하고 그 자막을 돌려준다. 앱 저장소의 자막이면 그 파일을 바꾸고,
     * 영상 옆이나 서버의 자막이면 앱 저장소에 사본을 만든다(다음에 열 때 이 사본을 먼저 불러온다).
     */
    suspend fun saveEdited(mediaUri: String, subtitle: LoadedSubtitle, cues: List<SubtitleCue>): LoadedSubtitle = withContext(Dispatchers.IO) {
        val dir = storageDir(mediaUri)
        val old = File(subtitle.source).takeIf { subtitle.source.startsWith(dir.absolutePath) }
        val base = (old?.name ?: displayName(subtitle.source)).substringBeforeLast('.')
        val file = saveSrt(mediaUri, base, cues)
        if (old != null && old.absolutePath != file.absolutePath) old.delete()
        subtitle.copy(source = file.absolutePath, cues = cues)
    }

    /** 자막을 영상 옆에 저장할 곳 */
    sealed interface SaveLocation {
        /** 사용자에게 보여 줄 폴더 이름 */
        val label: String

        data class Remote(val serverId: Long, val dir: String, override val label: String) : SaveLocation

        /** 접근을 허락받은 SAF 폴더(트리) 안의 폴더 */
        data class Tree(val tree: Uri, val folderId: String, override val label: String) : SaveLocation

        /** 휴대폰 폴더인데 아직 쓰기 허락을 받지 않았다. [initialUri]로 폴더 선택 화면을 연다 */
        data class NeedsAccess(val initialUri: Uri?, override val label: String) : SaveLocation

        data class Unsupported(val reason: String) : SaveLocation {
            override val label get() = ""
        }
    }

    /** 영상이 있는 폴더를 찾는다. 휴대폰 영상은 SAF로 그 폴더(또는 상위 폴더)의 쓰기 허락이 있어야 한다 */
    suspend fun saveLocation(entry: MediaEntry): SaveLocation = withContext(Dispatchers.IO) {
        val uri = entry.androidUri
        val notFound = SaveLocation.Unsupported("이 영상의 폴더를 찾지 못했습니다")
        when {
            RemoteUris.isRemote(uri) -> {
                val id = RemoteUris.serverId(uri)
                val dir = remotePathParent(RemoteUris.path(uri))
                val server = remotes.server(id)?.name.orEmpty()
                SaveLocation.Remote(id, dir, "$server $dir".trim())
            }
            uri.scheme == "content" && isTreeDocument(uri) -> {
                // 문서 제공자(클라우드 앱 등) 폴더를 서버로 추가해 연 영상
                val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
                val path = runCatching { DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path }.getOrNull()
                val parent = path?.getOrNull(path.size - 2) ?: return@withContext notFound
                val label = parent.substringAfter(':')
                if (canWrite(tree)) SaveLocation.Tree(tree, parent, label)
                else SaveLocation.NeedsAccess(DocumentsContract.buildDocumentUri(uri.authority, parent), label)
            }
            uri.scheme == "content" || uri.scheme == "file" -> {
                val dir = localPath(entry)?.let { File(it).parent } ?: return@withContext notFound
                val folderId = externalStorageDocId(dir)
                    ?: return@withContext SaveLocation.Unsupported("이 저장 위치에는 자막을 저장할 수 없습니다")
                val label = folderId.substringAfter(':').ifEmpty { "내장 저장공간" }
                val tree = grantedTreeFor(folderId)
                if (tree != null) SaveLocation.Tree(tree, folderId, label)
                else SaveLocation.NeedsAccess(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, folderId), label)
            }
            else -> SaveLocation.Unsupported("이 영상의 위치에는 자막을 저장할 수 없습니다")
        }
    }

    /** 폴더 선택 화면에서 고른 폴더로 저장 위치를 정한다. 영상 폴더나 그 상위를 골랐으면 영상 폴더에, 아니면 고른 폴더에 저장한다 */
    fun locationFromPickedTree(tree: Uri, wanted: SaveLocation.NeedsAccess): SaveLocation.Tree {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val wantedId = wanted.initialUri?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() }
        return if (wantedId != null && covers(treeId, wantedId)) {
            SaveLocation.Tree(tree, wantedId, wanted.label)
        } else {
            SaveLocation.Tree(tree, treeId, treeId.substringAfter(':').ifEmpty { "선택한 폴더" })
        }
    }

    /** 저장 위치에 이미 있는 파일 이름들(소문자) */
    suspend fun existingNames(location: SaveLocation): Set<String> = withContext(Dispatchers.IO) {
        when (location) {
            is SaveLocation.Remote -> remotes.get(location.serverId).list(location.dir).map { it.name.lowercase() }.toSet()
            is SaveLocation.Tree -> treeChildren(location).keys
            else -> emptySet()
        }
    }

    /** [name]으로 저장한다. 같은 이름이 있으면 덮어쓴다(덮어쓸지는 화면에서 미리 묻는다) */
    suspend fun writeTo(location: SaveLocation, name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        when (location) {
            is SaveLocation.Remote -> remotes.get(location.serverId).write(joinRemote(location.dir, name), bytes)
            is SaveLocation.Tree -> {
                val resolver = context.contentResolver
                val existing = treeChildren(location)[name.lowercase()]
                val doc = existing?.let { DocumentsContract.buildDocumentUriUsingTree(location.tree, it) }
                    ?: DocumentsContract.createDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(location.tree, location.folderId),
                        // 형식을 정하면 제공자가 확장자를 바꾸거나 덧붙일 수 있어 이름을 그대로 쓰게 한다
                        "application/octet-stream",
                        name,
                    )
                    ?: throw IOException("파일을 만들지 못했습니다")
                (resolver.openOutputStream(doc, "wt") ?: throw IOException("파일을 열지 못했습니다")).use { it.write(bytes) }
            }
            is SaveLocation.NeedsAccess -> throw IOException("먼저 폴더 접근을 허락해 주세요")
            is SaveLocation.Unsupported -> throw IOException(location.reason)
        }
    }

    /** 폴더 안 파일들: 소문자 이름 → 문서 ID */
    private fun treeChildren(location: SaveLocation.Tree): Map<String, String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(location.tree, location.folderId)
        val out = mutableMapOf<String, String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) out[c.getString(1).orEmpty().lowercase()] = c.getString(0) }
        return out
    }

    private fun isTreeDocument(uri: Uri) = uri.pathSegments.firstOrNull() == "tree"

    private fun canWrite(tree: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** 쓰기 허락을 받은 내장 저장공간 트리 중 [folderId]를 포함하는 가장 가까운 것 */
    private fun grantedTreeFor(folderId: String): Uri? =
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == EXTERNAL_STORAGE && isTreeDocument(it.uri) }
            .map { it.uri }
            .filter { covers(DocumentsContract.getTreeDocumentId(it), folderId) }
            .maxByOrNull { DocumentsContract.getTreeDocumentId(it).length }

    private fun localPath(entry: MediaEntry): String? {
        val uri = entry.androidUri
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            else -> null
        }
    }

    private fun localSidecars(entry: MediaEntry): List<String> {
        val path = localPath(entry) ?: return emptyList()
        val file = File(path)
        val base = file.nameWithoutExtension.lowercase()
        // Android 11+에서는 '모든 파일 접근' 권한이 없으면 자막 파일이 보이지 않는다
        val direct = file.parentFile?.listFiles()
            ?.filter { MediaTypes.isSubtitle(it.name) && it.name.lowercase().startsWith(base) }
            ?.map { it.absolutePath }
            .orEmpty()
        if (direct.isNotEmpty()) return direct
        // 자막을 영상 옆에 저장하려고 폴더 접근을 허락받았으면 그 권한으로 찾는다
        val folderId = file.parent?.let { externalStorageDocId(it) } ?: return emptyList()
        val tree = grantedTreeFor(folderId) ?: return emptyList()
        return runCatching {
            treeChildren(SaveLocation.Tree(tree, folderId, ""))
                .filterKeys { MediaTypes.isSubtitle(it) && it.startsWith(base) }
                .values.map { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

        /** 원격 폴더 목록에서 미디어 파일마다 자막 후보를 붙인다. */
        fun sidecarsFor(mediaName: String, siblings: List<Pair<String, String>>): List<String> {
            val base = mediaName.substringBeforeLast('.').lowercase()
            return siblings.filter { (name, _) -> MediaTypes.isSubtitle(name) && name.lowercase().startsWith(base) }
                .map { it.second }
        }

        /** "/storage/emulated/0/Movies/a" → "primary:Movies/a", "/storage/1234-ABCD/x" → "1234-ABCD:x" */
        fun externalStorageDocId(dir: String): String? {
            val p = dir.trimEnd('/')
            Regex("""^/storage/emulated/0(?:/(.*))?$""").find(p)?.let { return "primary:" + it.groupValues[1] }
            Regex("""^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(?:/(.*))?$""").find(p)?.let { return it.groupValues[1] + ":" + it.groupValues[2] }
            return null
        }

        /** 트리 [treeId]가 폴더 [docId]를 포함하는지("primary:"는 저장공간 전체) */
        fun covers(treeId: String, docId: String): Boolean =
            docId == treeId || (treeId.endsWith(":") && docId.startsWith(treeId)) || docId.startsWith("$treeId/")

        private fun remotePathParent(path: String): String {
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx <= 0) "/" else trimmed.substring(0, idx)
        }

        private fun joinRemote(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

        private val subtitleExt = Regex("""\.(srt|ass|ssa|smi|sami|vtt|sub)$""", RegexOption.IGNORE_CASE)
        private val languages: Set<String> by lazy {
            Locale.getISOLanguages().flatMap { listOf(it, runCatching { Locale(it).isO3Language }.getOrDefault(it)) }.toSet()
        }

        /**
         * 영상 옆에 저장할 기본 이름: "영상이름.언어.srt". 자막 파일 이름 끝에 언어 코드(.ko, .eng)가 있으면 붙이고,
         * 없으면 "영상이름.srt". 같은 이름의 자막을 자동으로 불러오는 플레이어가 많아 영상 이름으로 시작하게 한다.
         */
        fun defaultExportName(mediaTitle: String, subtitleName: String): String {
            val base = mediaTitle.substringBeforeLast('.', mediaTitle)
            val lang = subtitleName.replace(subtitleExt, "").substringAfterLast('.', "").lowercase()
                .takeIf { it.length in 2..3 && it in languages }
            return if (lang != null) "$base.$lang.srt" else "$base.srt"
        }

        /** [name]과 겹치지 않는 이름: "영상.ko.srt" → "영상.ko-2.srt" (언어 표시는 그대로 알아볼 수 있다) */
        fun alternativeName(name: String, existing: Set<String>): String {
            val stem = name.removeSuffix(".srt")
            for (i in 2..999) {
                val candidate = "$stem-$i.srt"
                if (candidate.lowercase() !in existing) return candidate
            }
            return "$stem-${System.currentTimeMillis()}.srt"
        }
    }
}
