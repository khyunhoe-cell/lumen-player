package com.lumen.player.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import com.lumen.player.data.media.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 앱 전체에서 하나만 두는 PlaybackService 바인딩. 미니 플레이어와 재생 화면이 함께 쓴다. */
@UnstableApi
class PlayerConnection(private val app: Application, private val scope: CoroutineScope) {
    private val _service = MutableStateFlow<PlaybackService?>(null)
    val service: StateFlow<PlaybackService?> = _service.asStateFlow()
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _service.value = (binder as? PlaybackService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
            bound = false
        }
    }

    fun ensureBound() {
        if (bound) return
        bound = app.bindService(
            Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    suspend fun await(): PlaybackService {
        ensureBound()
        return _service.filterNotNull().first()
    }

    /** 재생을 시작하고, 영상이면 재생 화면을 연다. */
    fun play(context: Context, entries: List<MediaEntry>, index: Int, openScreen: Boolean = true) {
        scope.launch(Dispatchers.Main) {
            await().play(entries, index)
            val entry = entries.getOrNull(index) ?: return@launch
            if (openScreen && entry.isVideo) {
                context.startActivity(
                    Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun enqueue(entries: List<MediaEntry>) {
        scope.launch(Dispatchers.Main) { await().enqueue(entries) }
    }
}
