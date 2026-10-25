package com.lumen.player.data.media

import android.net.Uri
import kotlinx.serialization.Serializable

/** 화면과 재생 대기열이 공유하는 미디어 한 건. 로컬·네트워크 구분 없이 uri 하나로 재생한다. */
@Serializable
data class MediaEntry(
    val uri: String,
    val title: String,
    val isVideo: Boolean,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val sourceLabel: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumArtUri: String? = null,
    /** 같은 폴더에서 찾은 자막 후보 uri */
    val sidecarSubtitles: List<String> = emptyList(),
) {
    val androidUri: Uri get() = Uri.parse(uri)
}

object MediaTypes {
    val video = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "asf", "flv", "f4v", "3gp", "3g2",
        "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "ogv", "rm", "rmvb", "divx", "m3u8", "mpd",
    )
    val audio = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "alac", "aiff", "aif",
        "dsf", "dff", "mka", "amr", "mid", "midi", "wv", "tta", "ac3", "dts",
    )
    val subtitle = setOf("srt", "ass", "ssa", "vtt", "smi", "sami", "ttml", "dfxp", "sub", "txt")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isVideo(name: String) = ext(name) in video
    fun isAudio(name: String) = ext(name) in audio
    fun isMedia(name: String) = isVideo(name) || isAudio(name)
    fun isSubtitle(name: String) = ext(name) in subtitle
}
