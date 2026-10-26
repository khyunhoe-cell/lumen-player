package com.lumen.player

import android.app.Application
import com.lumen.player.data.db.AppDatabase
import com.lumen.player.data.media.LocalMediaRepository
import com.lumen.player.data.settings.SettingsRepository
import com.lumen.player.player.PlayerConnection
import com.lumen.player.remote.RemoteRegistry
import com.lumen.player.subtitle.SubtitleRepository
import com.lumen.player.subtitle.ai.AiSubtitleGenerator
import com.lumen.player.subtitle.ai.ApiKeyChecker
import com.lumen.player.subtitle.ai.SenseVoiceModelManager
import com.lumen.player.subtitle.ai.WhisperModelManager
import com.lumen.player.subtitle.online.OnlineSubtitleSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 간단한 수동 DI. 화면과 서비스가 같은 인스턴스를 공유한다. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    val db = AppDatabase.create(app)
    val settings = SettingsRepository(app, scope)
    val localMedia = LocalMediaRepository(app)
    val remotes = RemoteRegistry(app, db.servers(), http)
    val subtitles = SubtitleRepository(app, remotes)
    val onlineSubtitles = OnlineSubtitleSearch(app, http, settings, remotes)
    val apiKeyChecker = ApiKeyChecker(http)
    val whisperModels = WhisperModelManager(app, http, scope)
    val senseVoiceModels = SenseVoiceModelManager(app, http, scope)
    val aiSubtitles = AiSubtitleGenerator(app, http, settings, remotes, whisperModels, senseVoiceModels, scope)
    val player = PlayerConnection(app, scope)
}

class LumenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: LumenApp
            private set
        val container get() = instance.container
    }
}
