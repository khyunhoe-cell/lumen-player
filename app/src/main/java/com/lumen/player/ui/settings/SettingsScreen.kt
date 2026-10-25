package com.lumen.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lumen.player.LumenApp
import com.lumen.player.ui.Routes
import com.lumen.player.ui.components.LabeledSlider
import com.lumen.player.ui.components.SectionTitle
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavController) {
    val repo = LumenApp.container.settings
    val s by repo.settings.collectAsState()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("설정", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 28.dp))
        }
        item { SectionTitle("재생") }
        item {
            ToggleRow("이어보기", "멈춘 위치부터 다시 재생", s.resumePlayback) { v -> scope.launch { repo.update { it.copy(resumePlayback = v) } } }
        }
        item {
            ToggleRow("백그라운드 재생", "앱을 나가거나 화면을 꺼도 소리는 계속", s.backgroundPlayback) { v -> scope.launch { repo.update { it.copy(backgroundPlayback = v) } } }
        }
        item {
            ToggleRow("홈으로 가면 화면 속 화면", "재생 중인 영상을 작은 창으로 띄움", s.autoPip) { v -> scope.launch { repo.update { it.copy(autoPip = v) } } }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                LabeledSlider("기본 재생 속도", s.defaultSpeed, 0.25f..3f, "%.2f×".format(s.defaultSpeed)) { v ->
                    scope.launch { repo.update { it.copy(defaultSpeed = (v * 20).toInt() / 20f) } }
                }
                LabeledSlider("두 번 탭 이동 간격", s.seekStepSec.toFloat(), 5f..60f, "${s.seekStepSec}초") { v ->
                    scope.launch { repo.update { it.copy(seekStepSec = (v / 5).toInt() * 5) } }
                }
            }
        }
        item { SectionTitle("자막") }
        item {
            ToggleRow("같은 이름의 자막 자동 불러오기", ".srt .smi .ass 를 영상과 함께 찾음", s.autoLoadSubtitles) { v -> scope.launch { repo.update { it.copy(autoLoadSubtitles = v) } } }
        }
        item { LinkRow("자막 스타일", "${s.subtitle.font.label} · ${s.subtitle.sizeSp.toInt()} · ${s.subtitle.position.label}") { nav.navigate(Routes.SUBTITLE_STYLE) } }
        item {
            val translate = s.ai.target().let { if (it.isBlank()) "" else " · ${java.util.Locale(it).displayLanguage}로 번역" }
            LinkRow("AI 자막 생성", "${s.ai.provider.label} · ${s.ai.model()}$translate") { nav.navigate(Routes.AI) }
        }
        item { LinkRow("온라인 자막 검색", "언어 ${s.online.languages}") { nav.navigate(Routes.ONLINE) } }
        item { SectionTitle("정보") }
        item {
            Text(
                "루멘 0.1.0 · Media3(ExoPlayer) + FFmpeg 오디오 디코더\n글꼴: IBM Plex Sans KR (SIL Open Font License)",
                style = MaterialTheme.typography.bodySmall,
                color = Lumen.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Lumen.Primary, checkedThumbColor = Lumen.OnPrimary),
            )
        }
    }
}

@Composable
fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        }
    }
}

@Composable
fun SubPageHeader(nav: NavController, title: String) {
    Row(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
