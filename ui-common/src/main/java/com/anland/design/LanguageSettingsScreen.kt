package com.anland.design

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.anland.design.R
import java.util.Locale
import android.content.res.Resources
import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import com.anland.design.SettingItemCard

private data class LanguageOption(val tag: String, val nativeName: String, val secondaryName: String)
const val LANGUAGE_PREFERENCES = "language_settings"
const val LANGUAGE_TAG = "language_tag"

@Composable
fun LanguageSettingsScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    var current by remember(context) { mutableStateOf(currentLanguageTags(context)) }
    LaunchedEffect(configuration) { current = currentLanguageTags(context) }
    val selectLanguage: (String) -> Unit = { tag ->
        if (current != tag) {
            current = tag
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
            } else {
                context.getSharedPreferences(LANGUAGE_PREFERENCES, android.content.Context.MODE_PRIVATE)
                    .edit().putString(LANGUAGE_TAG, tag).apply()
                activity?.suppressLocaleTransition()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                activity?.suppressLocaleTransition()
            }
        }
    }
    val options = remember { supportedLanguages() }
    val systemLocale = remember { Resources.getSystem().configuration.locales[0] }
    val systemOption = remember(systemLocale) {
        LanguageOption(systemLocale.toLanguageTag(), systemLocale.getDisplayName(systemLocale).replaceFirstChar { it.titlecase(systemLocale) }, systemLocale.getDisplayName(Locale.ENGLISH))
    }
    val filtered = options.filter { query.isBlank() || it.nativeName.contains(query, true) || it.secondaryName.contains(query, true) || it.tag.contains(query, true) }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                placeholder = { Text(stringResource(R.string.language_search)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp),
            )
        }
        if (query.isBlank()) {
            item { SectionLabel(stringResource(R.string.language_suggested)) }
            item { LanguageRow(LanguageOption("", stringResource(R.string.language_system), stringResource(R.string.language_system_detected, systemOption.nativeName)), current, selectLanguage) }
            item { LanguageRow(systemOption, current, selectLanguage) }
            item { SectionLabel(stringResource(R.string.language_all)) }
        }
        items(filtered, key = { "all-${it.tag}" }) { option ->
            LanguageRow(option, current, selectLanguage)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
}

@Composable
private fun LanguageRow(option: LanguageOption, current: String, onSelect: (String) -> Unit) {
            val selected = if (option.tag.isBlank()) current.isBlank() else current.equals(option.tag, true)
            val selectLanguage = { if (!selected) onSelect(option.tag) }
            SettingItemCard(
                title = option.nativeName,
                description = option.secondaryName.takeIf { it.isNotBlank() && it != option.nativeName },
                icon = Icons.Outlined.Language,
                selected = selected,
                onClick = selectLanguage,
                trailingContent = { RadioButton(selected, onClick = selectLanguage) },
            )
}

internal fun currentLanguageTags(context: android.content.Context): String =
    if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    else AppCompatDelegate.getApplicationLocales().toLanguageTags()

private fun Activity.suppressLocaleTransition() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

private fun supportedLanguages(): List<LanguageOption> {
    val tags = listOf("zh-CN", "zh-TW", "en")
    return tags.map { tag ->
        val locale = Locale.forLanguageTag(tag)
        LanguageOption(tag, locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }, locale.getDisplayName(Locale.ENGLISH))
    }.sortedBy { it.nativeName.lowercase() }
}
