package com.ghplus.patcher.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Persisted theme preference. Cycles System -> Light -> Dark -> System. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }
}

/** Tiny SharedPreferences-backed store for the theme mode. */
object ThemePrefs {
    private const val PREFS = "ghplus_patcher_prefs"
    private const val KEY_THEME = "theme_mode"

    fun load(context: Context): ThemeMode {
        val name = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(name!!) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A6FF8),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4A5560),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1E),
    onSurface = Color(0xFF1A1C1E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CB6FF),
    onPrimary = Color(0xFF12233A),
    secondary = Color(0xFFB9C3CF),
    background = Color(0xFF131416),
    surface = Color(0xFF1E2024),
    onBackground = Color(0xFFE3E3E6),
    onSurface = Color(0xFFE3E3E6),
)

@Composable
fun AppTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
