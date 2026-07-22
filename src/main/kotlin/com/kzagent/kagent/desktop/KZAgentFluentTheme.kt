package com.kzagent.kagent.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Mica
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors

private val FluentLightColorScheme = lightColorScheme(
    primary = Color(0xFF0F6CBD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFA),
    onPrimaryContainer = Color(0xFF0C3B5E),
    secondary = Color(0xFF4F6B85),
    onSecondary = Color.White,
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF9F9F9),
    surfaceContainerHigh = Color(0xFFF0F0F0),
    background = Color(0xFFF3F3F3),
    onBackground = Color(0xFF1B1B1B),
    onSurface = Color(0xFF1B1B1B),
    onSurfaceVariant = Color(0xFF616161),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFC42B1C),
    errorContainer = Color(0xFFFDE7E9),
    onErrorContainer = Color(0xFF6E0811),
)

private val FluentDarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A8E8),
    onPrimary = Color(0xFF082338),
    primaryContainer = Color(0xFF143F5F),
    onPrimaryContainer = Color(0xFFDCEBFA),
    secondary = Color(0xFFAFC7DC),
    onSecondary = Color(0xFF183247),
    surface = Color(0xFF2C2C2C),
    surfaceVariant = Color(0xFF292929),
    surfaceContainer = Color(0xFF252525),
    surfaceContainerHigh = Color(0xFF333333),
    background = Color(0xFF202020),
    onBackground = Color(0xFFF5F5F5),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFFC8C8C8),
    outline = Color(0xFF686868),
    outlineVariant = Color(0xFF454545),
    error = Color(0xFFFF99A4),
    errorContainer = Color(0xFF5A1A20),
    onErrorContainer = Color(0xFFFFD9DD),
)

private val FluentTypography = Typography(
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
)

private val FluentShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * Bridges Compose Fluent and Material 3 while migration is incremental.
 *
 * Compose Fluent owns the application material/background context. Material 3 is
 * intentionally nested so the existing markdown renderer and dialogs retain a
 * complete theme instead of falling back to library defaults.
 */
@OptIn(ExperimentalFluentApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun KZAgentFluentTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val fluentColors = remember(darkTheme) {
        if (darkTheme) darkColors(Color(0xFF0F6CBD)) else lightColors(Color(0xFF0F6CBD))
    }

    FluentTheme(
        colors = fluentColors,
        compactMode = true,
        useAcrylicPopup = false,
    ) {
        // Compose Fluent v0.1.0 installs a TextContextMenu compiled against
        // Compose 1.8. Compose 1.11 changed the action ABI, so keep Fluent's
        // other locals but restore the current Compose implementation here.
        CompositionLocalProvider(
            LocalTextContextMenu provides TextContextMenu.Default,
        ) {
            MaterialTheme(
                colorScheme = if (darkTheme) FluentDarkColorScheme else FluentLightColorScheme,
                typography = FluentTypography,
                shapes = FluentShapes,
            ) {
                Mica(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}
