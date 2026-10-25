package com.lumen.player.remote

import com.lumen.player.data.db.ServerEntity
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class WebDavFileSystem(
    private val server: ServerEntity,
    private val password: String,
    private val https: Boolean,
    private val http: OkHttpClient,
) : RemoteFileSystem {

    private val baseUrl: String = buildString {
        append(if (https) "https://" else "http://")
        append(server.host)
        val defaultPort = if (https) 443 else 80
        if (server.port > 0 && server.port != defaultPort) append(":").append(server.port)
        append("/").append(server.basePath.trim('/'))
    }.trimEnd('/')

    private val basePathOnServer = java.net.URI(baseUrl + "/").path.trimEnd('/')

    private fun url(path: String): String =
        baseUrl + path.split('/').joinToString("/") { if (it.isEmpty()) it else encode(it) }

    private fun encode(seg: String) = URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

    private fun Request.Builder.auth(): Request.Builder =
        if (server.username.isBlank()) this else header("Authorization", Credentials.basic(server.username, password, Charsets.UTF_8))

    override fun list(path: String): List<RemoteEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>""".trimMargin()
        val dirUrl = url(path).trimEnd('/') + "/"
        val req = Request.Builder().url(dirUrl)
            .method("PROPFIND", body.toRequestBody(null))
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .auth()
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("WebDAV 인증 실패: 아이디와 비밀번호를 확인하세요")
            if (!resp.isSuccessful) throw IOException("WebDAV 목록을 가져오지 못했습니다 (${resp.code})")
            val requested = java.net.URI(dirUrl).path.trimEnd('/')
            return parseMultistatus(resp.body!!.byteStream())
                .filter { decode(it.href).trimEnd('/') != decode(requested) }
                .map { item ->
                    val serverPath = decode(java.net.URI(item.href.replace(" ", "%20")).path).trimEnd('/')
                    val rel = serverPath.removePrefix(decode(basePathOnServer)).ifEmpty { "/" }
                    RemoteEntry(
                        name = rel.substringAfterLast('/'),
                        path = rel,
                        isDirectory = item.isCollection,
                        size = item.length,
                        modified = item.modified,
                    )
                }
        }
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private data class DavItem(val href: String, val isCollection: Boolean, val length: Long, val modified: Long)

    private fun parseMultistatus(input: InputStream): List<DavItem> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        val out = mutableListOf<DavItem>()
        var href = ""
        var collection = false
        var length = -1L
        var modified = 0L
        var text = StringBuilder()
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> { href = ""; collection = false; length = -1; modified = 0 }
                        "collection" -> collection = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> length = text.toString().trim().toLongOrNull() ?: -1
                    "getlastmodified" -> modified = runCatching { dateFormat.parse(text.toString().trim())!!.time }.getOrDefault(0)
                    "response" -> if (href.isNotEmpty()) out += DavItem(href, collection, length, modified)
                }
            }
        }
        return out
    }

    override fun size(path: String): Long {
        val req = Request.Builder().url(url(path)).head().auth().build()
        http.newCall(req).execute().use { return it.header("Content-Length")?.toLongOrNull() ?: -1 }
    }

    override fun open(path: String, offset: Long): InputStream {
        val req = Request.Builder().url(url(path)).auth()
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("WebDAV 파일을 열 수 없습니다 (${resp.code})")
        }
        val stream = resp.body!!.byteStream()
        // 서버가 Range를 무시하고 200으로 전체를 주면 직접 건너뛴다
        if (offset > 0 && resp.code == 200) {
            var left = offset
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped <= 0) break
                left -= skipped
            }
        }
        return stream
    }

    override fun close() = Unit
}
