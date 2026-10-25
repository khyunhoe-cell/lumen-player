package com.lumen.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.common.util.UnstableApi
import com.lumen.player.ui.LumenRoot
import com.lumen.player.ui.theme.LumenTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LumenApp.container.player.ensureBound()
        setContent {
            LumenTheme { LumenRoot() }
        }
    }
}
