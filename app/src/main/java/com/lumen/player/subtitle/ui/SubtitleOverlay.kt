package com.lumen.player.subtitle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.player.data.settings.SubtitleFont
import com.lumen.player.data.settings.SubtitlePosition
import com.lumen.player.data.settings.SubtitleStyle
import com.lumen.player.ui.theme.PlexSansKr
import java.io.File

@OptIn(ExperimentalTextApi::class)
fun SubtitleStyle.fontFamily(): FontFamily = when (font) {
    SubtitleFont.PLEX -> PlexSansKr
    SubtitleFont.SANS -> FontFamily.SansSerif
    SubtitleFont.SERIF -> FontFamily.Serif
    SubtitleFont.MONO -> FontFamily.Monospace
    SubtitleFont.CUSTOM -> customFontPath?.let { path ->
        runCatching { FontFamily(Font(File(path))) }.getOrNull()
    } ?: PlexSansKr
}

/** 영상 밖에 자막을 둘 때 영상 영역에서 비워 둘 높이 */
fun SubtitleStyle.reservedHeight(): Dp =
    if (position == SubtitlePosition.BELOW_VIDEO || position == SubtitlePosition.ABOVE_VIDEO) {
        (sizeSp * lineSpacing * reservedLines + marginDp * 2).dp
    } else {
        0.dp
    }

/**
 * 사용자 스타일로 자막을 그린다. 외곽선은 같은 글자를 Stroke로 한 번 더 그려 만든다.
 * [videoRect]는 컨테이너 좌표의 실제 영상 영역, [containerHeightPx]는 전체 높이.
 */
@Composable
fun SubtitleOverlay(
    lines: String,
    style: SubtitleStyle,
    videoRect: Rect,
    containerHeightPx: Float,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    onDragMargin: ((deltaDp: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
) {
    if (lines.isBlank()) return
    val density = LocalDensity.current
    val family = remember(style.font, style.customFontPath) { style.fontFamily() }
    val size = style.sizeSp * scale
    val base = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.SemiBold,
        lineHeight = (size * style.lineSpacing).sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textAlign = TextAlign.Center,
    )
    val fill = base.copy(
        color = Color(style.textColor),
        shadow = if (style.shadowRadius > 0f || style.shadowOffset > 0f) {
            Shadow(Color(style.shadowColor), Offset(style.shadowOffset * density.density, style.shadowOffset * density.density), style.shadowRadius * density.density)
        } else {
            null
        },
    )
    val outline = base.copy(
        color = Color(style.outlineColor),
        drawStyle = Stroke(width = style.outlineWidth * 2 * density.density, join = StrokeJoin.Round),
    )
    val marginPx = with(density) { (style.marginDp * scale).dp.toPx() }
    val dragModifier = if (onDragMargin != null) {
        Modifier.pointerInput(style.position) {
            detectVerticalDragGestures(onDragEnd = { onDragEnd?.invoke() }) { change, dragAmount ->
                change.consume()
                val dy = dragAmount / density.density
                // 아래쪽 기준 위치는 위로 끌수록 여백이 커진다
                val sign = if (style.position == SubtitlePosition.INSIDE_TOP) 1f else -1f
                onDragMargin(dy * sign)
            }
        }
    } else {
        Modifier
    }

    Layout(
        modifier = modifier,
        content = {
            Box(
                modifier = dragModifier
                    .widthIn(max = with(density) { (videoRect.width * 0.92f).toDp() })
                    .then(
                        if (Color(style.backgroundColor).alpha > 0f) {
                            Modifier
                                .background(Color(style.backgroundColor), RoundedCornerShape(6.dp))
                                .padding(horizontal = (style.backgroundPadding * 1.6f).dp, vertical = style.backgroundPadding.dp)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (style.outlineWidth > 0f) Text(lines, style = outline)
                Text(lines, style = fill)
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth))
        val x = (videoRect.center.x - placeable.width / 2f).toInt()
            .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
        val y = when (style.position) {
            SubtitlePosition.INSIDE_BOTTOM -> videoRect.bottom - marginPx - placeable.height
            SubtitlePosition.INSIDE_TOP -> videoRect.top + marginPx
            // 영상 아래 비워 둔 띠의 가운데
            SubtitlePosition.BELOW_VIDEO -> videoRect.bottom + ((containerHeightPx - videoRect.bottom) - placeable.height) / 2f
            SubtitlePosition.ABOVE_VIDEO -> (videoRect.top - placeable.height) / 2f
        }.toInt().coerceIn(0, (containerHeightPx.toInt() - placeable.height).coerceAtLeast(0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(IntOffset(x, y))
        }
    }
}

/** 설정 화면과 자막 메뉴의 미리보기. 영상 영역을 어두운 청록 상자로 그린다. */
@Composable
fun SubtitlePreview(style: SubtitleStyle, sample: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color.Black)) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val reserve = with(density) { (style.reservedHeight() * PREVIEW_SCALE).toPx() }
        val rect = when (style.position) {
            SubtitlePosition.BELOW_VIDEO -> Rect(0f, 0f, w, h - reserve)
            SubtitlePosition.ABOVE_VIDEO -> Rect(0f, reserve, w, h)
            else -> Rect(0f, 0f, w, h)
        }
        Box(
            Modifier
                .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .background(Color(0xFF183641)),
        )
        SubtitleOverlay(sample, style, rect, h, scale = PREVIEW_SCALE)
    }
}

private const val PREVIEW_SCALE = 0.6f
