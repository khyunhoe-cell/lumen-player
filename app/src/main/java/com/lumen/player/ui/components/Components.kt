package com.lumen.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumen.player.ui.theme.Lumen

/** 시안 A의 칩: 선택되면 청록 컨테이너, 아니면 얇은 테두리. 높이 36dp, 모서리 12dp */
@Composable
fun LumenChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Lumen.PrimaryContainer else Color.Transparent,
        contentColor = if (selected) Lumen.OnPrimaryContainer else Lumen.TextSoft,
        border = if (selected) null else BorderStroke(1.dp, Lumen.Outline),
        modifier = modifier.height(36.dp).semantics { this.selected = selected },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/** 끄는 동안에는 화면만 바꾸고, 손을 떼면 저장한다(설정 쓰기 횟수를 줄인다). */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Lumen.Primary,
                activeTrackColor = Lumen.Primary,
                inactiveTrackColor = Lumen.Hairline,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun ColorSwatches(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val isSel = c == selected
            Box(
                Modifier
                    .size(44.dp)
                    .then(if (isSel) Modifier.border(2.dp, Lumen.Primary, CircleShape) else Modifier)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(1.dp, Lumen.Outline, CircleShape)
                    .semantics { contentDescription = "색상 #%08X".format(c); this.selected = isSel }
                    .clickableNoRipple { onPick(c) },
            )
        }
    }
}

/** 소스 목록 한 줄: 44dp 둥근 아이콘 칸 + 제목/부제 */
@Composable
fun SourceListRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Lumen.Surface),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Lumen.Primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp))
}

/** 빈 화면은 다음 행동을 알려 준다 */
@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Lumen.TextMuted)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
