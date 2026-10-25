package com.lumen.player.data.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalFolder(val path: String, val name: String, val items: List<MediaEntry>)

class LocalMediaRepository(private val context: Context) {

    suspend fun videos(): List<MediaEntry> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val out = mutableListOf<MediaEntry>()
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val bucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                out += MediaEntry(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                    title = c.getString(name) ?: "",
                    isVideo = true,
                    durationMs = c.getLong(dur),
                    sizeBytes = c.getLong(size),
                    sourceLabel = c.getString(bucket) ?: "내 기기",
                )
            }
        }
        out
    }

    suspend fun audio(): List<MediaEntry> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val artBase = android.net.Uri.parse("content://media/external/audio/albumart")
        val out = mutableListOf<MediaEntry>()
        // MediaStore는 정렬 절의 COLLATE LOCALIZED를 거부하므로 제목 정렬은 앱에서 한다
        context.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                out += MediaEntry(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                    title = c.getString(title) ?: c.getString(name) ?: "",
                    isVideo = false,
                    durationMs = c.getLong(dur),
                    sizeBytes = c.getLong(size),
                    sourceLabel = "내 기기",
                    artist = c.getString(artist)?.takeIf { it != MediaStore.UNKNOWN_STRING },
                    album = c.getString(album),
                    albumArtUri = ContentUris.withAppendedId(artBase, c.getLong(albumId)).toString(),
                )
            }
        }
        val collator = java.text.Collator.getInstance()
        out.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    /** 영상을 폴더(버킷) 단위로 묶는다. */
    suspend fun videoFolders(): List<LocalFolder> =
        videos().groupBy { it.sourceLabel }.map { (name, items) -> LocalFolder(name, name, items) }
            .sortedBy { it.name.lowercase() }
}
