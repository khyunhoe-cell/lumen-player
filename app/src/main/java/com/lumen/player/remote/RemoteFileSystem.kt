package com.lumen.player.remote

import android.net.Uri
import java.io.Closeable
import java.io.InputStream

data class RemoteEntry(
    val name: String,
    /** 서버 루트 기준 경로. 항상 "/"로 시작하고 구분자는 "/" */
    val path: String,
    val isDirectory: Boolean,
    val size: Long = -1,
    val modified: Long = 0,
)

/**
 * 네트워크 저장소 하나. list는 화면에서, open/size는 재생기(로더 스레드)에서 블로킹으로 호출된다.
 * 구현은 여러 스레드에서 동시에 open되는 상황을 견뎌야 한다(영상 + 자막 + 썸네일).
 */
interface RemoteFileSystem : Closeable {
    fun list(path: String): List<RemoteEntry>
    fun size(path: String): Long
    fun open(path: String, offset: Long): InputStream

    /** 파일을 [bytes]로 만들거나 덮어쓴다(자막을 영상 옆에 저장할 때). 쓸 수 없는 저장소는 그대로 둔다 */
    fun write(path: String, bytes: ByteArray) {
        throw UnsupportedOperationException("이 저장소에는 파일을 저장할 수 없습니다")
    }
}

object RemoteUris {
    const val SCHEME = "lumen"

    /** lumen://<serverId>/<경로> — 재생·이어보기·재생목록이 모두 이 문자열을 키로 쓴다. */
    fun build(serverId: Long, path: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(serverId.toString())
        .path(path)
        .build()

    fun isRemote(uri: Uri) = uri.scheme == SCHEME
    fun serverId(uri: Uri): Long = uri.authority!!.toLong()
    fun path(uri: Uri): String = uri.path ?: "/"
}

internal fun joinPath(dir: String, name: String): String =
    if (dir.endsWith("/")) dir + name else "$dir/$name"

internal fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}
