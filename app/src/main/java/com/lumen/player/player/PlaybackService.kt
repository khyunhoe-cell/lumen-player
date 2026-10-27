package com.lumen.player.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lumen.player.LumenApp
import com.lumen.player.R
import com.lumen.player.data.db.PlaybackStateEntity
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.subtitle.LoadedSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 재생의 단일 주인. 화면은 LocalBinder로 ExoPlayer를 직접 받고,
 * 알림·잠금화면·블루투스 리모컨은 MediaSession으로 붙는다.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = LumenApp.container
    private val stateDao get() = container.db.playbackStates()

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null

    private val queue = mutableListOf<MediaEntry>()
    private val _queueFlow = MutableStateFlow<List<MediaEntry>>(emptyList())
    val queueFlow: StateFlow<List<MediaEntry>> = _queueFlow.asStateFlow()

    private val _current = MutableStateFlow<MediaEntry?>(null)
    val current: StateFlow<MediaEntry?> = _current.asStateFlow()

    private val _externalSubtitle = MutableStateFlow<LoadedSubtitle?>(null)
    val externalSubtitle: StateFlow<LoadedSubtitle?> = _externalSubtitle.asStateFlow()

    /** 양수면 자막이 늦게, 음수면 빨리 나온다 */
    val subtitleDelayMs = MutableStateFlow(0L)

    override fun onCreate() {
        super.onCreate()
        val renderers = DefaultRenderersFactory(this)
            // FFmpeg 오디오 디코더는 기기에 디코더가 없는 형식(DTS, TrueHD 등)만 맡는다.
            // FFmpeg를 앞세우면 AVI 속 MP3·AC-3처럼 프레임 경계가 어긋나거나 여러 프레임이 묶인 오디오를
            // 풀지 못해 재생 시계가 멈추거나 재생이 실패한다
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val dataSources = LumenDataSourceFactory(this, container.remotes, container.http)

        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)

        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openPlayer).build().also { addSession(it) }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        scope.launch {
            while (isActive) {
                delay(3_000)
                if (player.isPlaying) saveCurrent()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = _current.value
            if (previous != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                scope.launch { markCompleted(previous) }
            }
            val entry = queue.getOrNull(player.currentMediaItemIndex)
            _current.value = entry
            if (entry != null && previous?.uri != entry.uri) onEntryStarted(entry, isInitial = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) scope.launch { saveCurrent() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) _current.value?.let { scope.launch { markCompleted(it) } }
        }
    }

    private fun MediaEntry.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(uri)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist ?: sourceLabel)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUri?.let(Uri::parse))
                .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun PlaybackStateEntity?.resumePosition(): Long {
        if (this == null) return 0
        if (positionMs < 5_000) return 0
        if (durationMs > 0 && positionMs > durationMs * 0.95) return 0
        return positionMs
    }

    /** 대기열을 통째로 바꾸고 startIndex부터 재생한다. 저장된 위치가 있으면 이어서 본다. */
    fun play(entries: List<MediaEntry>, startIndex: Int, startPositionMs: Long? = null) {
        if (entries.isEmpty()) return
        scope.launch {
            saveCurrent()
            val index = startIndex.coerceIn(0, entries.lastIndex)
            val settings = container.settings.current()
            val saved = withContext(Dispatchers.IO) { stateDao.get(entries[index].uri) }
            val start = startPositionMs ?: if (settings.resumePlayback) saved.resumePosition() else 0
            queue.clear()
            queue.addAll(entries)
            _queueFlow.value = queue.toList()
            _current.value = null
            player.setMediaItems(entries.map { it.toMediaItem() }, index, start)
            player.setPlaybackSpeed(saved?.speed?.takeIf { it != 1f } ?: settings.defaultSpeed)
            player.prepare()
            player.play()
            // 바인딩만으로는 started 상태가 아니므로 백그라운드 재생을 위해 직접 시작한다
            startService(Intent(this@PlaybackService, PlaybackService::class.java))
        }
    }

    /**
     * 재생을 끝낸다(일시정지가 아니라 종료). 이어 보기 위치를 저장하고 대기열을 비워
     * 알림·백그라운드 재생·영상으로 돌아가기 버튼이 모두 사라지게 한다.
     */
    fun stopPlayback() {
        scope.launch {
            saveCurrent()
            // clearMediaItems()가 onMediaItemTransition을 부르므로 대기열을 먼저 비워야 현재 항목이 되살아나지 않는다
            queue.clear()
            _queueFlow.value = emptyList()
            player.stop()
            player.clearMediaItems()
            _current.value = null
            _externalSubtitle.value = null
            stopSelf()
        }
    }

    fun enqueue(entries: List<MediaEntry>) {
        if (queue.isEmpty()) return play(entries, 0)
        queue.addAll(entries)
        _queueFlow.value = queue.toList()
        player.addMediaItems(entries.map { it.toMediaItem() })
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        queue.removeAt(index)
        _queueFlow.value = queue.toList()
        player.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        queue.add(to, queue.removeAt(from))
        _queueFlow.value = queue.toList()
        player.moveMediaItem(from, to)
    }

    private fun onEntryStarted(entry: MediaEntry, isInitial: Boolean) {
        _externalSubtitle.value = null
        subtitleDelayMs.value = 0
        setEmbeddedTextEnabled(true)
        scope.launch {
            val settings = container.settings.current()
            val saved = withContext(Dispatchers.IO) { stateDao.get(entry.uri) }
            // 대기열에서 다음 항목으로 넘어온 경우에도 그 항목의 이어보기 위치로 간다
            if (!isInitial && settings.resumePlayback) {
                val resume = saved.resumePosition()
                if (resume > 0) player.seekTo(resume)
            }
            if (!isInitial) saved?.speed?.let { player.setPlaybackSpeed(it) }
            val subtitleSource = saved?.subtitlePath
                ?: if (settings.autoLoadSubtitles) container.subtitles.findSidecar(entry, settings.online.languages) else null
            if (subtitleSource != null) {
                container.subtitles.load(subtitleSource).onSuccess { if (_current.value?.uri == entry.uri) setExternalSubtitle(it) }
            }
        }
    }

    /** 외부 자막을 켜면 내장 자막은 끈다(두 줄이 겹치지 않게). null이면 내장 자막으로 돌아간다. */
    fun setExternalSubtitle(subtitle: LoadedSubtitle?) {
        _externalSubtitle.value = subtitle
        setEmbeddedTextEnabled(subtitle == null)
        scope.launch { saveCurrent() }
    }

    fun setEmbeddedTextEnabled(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    /** 화면이 꺼지거나 앱이 뒤로 가면 영상 디코딩을 멈추고 소리만 낸다. */
    fun setVideoRendering(enabled: Boolean) {
        if (_current.value?.isVideo != true) return
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        if (disabled == !enabled) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    private suspend fun saveCurrent() {
        val entry = _current.value ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: entry.durationMs
        val state = PlaybackStateEntity(
            uri = entry.uri,
            title = entry.title,
            positionMs = player.currentPosition,
            durationMs = duration,
            isVideo = entry.isVideo,
            sourceLabel = entry.sourceLabel,
            updatedAt = System.currentTimeMillis(),
            subtitlePath = _externalSubtitle.value?.source,
            speed = player.playbackParameters.speed,
        )
        withContext(Dispatchers.IO) { stateDao.upsert(state) }
    }

    private suspend fun markCompleted(entry: MediaEntry) {
        withContext(Dispatchers.IO) {
            val old = stateDao.get(entry.uri)
            val duration = old?.durationMs?.takeIf { it > 0 } ?: entry.durationMs
            stateDao.upsert(
                PlaybackStateEntity(
                    uri = entry.uri, title = entry.title, positionMs = duration, durationMs = duration,
                    isVideo = entry.isVideo, sourceLabel = entry.sourceLabel, updatedAt = System.currentTimeMillis(),
                    subtitlePath = old?.subtitlePath, speed = old?.speed ?: 1f,
                ),
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onBind(intent: Intent?): IBinder? {
        val sessionBinder = super.onBind(intent)
        return if (intent?.action == ACTION_LOCAL) binder else sessionBinder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runBlocking { saveCurrent() }
        scope.cancel()
        session?.run {
            release()
            session = null
        }
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCAL = "com.lumen.player.LOCAL_BIND"
    }
}
