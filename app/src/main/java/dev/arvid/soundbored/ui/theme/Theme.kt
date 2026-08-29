package dev.arvid.soundbored.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Violet = Color(0xFF7C5CFF)
private val VioletDark = Color(0xFFB4A0FF)

private val LightColors = lightColorScheme(
    primary = Violet,
    secondary = Color(0xFF4F7CFF),
    tertiary = Color(0xFFE0567A),
)

private val DarkColors = darkColorScheme(
    primary = VioletDark,
    secondary = Color(0xFF9FBBFF),
    tertiary = Color(0xFFFF9EB4),
)

/** The colours soundboard buttons cycle through. */
val ClipPalette = listOf(
    Color(0xFF7C5CFF),
    Color(0xFF2FA8A0),
    Color(0xFFE0567A),
    Color(0xFFE08A2F),
    Color(0xFF4F7CFF),
    Color(0xFF5FA83E),
    Color(0xFFB1519E),
    Color(0xFF3F8FBF),
)

@Composable
fun SoundboredTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
