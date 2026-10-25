package com.lumen.player.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 이어보기용 재생 위치. uri는 재생에 쓰는 문자열 그대로 키로 쓴다. */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val isVideo: Boolean,
    val sourceLabel: String,
    val updatedAt: Long,
    /** 사용자가 마지막으로 고른 외부/AI 자막 파일 경로 */
    val subtitlePath: String? = null,
    val speed: Float = 1f,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val uri: String,
    val title: String,
    val isVideo: Boolean,
    val sourceLabel: String,
    val sortOrder: Int,
)

enum class ServerType(val label: String, val defaultPort: Int) {
    SMB("SMB", 445),
    FTP("FTP", 21),
    FTPS("FTPS", 21),
    WEBDAV("WebDAV", 80),
    WEBDAVS("WebDAV (HTTPS)", 443),
    NFS("NFS", 2049),
    /** Storage Access Framework 폴더. Google Drive, OneDrive, Dropbox 앱이 제공하는 클라우드도 여기로 들어온다. */
    SAF("클라우드 · 문서 폴더", 0),
}

@Entity(tableName = "server")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ServerType,
    val name: String,
    val host: String,
    val port: Int,
    /** SMB는 공유 이름, NFS는 export 경로, WebDAV는 기본 경로, SAF는 트리 URI */
    val basePath: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
)
