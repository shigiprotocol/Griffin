package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Griffin brand colors, from the logo: a deep classic navy and the griffin's orange.
val GriffinNavy = Color(0xFF000826) // Deep navy, a shade deeper than the logo's
val GriffinNavyRaised = Color(0xFF0B1A45) // Navy, one step up for surfaces on the navy
val GriffinOrange = Color(0xFFF66303) // Griffin orange
val GriffinOrangeLight = Color(0xFFFF8A3D) // Orange, lighter for text on OLED black
val GriffinOnNavyMuted = Color(0xFFAEB8D4) // Light navy gray, text on navy that is not selected
val OledBlack = Color(0xFF000000) // True black, so OLED pixels stay off

private val LightColor = lightColorScheme(
    primary = Color(0xFF0B1F5C), // Navy
    onPrimary = Color(0xFFFFFFFF), // White
    primaryContainer = Color(0xFFDDE3FF), // Pale Navy
    onPrimaryContainer = GriffinNavy,
    secondary = GriffinOrange,
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFFFE3D1), // Pale Orange
    onSecondaryContainer = Color(0xFF341100), // Dark Brown
    tertiary = Color(0xFF009966), // Green
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFA0F2D0), // Light Green
    onTertiaryContainer = Color(0xFF00201A), // Dark Teal
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFFFFFFF), // White
    onBackground = Color(0xFF0E1324), // Navy Black
    surface = Color(0xFFFFFFFF), // White
    onSurface = Color(0xFF0E1324), // Navy Black
    surfaceVariant = Color(0xFFE2E6F2), // Light Navy Gray
    onSurfaceVariant = Color(0xFF444B60), // Dark Navy Gray
    outline = Color(0xFF747B91), // Navy Gray
    outlineVariant = Color(0xFFC6CCDD), // Light Navy Gray
    inverseSurface = Color(0xFF0E1324), // Navy Black
    inverseOnSurface = Color(0xFFF1F3FA), // Very Light Gray
    inversePrimary = Color(0xFFB7C4FF), // Light Navy
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF0B1F5C), // Navy
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFF6F7FB), // Very Light Gray
    surfaceContainer = Color(0xFFF0F2F8), // Light Gray
    surfaceContainerHigh = Color(0xFFEAEDF5), // Light Gray
    surfaceContainerHighest = Color(0xFFE4E8F2), // Light Gray
)

// Griffin: OLED black behind everything, navy for what stands on it, orange for what is active.
private val DarkColor = darkColorScheme(
    primary = GriffinOrangeLight,
    onPrimary = GriffinNavy,
    primaryContainer = GriffinNavyRaised,
    onPrimaryContainer = Color(0xFFFFDCC7), // Pale Orange
    secondary = GriffinOrange,
    onSecondary = GriffinNavy,
    secondaryContainer = Color(0xFF13285E), // Navy
    onSecondaryContainer = Color(0xFFFFB68A), // Light Orange
    tertiary = Color(0xFF83D6B5), // Mint Green
    onTertiary = Color(0xFF00382E), // Dark Teal
    tertiaryContainer = Color(0xFF005143), // Teal
    onTertiaryContainer = Color(0xFFA0F2D0), // Light Green
    error = Color(0xFFFFB4AB), // Light Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = OledBlack,
    onBackground = Color(0xFFE8ECF7), // Light Gray
    surface = OledBlack,
    onSurface = Color(0xFFE8ECF7), // Light Gray
    surfaceVariant = Color(0xFF111B36), // Dark Navy
    onSurfaceVariant = Color(0xFFB4BED8), // Light Navy Gray
    outline = Color(0xFF5A6684), // Navy Gray
    outlineVariant = Color(0xFF1C2848), // Dark Navy
    inverseSurface = Color(0xFFE8ECF7), // Light Gray
    inverseOnSurface = GriffinNavy,
    inversePrimary = GriffinOrange,
    scrim = OledBlack,
    surfaceTint = OledBlack, // No tint, so raised surfaces stay black
    surfaceContainerLowest = OledBlack,
    surfaceContainerLow = Color(0xFF030817), // Navy Black
    surfaceContainer = Color(0xFF050C22), // Navy Black
    surfaceContainerHigh = Color(0xFF08122E), // Deep Navy
    surfaceContainerHighest = Color(0xFF0C1839), // Deep Navy
)

/**
 * Griffin: a dynamic dark scheme on OLED black, whatever the wallpaper's colors: the background and the surfaces the
 * screens lay out on are true black.
 */
private fun androidx.compose.material3.ColorScheme.onOledBlack() = copy(
    background = OledBlack,
    surface = OledBlack,
    surfaceContainerLowest = OledBlack,
    surfaceTint = OledBlack,
    scrim = OledBlack,
)

// Semantic Colors
val colorPing = Color(0xFF009966) // Green
val colorPingRed = Color(0xFFFF0099) // Pink Red
val colorConfigType = GriffinOrange
val colorFabActive = GriffinOrange
val colorFabInactiveLight = Color(0xFF0B1F5C) // Navy
val colorFabInactiveDark = Color(0xFF1C2A55) // Navy
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF16213F) // Dark Navy

// Toast Colors 85%
val toastNormalBgLight = Color(0xD9353A3E) // Dark Gray
val toastNormalBgDark = Color(0xE60B1A45) // Navy
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xD93F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    /** Griffin: off by default, so the navy and orange of the logo show rather than the wallpaper's colors. */
    const val DEFAULT_DYNAMIC_COLOR = false

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context).onOledBlack() else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
