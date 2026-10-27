package com.lumen.player.remote

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.lumen.player.data.db.ServerEntity
import java.io.InputStream

/**
 * NFSv3. basePath = export 경로(예: /volume1/video).
 * 사용자 이름 칸에 "uid:gid"를 넣으면 그 계정으로 접근한다. 비우면 0:0.
 */
class NfsFileSystem(server: ServerEntity) : RemoteFileSystem {
    private val nfs: Nfs3

    init {
        val (uid, gid) = server.username.split(':').let {
            (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        nfs = Nfs3(server.host, "/" + server.basePath.trim('/'), CredentialUnix(uid, gid, null), 3)
    }

    private fun file(path: String) = Nfs3File(nfs, "/" + path.trim('/'))

    override fun list(path: String): List<RemoteEntry> =
        file(path).listFiles().map {
            RemoteEntry(
                name = it.name,
                path = joinPath(path, it.name),
                isDirectory = it.isDirectory,
                size = if (it.isDirectory) -1 else it.length(),
                modified = it.lastModified(),
            )
        }

    override fun size(path: String): Long = file(path).length()

    override fun open(path: String, offset: Long): InputStream {
        val stream = NfsFileInputStream(file(path))
        if (offset > 0) stream.skip(offset)
        return stream
    }

    override fun write(path: String, bytes: ByteArray) {
        val f = file(path)
        if (f.exists()) f.delete()
        f.createNewFile()
        NfsFileOutputStream(f).use { it.write(bytes) }
    }

    override fun close() = Unit
}
