package dk.azp.jellybook.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FA6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFDDB3),
    onSecondaryContainer = Color(0xFF2C1600),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCDBDFF),
    onPrimary = Color(0xFF370B7A),
    primaryContainer = Color(0xFF4E2B95),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFFFFB95C),
    secondaryContainer = Color(0xFF693F00),
    onSecondaryContainer = Color(0xFFFFDDB3),
)

@Composable
fun JellybookTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
