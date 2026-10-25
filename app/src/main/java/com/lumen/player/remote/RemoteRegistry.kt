package com.lumen.player.remote

import android.content.Context
import android.net.Uri
import com.lumen.player.data.db.ServerDao
import com.lumen.player.data.db.ServerEntity
import com.lumen.player.data.db.ServerType
import com.lumen.player.data.settings.SecretBox
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** 서버 id → 연결된 파일 시스템. 재생기는 로더 스레드에서 blocking 버전을 부른다. */
class RemoteRegistry(
    private val context: Context,
    private val servers: ServerDao,
    private val http: OkHttpClient,
) {
    private val cache = ConcurrentHashMap<Long, RemoteFileSystem>()

    suspend fun save(server: ServerEntity, plainPassword: String?): Long {
        val toSave = if (plainPassword != null) server.copy(password = SecretBox.seal(plainPassword)) else server
        val id = servers.upsert(toSave).let { if (it > 0) it else server.id }
        invalidate(id)
        return id
    }

    fun invalidate(id: Long) {
        cache.remove(id)?.let { runCatching { it.close() } }
    }

    suspend fun get(id: Long): RemoteFileSystem {
        cache[id]?.let { return it }
        val server = servers.get(id) ?: error("서버가 삭제되었습니다")
        return create(server).also { cache[id] = it }
    }

    fun getBlocking(id: Long): RemoteFileSystem = cache[id] ?: runBlocking { get(id) }

    suspend fun server(id: Long) = servers.get(id)

    /** 연결 시험. 루트 목록을 한 번 가져와 본다. */
    fun test(server: ServerEntity, plainPassword: String): Result<Int> = runCatching {
        create(server.copy(password = SecretBox.seal(plainPassword))).use { it.list("/").size }
    }

    private fun create(server: ServerEntity): RemoteFileSystem {
        val password = SecretBox.open(server.password)
        return when (server.type) {
            ServerType.SMB -> SmbFileSystem(server, password)
            ServerType.FTP -> FtpFileSystem(server, password, secure = false)
            ServerType.FTPS -> FtpFileSystem(server, password, secure = true)
            ServerType.WEBDAV -> WebDavFileSystem(server, password, https = false, http = http)
            ServerType.WEBDAVS -> WebDavFileSystem(server, password, https = true, http = http)
            ServerType.NFS -> NfsFileSystem(server)
            ServerType.SAF -> SafFileSystem(context, Uri.parse(server.basePath))
        }
    }
}
