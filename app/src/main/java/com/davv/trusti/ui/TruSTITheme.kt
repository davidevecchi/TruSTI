package com.davv.trusti.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary              = Color(0xFFC7273A),
    onPrimary            = Color(0xFFFFFFFF),
    primaryContainer     = Color(0xFFFFDAD9),
    onPrimaryContainer   = Color(0xFF40000C),
    secondary            = Color(0xFF775658),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFFFFDADE),
    onSecondaryContainer = Color(0xFF2C1517),
    background           = Color(0xFFFFFBFF),
    onBackground         = Color(0xFF201A1A),
    surface              = Color(0xFFFFFBFF),
    onSurface            = Color(0xFF201A1A),
    surfaceVariant       = Color(0xFFF5DDDE),
    onSurfaceVariant     = Color(0xFF534344),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFFEF1F1),
    surfaceContainer        = Color(0xFFF9ECEC),
    surfaceContainerHigh    = Color(0xFFF3E6E6),
    surfaceContainerHighest = Color(0xFFECE0E0),
    outline              = Color(0xFF857374),
    outlineVariant       = Color(0xFFD8C2C2),
    inverseSurface       = Color(0xFF362F2F),
    inverseOnSurface     = Color(0xFFFBEDED),
    inversePrimary       = Color(0xFFFFB3B1),
    error                = Color(0xFFBA1A1A),
    onError              = Color(0xFFFFFFFF),
    errorContainer       = Color(0xFFFFDAD6),
    onErrorContainer     = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary              = Color(0xFFFFB3B1),
    onPrimary            = Color(0xFF68000F),
    primaryContainer     = Color(0xFF930019),
    onPrimaryContainer   = Color(0xFFFFDAD9),
    secondary            = Color(0xFFE7BDBF),
    onSecondary          = Color(0xFF44202A),
    secondaryContainer   = Color(0xFF5D3540),
    onSecondaryContainer = Color(0xFFFFDADE),
    background           = Color(0xFF201A1A),
    onBackground         = Color(0xFFECE0E0),
    surface              = Color(0xFF201A1A),
    onSurface            = Color(0xFFECE0E0),
    surfaceVariant       = Color(0xFF534344),
    onSurfaceVariant     = Color(0xFFD8C2C2),
    surfaceContainerLowest  = Color(0xFF1A1414),
    surfaceContainerLow     = Color(0xFF2B2424),
    surfaceContainer        = Color(0xFF322B2B),
    surfaceContainerHigh    = Color(0xFF3D3535),
    surfaceContainerHighest = Color(0xFF484040),
    outline              = Color(0xFFA08C8D),
    outlineVariant       = Color(0xFF534344),
    inverseSurface       = Color(0xFFECE0E0),
    inverseOnSurface     = Color(0xFF362F2F),
    inversePrimary       = Color(0xFFC7273A),
    error                = Color(0xFFFFB4AB),
    onError              = Color(0xFF690005),
    errorContainer       = Color(0xFF93000A),
    onErrorContainer     = Color(0xFFFFDAD6),
)

@Composable
fun TruSTITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TruSTITypography,
        content = content
    )
}