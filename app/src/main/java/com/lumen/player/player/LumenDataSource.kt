package com.lumen.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lumen.player.remote.RemoteRegistry
import com.lumen.player.remote.RemoteUris
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** lumen://서버/경로 를 RemoteFileSystem 스트림으로 읽는다. */
@UnstableApi
class RemoteDataSource(private val registry: RemoteRegistry) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var stream: InputStream? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val fs = registry.getBlocking(RemoteUris.serverId(dataSpec.uri))
        val path = RemoteUris.path(dataSpec.uri)
        val key = dataSpec.uri.toString()
        val total = sizes.getOrPut(key) { fs.size(path) }
        stream = fs.open(path, dataSpec.position)
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total >= 0 -> total - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = stream!!.read(buffer, offset, toRead)
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            stream?.close()
        } finally {
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private val sizes = ConcurrentHashMap<String, Long>()
    }
}

/** URI 스킴에 따라 원격 파일 시스템 또는 기본(content/file/http) 데이터 소스로 보낸다. */
@UnstableApi
class RoutingDataSource(
    private val fallback: DataSource,
    private val remote: DataSource,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (RemoteUris.isRemote(dataSpec.uri)) remote else fallback
        current = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}

@UnstableApi
class LumenDataSourceFactory(context: Context, private val registry: RemoteRegistry, http: OkHttpClient) : DataSource.Factory {
    private val fallback = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))

    override fun createDataSource(): DataSource =
        RoutingDataSource(fallback.createDataSource(), RemoteDataSource(registry))
}
