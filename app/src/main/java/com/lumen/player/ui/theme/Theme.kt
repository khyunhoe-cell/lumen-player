package com.lumen.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 시안 A "루멘" 토큰. 디자인 캔버스의 hex 값을 그대로 옮겼다. */
object Lumen {
    val Ground = Color(0xFF0E1719)
    val Deep = Color(0xFF0A1213)
    val Surface = Color(0xFF172426)
    val SurfaceHigh = Color(0xFF213335)
    val NavBar = Color(0xFF132022)
    val Primary = Color(0xFF7FD6C8)
    val OnPrimary = Color(0xFF00332D)
    val PrimaryContainer = Color(0xFF2E4F4C)
    val OnPrimaryContainer = Color(0xFFC8F2EA)
    val Amber = Color(0xFFF2C37C)
    val Text = Color(0xFFE3ECEB)
    val TextSoft = Color(0xFFCFE0DE)
    val TextMuted = Color(0xFF9DB2B0)
    val Outline = Color(0xFF3A4E50)
    val Hairline = Color(0xFF2A3B3D)
    val Error = Color(0xFFFFB4AB)
}

private val LumenColors = darkColorScheme(
    primary = Lumen.Primary,
    onPrimary = Lumen.OnPrimary,
    primaryContainer = Lumen.PrimaryContainer,
    onPrimaryContainer = Lumen.OnPrimaryContainer,
    secondary = Lumen.Amber,
    onSecondary = Color(0xFF3F2C00),
    secondaryContainer = Lumen.PrimaryContainer,
    onSecondaryContainer = Lumen.OnPrimaryContainer,
    tertiary = Lumen.Amber,
    background = Lumen.Ground,
    onBackground = Lumen.Text,
    surface = Lumen.Ground,
    onSurface = Lumen.Text,
    surfaceVariant = Lumen.Surface,
    onSurfaceVariant = Lumen.TextMuted,
    surfaceContainerLowest = Lumen.Deep,
    surfaceContainerLow = Lumen.NavBar,
    surfaceContainer = Lumen.Surface,
    surfaceContainerHigh = Lumen.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF2A3E40),
    outline = Lumen.Outline,
    outlineVariant = Lumen.Hairline,
    error = Lumen.Error,
)

private val LumenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LumenColors,
        typography = LumenTypography,
        shapes = LumenShapes,
        content = content,
    )
}
