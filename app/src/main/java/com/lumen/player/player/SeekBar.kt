package com.lumen.player.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.lumen.player.ui.theme.Lumen

/**
 * 시안 A의 재생바: 6dp 트랙, 청록 채움, 세로 막대 손잡이(4×24).
 * 끄는 동안에는 onScrub으로 미리보기 위치만 알리고, 손을 떼면 onSeek로 이동한다.
 */
@Composable
fun LumenSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = "재생 위치"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    onSeek((target * duration).toLong())
                    true
                }
            }
            .pointerInput(duration) {
                detectTapGestures { offset -> onSeek((offset.x / size.width * duration).toLong().coerceIn(0, duration)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrub((dragFraction * duration).toLong())
                    },
                    onDragEnd = {
                        onSeek((dragFraction * duration).toLong())
                        dragging = false
                        onScrub(null)
                    },
                    onDragCancel = {
                        dragging = false
                        onScrub(null)
                    },
                ) { change, delta ->
                    change.consume()
                    dragFraction = (dragFraction + delta / size.width).coerceIn(0f, 1f)
                    onScrub((dragFraction * duration).toLong())
                }
            },
    ) {
        val trackH = 6.dp.toPx()
        val y = (size.height - trackH) / 2
        val r = CornerRadius(trackH / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, trackH), r)
        drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y), Size(size.width * buffered, trackH), r)
        drawRoundRect(Lumen.Primary, Offset(0f, y), Size(size.width * fraction, trackH), r)
        val thumbW = 4.dp.toPx()
        val thumbH = (if (dragging) 28 else 24).dp.toPx()
        drawRoundRect(
            Lumen.OnPrimaryContainer,
            Offset((size.width * fraction - thumbW / 2).coerceIn(0f, size.width - thumbW), (size.height - thumbH) / 2),
            Size(thumbW, thumbH),
            CornerRadius(thumbW / 2),
        )
    }
}
