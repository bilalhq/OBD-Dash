package com.obddash.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.obddash.R

object DashColors {
    val Bg = Color(0xFF07090D)
    val Surface = Color(0xFF10141B)
    val SurfaceHigh = Color(0xFF171C26)
    val Border = Color(0x12FFFFFF)
    val Track = Color(0xFF1E2530)
    val Accent = Color(0xFF22D3EE)
    val Accent2 = Color(0xFF8B5CF6)
    val Red = Color(0xFFFB4D6D)
    val Amber = Color(0xFFFBBF24)
    val Green = Color(0xFF34D399)
    val Text = Color(0xFFF1F5F9)
    val Muted = Color(0xFF8A94A6)
}

object DashBrushes {
    /** Signature cyan → violet gradient used for gauge arcs, buttons and highlights. */
    val Accent = Brush.linearGradient(listOf(DashColors.Accent, DashColors.Accent2))

    /** Cards: a slightly lifted top edge fading into the base surface. */
    val Card = Brush.verticalGradient(listOf(DashColors.SurfaceHigh, DashColors.Surface))
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(
    R.font.space_grotesk, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

object DashFonts {
    /** Body / UI text. */
    val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

    /** Numbers and headings — the "instrument" face. */
    val Display = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))
}

private val typography = Typography().let { t ->
    fun TextStyle.inter() = copy(fontFamily = DashFonts.Inter)
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = DashFonts.Display),
        displayMedium = t.displayMedium.copy(fontFamily = DashFonts.Display),
        displaySmall = t.displaySmall.copy(fontFamily = DashFonts.Display),
        headlineLarge = t.headlineLarge.copy(fontFamily = DashFonts.Display),
        headlineMedium = t.headlineMedium.copy(fontFamily = DashFonts.Display),
        headlineSmall = t.headlineSmall.copy(fontFamily = DashFonts.Display),
        titleLarge = t.titleLarge.copy(fontFamily = DashFonts.Display, fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleMedium = t.titleMedium.copy(fontFamily = DashFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
        titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(),
        bodyMedium = t.bodyMedium.inter(),
        bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.copy(fontFamily = DashFonts.Inter, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontFamily = DashFonts.Inter, fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.copy(fontFamily = DashFonts.Inter, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    )
}

@Composable
fun ObdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DashColors.Accent,
            onPrimary = DashColors.Bg,
            secondary = DashColors.Accent2,
            background = DashColors.Bg,
            onBackground = DashColors.Text,
            surface = DashColors.Surface,
            onSurface = DashColors.Text,
            surfaceVariant = DashColors.SurfaceHigh,
            onSurfaceVariant = DashColors.Muted,
            outline = DashColors.Border,
            error = DashColors.Red,
        ),
        typography = typography,
    ) {
        // Default text/icon colour for anything not inside a Surface (our glass cards and the
        // transparent Scaffold don't set one, which would otherwise fall back to black).
        CompositionLocalProvider(LocalContentColor provides DashColors.Text, content = content)
    }
}
