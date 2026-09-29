package com.anland.design

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Request-scoped appearance. Never writes the receiving app's preferences. */
object LaunchTheme {
    const val EXTRA = "anland.launch.appearance"

    @JvmStatic fun capture(context: Context): Bundle {
        val appearance = Appearance(context)
        val dark = when (appearance.mode) {
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
            ThemeMode.System -> context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        return Bundle().apply {
            putString("mode", if (dark) "Dark" else "Light")
            putBoolean("dynamic", appearance.dynamic)
            putString("color", appearance.color.name)
            putString("custom", appearance.custom)
            putBoolean("useCustom", appearance.useCustom)
        }
    }
}

@Composable
fun WithLaunchTheme(snapshot: Bundle?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val values = remember(snapshot) { snapshot ?: LaunchTheme.capture(context) }
    val mode = runCatching { ThemeMode.valueOf(values.getString("mode").orEmpty()) }.getOrDefault(ThemeMode.System)
    val color = runCatching { ThemeColor.valueOf(values.getString("color").orEmpty()) }.getOrDefault(ThemeColor.Teal)
    // A floating handoff leaves the underlying page's system bars untouched.
    ShellTheme(mode, values.getBoolean("dynamic", true), color,
        values.getString("custom", "#6750A4"), values.getBoolean("useCustom"), content)
}
