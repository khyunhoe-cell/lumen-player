package com.lumen.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lumen.player.R

val PlexSansKr = FontFamily(
    Font(R.font.plex_kr_regular, FontWeight.Normal),
    Font(R.font.plex_kr_medium, FontWeight.Medium),
    Font(R.font.plex_kr_semibold, FontWeight.SemiBold),
    Font(R.font.plex_kr_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = PlexSansKr,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
)

val LumenTypography = Typography(
    displaySmall = style(40, 48, FontWeight.Bold),
    headlineMedium = style(26, 34, FontWeight.Bold),
    headlineSmall = style(22, 30, FontWeight.Bold),
    titleLarge = style(18, 26, FontWeight.Bold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(15, 22, FontWeight.Medium),
    bodyLarge = style(17, 27, FontWeight.Normal),
    bodyMedium = style(15, 24, FontWeight.Normal),
    bodySmall = style(13, 20, FontWeight.Normal),
    labelLarge = style(16, 22, FontWeight.SemiBold),
    labelMedium = style(14, 20, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)
