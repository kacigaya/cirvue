package app.cirvue.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Dynamic color on Android 12+. Older versions use a teal scheme built from the icon color. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CirvueTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = remember(context, darkTheme) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkFallback
            else -> LightFallback
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

// Close to Material Theme Builder output for seed #0E5C52.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF006A60), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF051F1C),
    tertiary = Color(0xFF456179), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF), onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF4FBF8), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF8), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E1), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainer = Color(0xFFE9EFEC), surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
    inverseSurface = Color(0xFF2B3230), inverseOnSurface = Color(0xFFECF2EF),
    inversePrimary = Color(0xFF82D5C8),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF82D5C8), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9EF2E4),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFADCAE6), onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4960), onTertiaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513), onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2120), surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
    inverseSurface = Color(0xFFDDE4E1), inverseOnSurface = Color(0xFF2B3230),
    inversePrimary = Color(0xFF006A60),
)
