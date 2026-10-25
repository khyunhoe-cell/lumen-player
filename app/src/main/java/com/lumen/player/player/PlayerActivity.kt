package com.lumen.player.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.lumen.player.LumenApp
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.media.MediaTypes
import com.lumen.player.ui.theme.LumenTheme
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : ComponentActivity() {
    private val connection get() = LumenApp.container.player
    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        connection.ensureBound()
        handleViewIntent(intent)
        setContent {
            LumenTheme {
                val service by connection.service.collectAsState()
                service?.let { svc ->
                    val entry by svc.current.collectAsState()
                    // 영상은 가로로 돌리고, 음악은 기기 방향을 따른다
                    LaunchedEffect(entry?.isVideo) {
                        requestedOrientation = if (entry?.isVideo == false) {
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                    PlayerScreen(svc, inPip = inPip.value, onClose = { finish() }, onEnterPip = ::enterPip)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** 다른 앱(파일 관리자, 브라우저)에서 "루멘으로 열기"를 눌렀을 때 */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val name = displayName(uri)
        val isVideo = intent.type?.startsWith("video/") == true || MediaTypes.isVideo(name) ||
            (intent.type?.startsWith("audio/") != true && !MediaTypes.isAudio(name))
        lifecycleScope.launch {
            connection.await().play(listOf(MediaEntry(uri.toString(), name, isVideo, sourceLabel = "외부 앱")), 0)
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            }
        }
        return Uri.decode(uri.lastPathSegment ?: uri.toString())
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val size = connection.service.value?.player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            Rational(size.width, size.height).coerceIn(Rational(100, 239), Rational(239, 100))
        } else {
            Rational(16, 9)
        }
        return PictureInPictureParams.Builder().setAspectRatio(ratio).build()
    }

    private fun Rational.coerceIn(min: Rational, max: Rational): Rational =
        if (this < min) min else if (this > max) max else this

    fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val service = connection.service.value ?: return
        val settings = LumenApp.container.settings.settings.value
        if (settings.autoPip && service.player.isPlaying && service.current.value?.isVideo == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        connection.service.value?.setVideoRendering(true)
    }

    override fun onStop() {
        super.onStop()
        val service = connection.service.value ?: return
        if (isInPictureInPictureMode) return
        if (LumenApp.container.settings.settings.value.backgroundPlayback) {
            // 소리만 계속 재생하고 영상 디코딩은 멈춰 배터리를 아낀다
            service.setVideoRendering(false)
        } else {
            service.player.pause()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
