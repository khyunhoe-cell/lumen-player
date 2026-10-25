package com.lumen.player.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.lumen.player.data.db.ServerEntity
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** basePath = "공유이름" 또는 "공유이름/하위/폴더" */
class SmbFileSystem(private val server: ServerEntity, private val password: String) : RemoteFileSystem {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build(),
    )
    private val shareName = server.basePath.trim('/').substringBefore('/')
    private val subRoot = server.basePath.trim('/').substringAfter('/', "")

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        runCatching { connection?.close() }
        val conn = client.connect(server.host, if (server.port > 0) server.port else 445)
        val auth = if (server.username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(server.username, password.toCharArray(), server.domain.ifBlank { null })
        }
        val s = conn.authenticate(auth)
        val disk = s.connectShare(shareName) as? DiskShare
            ?: error("'$shareName'은(는) 파일 공유가 아닙니다")
        connection = conn
        session = s
        share = disk
        return disk
    }

    private fun smbPath(path: String): String {
        val full = listOf(subRoot, path.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
        return full.replace('/', '\\')
    }

    override fun list(path: String): List<RemoteEntry> =
        share().list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteEntry(
                    name = it.fileName,
                    path = joinPath(path, it.fileName),
                    isDirectory = dir,
                    size = it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }

    private fun openFile(path: String): File = share().openFile(
        smbPath(path),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

    override fun size(path: String): Long = openFile(path).use { it.fileInformation.standardInformation.endOfFile }

    override fun open(path: String, offset: Long): InputStream = SmbRangeStream(openFile(path), offset)

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client.close() }
    }
}

/** smbj의 File.read(buffer, offset)로 임의 위치부터 읽는다. 탐색할 때마다 새 스트림을 만들 필요가 없다. */
private class SmbRangeStream(private val file: File, private var position: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = file.read(b, position, off, minOf(len, 1 shl 20))
        if (n <= 0) return -1
        position += n
        return n
    }

    override fun skip(n: Long): Long {
        position += n
        return n
    }

    override fun close() = file.close()
}
