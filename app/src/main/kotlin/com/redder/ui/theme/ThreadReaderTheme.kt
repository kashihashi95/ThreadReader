package com.redder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Static light scheme: a warm orange-red accent on neutral surfaces, used below Android 12. */
internal val ThreadReaderLightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFFB02F00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBD1),
        onPrimaryContainer = Color(0xFF3B0900),
        secondary = Color(0xFF77574E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDBD1),
        onSecondaryContainer = Color(0xFF2C150F),
        tertiary = Color(0xFF6C5D2F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF5E1A7),
        onTertiaryContainer = Color(0xFF231B00),
        background = Color(0xFFFFFBFF),
        onBackground = Color(0xFF201A18),
        surface = Color(0xFFFFFBFF),
        onSurface = Color(0xFF201A18),
        surfaceVariant = Color(0xFFF5DED8),
        onSurfaceVariant = Color(0xFF53433F),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF1ED),
        surfaceContainer = Color(0xFFFCEAE5),
        surfaceContainerHigh = Color(0xFFF7E4DF),
        surfaceContainerHighest = Color(0xFFF1DFDA),
        outline = Color(0xFF85736E),
        outlineVariant = Color(0xFFD8C2BC),
    )

/** Static dark scheme matching [ThreadReaderLightColors]. */
internal val ThreadReaderDarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFFFB5A0),
        onPrimary = Color(0xFF5F1500),
        primaryContainer = Color(0xFF862200),
        onPrimaryContainer = Color(0xFFFFDBD1),
        secondary = Color(0xFFE7BDB2),
        onSecondary = Color(0xFF442A22),
        secondaryContainer = Color(0xFF5D4037),
        onSecondaryContainer = Color(0xFFFFDBD1),
        tertiary = Color(0xFFD8C58D),
        onTertiary = Color(0xFF3B2F05),
        tertiaryContainer = Color(0xFF534619),
        onTertiaryContainer = Color(0xFFF5E1A7),
        background = Color(0xFF201A18),
        onBackground = Color(0xFFEDE0DC),
        surface = Color(0xFF201A18),
        onSurface = Color(0xFFEDE0DC),
        surfaceVariant = Color(0xFF53433F),
        onSurfaceVariant = Color(0xFFD8C2BC),
        surfaceContainerLowest = Color(0xFF1A110F),
        surfaceContainerLow = Color(0xFF231917),
        surfaceContainer = Color(0xFF271D1B),
        surfaceContainerHigh = Color(0xFF322825),
        surfaceContainerHighest = Color(0xFF3D3230),
        outline = Color(0xFFA08C87),
        outlineVariant = Color(0xFF53433F),
    )

/**
 * App-wide Material 3 theme. Follows the system dark mode and uses the wallpaper-based dynamic
 * colors on Android 12+, falling back to the static ThreadReader schemes on older devices.
 */
@Composable
fun ThreadReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (darkTheme) {
            ThreadReaderDarkColors
        } else {
            ThreadReaderLightColors
        }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
