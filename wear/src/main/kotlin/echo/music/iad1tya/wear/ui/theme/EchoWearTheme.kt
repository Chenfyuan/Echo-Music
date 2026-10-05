package echo.music.iad1tya.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Wear OS Material 3 theme.
 *
 * Wear OS displays are OLED, so the scheme is dark-only with a true-black background, and tinted
 * from the same seed colour (0xFFED5564) the phone app falls back to.
 */
private val EchoColorScheme =
  ColorScheme(
    primary = Color(0xFFFFB3B5),
    primaryDim = Color(0xFFF29A9D),
    primaryContainer = Color(0xFF7A1A2A),
    onPrimary = Color(0xFF5C0016),
    onPrimaryContainer = Color(0xFFFFDADA),
    secondary = Color(0xFFE6BDBE),
    secondaryDim = Color(0xFFD1AAAB),
    secondaryContainer = Color(0xFF4F3638),
    onSecondary = Color(0xFF45292B),
    onSecondaryContainer = Color(0xFFFFDADA),
    tertiary = Color(0xFFE8C08E),
    tertiaryDim = Color(0xFFD3AC7B),
    tertiaryContainer = Color(0xFF4A3414),
    onTertiary = Color(0xFF412C0B),
    onTertiaryContainer = Color(0xFFFFDDB6),
    surfaceContainerLow = Color(0xFF141010),
    surfaceContainer = Color(0xFF1F1A1A),
    surfaceContainerHigh = Color(0xFF2A2424),
    onSurface = Color(0xFFEDE0DF),
    onSurfaceVariant = Color(0xFFD8C2C2),
    outline = Color(0xFFA08C8C),
    outlineVariant = Color(0xFF524343),
    background = Color.Black,
    onBackground = Color(0xFFEDE0DF),
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFFE69A92),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
  )

@Composable
fun EchoWearTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = EchoColorScheme, content = content)
}
