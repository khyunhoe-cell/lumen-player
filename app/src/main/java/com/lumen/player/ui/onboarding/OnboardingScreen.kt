package com.lumen.player.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lumen.player.ui.theme.Lumen

private val sources = listOf("내 기기", "Google Drive", "OneDrive", "SMB", "FTP", "WebDAV", "NFS")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onGranted: () -> Unit, onAddServer: () -> Unit) {
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
    // 거부해도 네트워크 재생은 되므로 결과와 무관하게 다음으로 간다
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onGranted() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lumen.Ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 36.dp),
    ) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(Lumen.Primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Lumen.OnPrimary, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text("루멘", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "휴대폰, 클라우드, 집 NAS에 흩어진 영상과 음악을 한곳에서 재생하세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = Lumen.TextSoft,
        )
        Spacer(Modifier.height(36.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Lumen.TextSoft,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Lumen.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        Feature(Icons.Outlined.Schedule, "멈춘 곳부터 이어보기")
        Feature(Icons.Filled.ClosedCaption, "자막 없는 영상은 검색하거나 AI로 생성")
        Feature(Icons.Filled.Headphones, "화면을 꺼도 계속 재생")
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = { request.launch(permissions) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lumen.Primary, contentColor = Lumen.OnPrimary),
        ) { Text("기기 미디어 접근 허용", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onAddServer,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF4A6264)),
        ) { Text("네트워크 서버 먼저 추가", style = MaterialTheme.typography.labelLarge, color = Lumen.TextSoft) }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Lumen.Primary)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Lumen.TextSoft)
    }
}
