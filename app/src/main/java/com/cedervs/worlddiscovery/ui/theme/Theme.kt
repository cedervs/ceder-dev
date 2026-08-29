package com.cedervs.worlddiscovery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The three modes documented in product-spec.md §2. No manual switcher UI exists yet
 * (settings persistence is not built in this increment), but the theme itself already
 * supports all three: [WorldDiscoveryApp] simply defaults to [ThemeMode.SYSTEM].
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

private val LightColors = lightColorScheme(
    primary = DiscoveryOrange,
    secondary = DiscoveryOrangeDark,
)

private val DarkColors = darkColorScheme(
    primary = DiscoveryOrange,
    secondary = DiscoveryOrangeDark,
)

@Composable
fun WorldDiscoveryTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (useDarkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
