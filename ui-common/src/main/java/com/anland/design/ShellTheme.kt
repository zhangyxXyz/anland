package com.anland.design

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme

@Composable
fun ShellTheme(themeMode: ThemeMode, dynamicColor: Boolean, themeColor: ThemeColor, customThemeColor: String, useCustomTheme: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkMode = when (themeMode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val seed = if (useCustomTheme) Color(runCatching { android.graphics.Color.parseColor(customThemeColor) }.getOrDefault(themeColor.argb.toInt())) else Color(themeColor.argb)
    val colors = remember(dynamicColor, darkMode, themeColor, customThemeColor, useCustomTheme, context) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= 31 && darkMode -> dynamicDarkColorScheme(context)
            dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
            else -> dynamicColorScheme(seedColor = seed, isDark = darkMode, isAmoled = false)
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
