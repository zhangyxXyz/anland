package com.anland.design

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.anland.design.R
import com.anland.design.ThemeColor
import com.anland.design.ThemeMode
import com.materialkolor.dynamicColorScheme
import androidx.compose.ui.graphics.toArgb
import android.graphics.Color as AndroidColor
import com.anland.design.HsvColorDialog
import com.anland.design.NavigationSettingItem
import com.anland.design.SettingGroup
import com.anland.design.SwitchSettingItem

/** Appearance section kept structurally identical to MaterialDesignTmpl. */
@Composable
fun TemplateAppearance(
    dynamicColor: Boolean, themeMode: ThemeMode, themeColor: ThemeColor,
    customThemeColor: String, useCustomTheme: Boolean,
    onDynamicColorChange: (Boolean) -> Unit, onThemeModeChange: (ThemeMode) -> Unit,
    onThemeColorChange: (ThemeColor) -> Unit, onCustomThemeColorChange: (String) -> Unit,
    onCancelCustomTheme: () -> Unit, onBeginCustomTheme: () -> Unit, onOpenLanguage: () -> Unit,
    liquidGlass: Boolean, onLiquidGlassChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showCustomThemePicker by rememberSaveable { mutableStateOf(false) }
    var customThemeBeforePicker by rememberSaveable { mutableStateOf(customThemeColor) }
    val language = currentLanguageTags(context)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsContainer(stringResource(R.string.settings_appearance)) {
            ValueSetting(Icons.Outlined.Translate, stringResource(R.string.settings_language), languageLabel(language), onOpenLanguage)
            ValueSetting(Icons.Outlined.Contrast, stringResource(R.string.settings_theme_mode), themeModeLabel(themeMode)) { dialog = "theme" }
            SwitchSetting(Icons.Outlined.ColorLens, stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_summary), dynamicColor, onDynamicColorChange)
            SwitchSetting(Icons.Outlined.BlurOn, stringResource(R.string.settings_liquid_glass), stringResource(R.string.settings_liquid_glass_summary), liquidGlass, onLiquidGlassChange)
            Text(stringResource(R.string.settings_theme_color), Modifier.padding(start = 20.dp, top = 12.dp), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.settings_theme_color_summary), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val previewDark = MaterialTheme.colorScheme.background.luminance() < .5f
            ThemeColorRow(ThemeColor.entries, themeColor, useCustomTheme, previewDark, onThemeColorChange)
            val customPreview = remember(customThemeColor, previewDark) { previewColors(runCatching { Color(AndroidColor.parseColor(customThemeColor)) }.getOrDefault(Color(0xFF6750A4)), previewDark) }
            Row(Modifier.fillMaxWidth().clickable { onBeginCustomTheme(); customThemeBeforePicker = customThemeColor; showCustomThemePicker = true }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp, 38.dp).clip(RoundedCornerShape(19.dp)).then(if (useCustomTheme) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(19.dp)) else Modifier), contentAlignment = Alignment.Center) {
                    Box(Modifier.offset(x = (-18).dp).size(22.dp).background(customPreview.first, CircleShape)); Box(Modifier.size(22.dp).background(customPreview.second, CircleShape)); Box(Modifier.offset(x = 18.dp).size(22.dp).background(customPreview.third, CircleShape))
                    if (useCustomTheme) Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = Color.White)
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(stringResource(R.string.settings_custom_theme), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.settings_custom_theme_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(customThemeColor.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.Outlined.Colorize, null)
            }
        }
    }
    if (dialog == "theme") ChoiceDialog(
        title = stringResource(R.string.settings_theme_mode),
        labels = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)), selected = ThemeMode.entries.indexOf(themeMode), onDismiss = { dialog = null },
    ) { onThemeModeChange(ThemeMode.entries[it]); dialog = null }
    if (showCustomThemePicker) HsvColorDialog(
        initial = customThemeBeforePicker,
        dismiss = {
            onCancelCustomTheme()
            showCustomThemePicker = false
        },
        preview = onCustomThemeColorChange,
        select = {
            onCustomThemeColorChange(it)
            showCustomThemePicker = false
        },
    )
}

