/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aurora Pure reads exact delivery data out of Google Play, so the interface is built to look
 * precise rather than decorative: a cool slate neutral instead of Material's warm default, an
 * aurora teal carried through primary and secondary so selection states never fight the accent,
 * and violet held back for advisory notes alone.
 */
private val AuroraTeal = Color(0xFF00A88E)
private val AuroraTealBright = Color(0xFF5EDBC0)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9DF5DE),
    onPrimaryContainer = Color(0xFF00201A),
    inversePrimary = AuroraTealBright,
    secondary = Color(0xFF3F6660),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC2ECE4),
    onSecondaryContainer = Color(0xFF13201E),
    tertiary = Color(0xFF6A4FD0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7DEFF),
    onTertiaryContainer = Color(0xFF21005E),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF141D1B),
    surface = Color(0xFFF6FAF8),
    onSurface = Color(0xFF141D1B),
    surfaceVariant = Color(0xFFD9E5E1),
    onSurfaceVariant = Color(0xFF3D4A47),
    surfaceTint = Color(0xFF00695B),
    inverseSurface = Color(0xFF293230),
    inverseOnSurface = Color(0xFFEBF2EF),
    outline = Color(0xFF6D7A76),
    outlineVariant = Color(0xFFBDCAC5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FAF8),
    surfaceDim = Color(0xFFD6DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F2),
    surfaceContainer = Color(0xFFEAF0ED),
    surfaceContainerHigh = Color(0xFFE4EAE7),
    surfaceContainerHighest = Color(0xFFDEE5E2)
)

private val DarkColors = darkColorScheme(
    primary = AuroraTealBright,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005044),
    onPrimaryContainer = Color(0xFF9DF5DE),
    inversePrimary = Color(0xFF00695B),
    secondary = Color(0xFFA9CFC8),
    onSecondary = Color(0xFF143733),
    secondaryContainer = Color(0xFF2B4E48),
    onSecondaryContainer = Color(0xFFC2ECE4),
    tertiary = Color(0xFFCEBDFF),
    onTertiary = Color(0xFF37118D),
    tertiaryContainer = Color(0xFF5133A6),
    onTertiaryContainer = Color(0xFFE7DEFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1413),
    onBackground = Color(0xFFDCE4E1),
    surface = Color(0xFF0D1413),
    onSurface = Color(0xFFDCE4E1),
    surfaceVariant = Color(0xFF3D4A47),
    onSurfaceVariant = Color(0xFFBDCAC5),
    surfaceTint = AuroraTealBright,
    inverseSurface = Color(0xFFDCE4E1),
    inverseOnSurface = Color(0xFF293230),
    outline = Color(0xFF879490),
    outlineVariant = Color(0xFF3D4A47),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333A38),
    surfaceDim = Color(0xFF0D1413),
    surfaceContainerLowest = Color(0xFF080E0D),
    surfaceContainerLow = Color(0xFF151C1A),
    surfaceContainer = Color(0xFF19201E),
    surfaceContainerHigh = Color(0xFF232A29),
    surfaceContainerHighest = Color(0xFF2E3533)
)

/**
 * Material's baseline tracking is tuned for marketing copy. Screens here are dense lists of
 * versions, ABIs, and byte counts, so headings are tightened and small labels are opened up
 * slightly to stay readable at a glance.
 */
private val LineHeightAlignment = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private val BaseTypography = Typography()

private val PureTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = LineHeightAlignment
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = LineHeightAlignment
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        lineHeightStyle = LineHeightAlignment
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        lineHeightStyle = LineHeightAlignment
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 21.sp),
    bodySmall = BaseTypography.bodySmall.copy(lineHeight = 18.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    ),
    labelSmall = BaseTypography.labelSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp
    )
)

/** Slightly softer than Material's default, so cards read as panels rather than tiles. */
private val PureShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** One spacing rhythm for the whole app, replacing the ad-hoc 7/10/14/21dp values. */
object PureSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Every screen's outer gutter. */
    val gutter = 20.dp

    /** Android's minimum comfortable tap area. */
    val touchTarget = 48.dp
}

/**
 * Package names, hashes, DPI values, and byte counts. Setting them in a monospaced face keeps
 * digits aligned down a list and visually separates machine values from prose.
 */
@Composable
fun technicalTextStyle(base: TextStyle = MaterialTheme.typography.bodySmall): TextStyle =
    base.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)

@Composable
fun AuroraPureTheme(
    themeMode: Int,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PureTypography,
        shapes = PureShapes,
        content = content
    )
}

/** Exposed for previews and for the status colour used by download and verification states. */
internal object PureAccents {
    val teal = AuroraTeal
}
