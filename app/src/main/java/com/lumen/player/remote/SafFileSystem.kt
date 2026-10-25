package com.lumen.player.remote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Storage Access Framework 트리. Google Drive·OneDrive·Dropbox·Nextcloud 같은 앱이
 * 문서 제공자로 노출한 폴더를 그대로 탐색한다. path에는 documentId를 담는다.
 * 재생에는 content:// 문서 URI를 직접 넘기므로 open은 자막 탐색과 AI 추출에서만 쓰인다.
 */
class SafFileSystem(private val context: Context, private val treeUri: Uri) : RemoteFileSystem {
    val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun list(path: String): List<RemoteEntry> {
        val docId = if (path == "/" || path.isEmpty()) rootDocumentId else path
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = mutableListOf<RemoteEntry>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += RemoteEntry(
                    name = c.getString(1) ?: "",
                    path = c.getString(0),
                    isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) -1 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return out
    }

    override fun size(path: String): Long =
        context.contentResolver.openAssetFileDescriptor(documentUri(path), "r")?.use { it.length } ?: -1

    override fun open(path: String, offset: Long): InputStream {
        val pfd = context.contentResolver.openFileDescriptor(documentUri(path), "r")
            ?: throw IOException("문서를 열 수 없습니다")
        val stream = FileInputStream(pfd.fileDescriptor)
        if (offset > 0) stream.channel.position(offset)
        return object : java.io.FilterInputStream(stream) {
            override fun close() {
                super.close()
                pfd.close()
            }
        }
    }

    override fun close() = Unit
}