@Composable private fun languageLabel(tag: String):String {
    if(tag.isBlank())return stringResource(R.string.language_system)
    val locale=java.util.Locale.forLanguageTag(tag.substringBefore(','))
    return locale.getDisplayName(locale).replaceFirstChar{it.titlecase(locale)}
}
@Composable private fun themeModeLabel(mode: ThemeMode) = stringResource(when (mode) { ThemeMode.System -> R.string.theme_system; ThemeMode.Light -> R.string.theme_light; ThemeMode.Dark -> R.string.theme_dark })

@Composable
private fun ChoiceDialog(title: String, labels: List<String>, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val icons = listOf(Icons.Outlined.SettingsBrightness, Icons.Outlined.LightMode, Icons.Outlined.DarkMode)
    val descriptions = listOf(
        stringResource(R.string.theme_system_summary),
        stringResource(R.string.theme_light_summary),
        stringResource(R.string.theme_dark_summary),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Contrast, null, tint = MaterialTheme.colorScheme.primary) },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == selected
                    Surface(
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(13.dp), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Icon(
                                    icons[index],
                                    null,
                                    Modifier.padding(10.dp).size(22.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(descriptions[index], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isSelected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}

@Composable private fun SettingsContainer(title: String, content: @Composable ColumnScope.() -> Unit) = SettingGroup(title, content = content)
@Composable private fun SwitchSetting(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchSettingItem(title, detail, icon, checked, onCheckedChange = onChange)
@Composable private fun ClickSetting(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) = NavigationSettingItem(title, detail, icon = icon, onClick = onClick)
@Composable private fun ValueSetting(icon: ImageVector, title: String, value: String, onClick: () -> Unit) = NavigationSettingItem(title = title, value = value, icon = icon, onClick = onClick)

@Composable
private fun ThemeColorRow(
    colors: List<ThemeColor>,
    selected: ThemeColor,
    useCustomTheme: Boolean,
    previewDark: Boolean,
    onSelect: (ThemeColor) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        colors.forEach { color ->
            val preview = remember(color, previewDark) { previewColors(Color(color.argb), previewDark) }
            Box(
                modifier = Modifier.size(64.dp, 38.dp).clip(RoundedCornerShape(19.dp))
                    .background(if (selected == color) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .28f) else Color.Transparent)
                    .then(if (!useCustomTheme && selected == color) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = .58f), RoundedCornerShape(19.dp)) else Modifier)
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.offset(x = (-18).dp).size(22.dp).background(preview.first.copy(alpha = .82f), CircleShape))
                Box(Modifier.size(22.dp).background(preview.second.copy(alpha = .82f), CircleShape))
                Box(Modifier.offset(x = 18.dp).size(22.dp).background(preview.third.copy(alpha = .82f), CircleShape))
                if (!useCustomTheme && selected == color) Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = Color.White)
            }
        }
    }
}

private fun previewColors(seed: Color, dark: Boolean): Triple<Color, Color, Color> {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(seed.toArgb(), hsv)
    fun shifted(degrees: Float, saturationScale: Float, valueScale: Float): Color {
        val next = floatArrayOf(
            (hsv[0] + degrees + 360f) % 360f,
            (hsv[1] * saturationScale).coerceIn(.25f, 1f),
            (hsv[2] * valueScale).coerceIn(if (dark) .62f else .42f, if (dark) 1f else .88f),
        )
        return Color(AndroidColor.HSVToColor(next))
    }
    return Triple(shifted(0f, 1f, if (dark) 1.18f else .92f), shifted(24f, .72f, if (dark) 1.08f else .82f), shifted(-32f, .62f, if (dark) 1.12f else .86f))
}
