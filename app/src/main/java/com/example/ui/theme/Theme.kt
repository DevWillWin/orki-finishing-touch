package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = ForestGreenPrimary,
    onPrimary = PureWhite,
    primaryContainer = SageGreen,
    onPrimaryContainer = ForestGreenDeep,
    secondary = SlateTextSecondary,
    onSecondary = PureWhite,
    secondaryContainer = PaleSage,
    onSecondaryContainer = CharcoalTextPrimary,
    tertiary = AmberPro,
    onTertiary = PureWhite,
    background = IvoryBackground,
    onBackground = CharcoalTextPrimary,
    surface = PureWhite,
    onSurface = CharcoalTextPrimary,
    surfaceVariant = PaleSage,
    onSurfaceVariant = SlateTextSecondary,
    surfaceTint = ForestGreenPrimary,
    outline = WarmBorder,
    outlineVariant = WarmBorderSubtle,
    error = ErrorRed,
    onError = PureWhite,
    errorContainer = ErrorRedDark,
    onErrorContainer = ErrorRed
)

@Composable
fun OrkiTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
