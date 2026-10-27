package com.lumen.player.remote

import com.lumen.player.data.db.ServerEntity
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration

/**
 * FTP 연결 하나는 동시에 한 전송만 할 수 있다. 목록 조회는 공유 연결 하나로,
 * 재생 스트림은 열 때마다 별도 연결을 만든다.
 */
class FtpFileSystem(
    private val server: ServerEntity,
    private val password: String,
    private val secure: Boolean,
) : RemoteFileSystem {
    private var listClient: FTPClient? = null
    private val root = "/" + server.basePath.trim('/')

    private fun connect(): FTPClient {
        val client = if (secure) FTPSClient(false) else FTPClient()
        client.controlEncoding = "UTF-8"
        client.setConnectTimeout(20_000)
        client.setDataTimeout(Duration.ofSeconds(60))
        client.connect(server.host, if (server.port > 0) server.port else 21)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            client.disconnect()
            throw IOException("FTP 서버가 연결을 거부했습니다 (${client.replyCode})")
        }
        val user = server.username.ifBlank { "anonymous" }
        val pass = if (server.username.isBlank()) "lumen@" else password
        if (!client.login(user, pass)) {
            client.disconnect()
            throw IOException("FTP 로그인 실패: 아이디와 비밀번호를 확인하세요")
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        client.sendCommand("OPTS UTF8 ON")
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.enterLocalPassiveMode()
        return client
    }

    private fun full(path: String) = (root.trimEnd('/') + "/" + path.trimStart('/')).ifEmpty { "/" }

    @Synchronized
    private fun lister(): FTPClient {
        listClient?.takeIf { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) }?.let { return it }
        runCatching { listClient?.disconnect() }
        return connect().also { listClient = it }
    }

    @Synchronized
    override fun list(path: String): List<RemoteEntry> =
        lister().listFiles(full(path)).filter { it.name != "." && it.name != ".." }.map {
            RemoteEntry(
                name = it.name,
                path = joinPath(path, it.name),
                isDirectory = it.isDirectory,
                size = it.size,
                modified = it.timestamp?.timeInMillis ?: 0,
            )
        }

    @Synchronized
    override fun size(path: String): Long {
        val c = lister()
        c.getSize(full(path))?.trim()?.toLongOrNull()?.let { return it }
        return c.listFiles(full(path)).firstOrNull()?.size ?: -1
    }

    override fun open(path: String, offset: Long): InputStream {
        val client = connect()
        if (offset > 0) client.restartOffset = offset
        val stream = client.retrieveFileStream(full(path))
            ?: run {
                client.disconnect()
                throw IOException("파일을 열 수 없습니다: ${client.replyString}")
            }
        return object : FilterInputStream(stream) {
            override fun close() {
                // 끝까지 읽지 않고 닫는 경우(탐색)가 대부분이라 completePendingCommand 대신 연결을 끊는다
                runCatching { super.close() }
                runCatching { client.abort() }
                runCatching { client.disconnect() }
            }
        }
    }

    override fun write(path: String, bytes: ByteArray) {
        val client = connect()
        try {
            if (!client.storeFile(full(path), bytes.inputStream())) {
                throw IOException("FTP에 저장하지 못했습니다: ${client.replyString.trim()}")
            }
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { listClient?.logout() }
        runCatching { listClient?.disconnect() }
        listClient = null
    }
}
