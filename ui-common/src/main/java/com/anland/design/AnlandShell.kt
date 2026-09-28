package com.anland.design

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anland.design.glass.LiquidGlassBottomBar
import com.anland.design.glass.LiquidGlassBottomBarItem
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Adapted from MaterialDesignTmpl MainShell: responsive navigation, shared appearance and feature slots. */
data class Destination(val title: String, val icon: ImageVector)

fun applySavedAppearance(context: Context) {
    val mode=Appearance(context).mode
    AppCompatDelegate.setDefaultNightMode(mode.appCompatNightMode)
    if(android.os.Build.VERSION.SDK_INT < 33) {
        val tag=context.getSharedPreferences(LANGUAGE_PREFERENCES,Context.MODE_PRIVATE).getString(LANGUAGE_TAG,"").orEmpty()
        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tag))
    }
}

@Stable
class Appearance(context: Context) {
    private val prefs = context.getSharedPreferences("anland_appearance", Context.MODE_PRIVATE)
    var mode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("mode", "System")!!) }.getOrDefault(ThemeMode.System))
        private set
    var dynamic by mutableStateOf(prefs.getBoolean("dynamic", true)); private set
    var glass by mutableStateOf(prefs.getBoolean("glass", true)); private set
    var color by mutableStateOf(runCatching { ThemeColor.valueOf(prefs.getString("color", "Teal")!!) }.getOrDefault(ThemeColor.Teal)); private set
    var custom by mutableStateOf(prefs.getString("custom", "#6750A4").orEmpty()); private set
    var useCustom by mutableStateOf(prefs.getBoolean("useCustom", false)); private set
    fun mode(value: ThemeMode) { mode=value; prefs.edit().putString("mode",value.name).apply(); AppCompatDelegate.setDefaultNightMode(value.appCompatNightMode) }
    fun dynamic(value: Boolean) { dynamic=value; prefs.edit().putBoolean("dynamic",value).apply() }
    fun glass(value: Boolean) { glass=value; prefs.edit().putBoolean("glass",value).apply() }
    fun color(value: ThemeColor) { color=value; useCustom=false; dynamic(false); prefs.edit().putString("color",value.name).putBoolean("useCustom",false).apply() }
    fun custom(value: String) { custom=value; useCustom=true; dynamic(false); prefs.edit().putString("custom",value).putBoolean("useCustom",true).apply() }
    fun restoreCustom(value:String, enabled:Boolean, dynamicValue:Boolean) {
        custom=value;useCustom=enabled;dynamic=dynamicValue
        prefs.edit().putString("custom",value).putBoolean("useCustom",enabled).putBoolean("dynamic",dynamicValue).apply()
    }
}

@Composable
fun WithAnlandTheme(content: @Composable (Appearance) -> Unit) {
    val context=LocalContext.current
    val appearance=remember { Appearance(context) }
    ShellTheme(appearance.mode, appearance.dynamic, appearance.color, appearance.custom, appearance.useCustom) {
        val surface=MaterialTheme.colorScheme.surface
        SideEffect {
            var owner=context
            while(owner is ContextWrapper && owner !is Activity)owner=owner.baseContext
            (owner as? Activity)?.window?.let { window ->
                // Custom theme mode can differ from the system mode: system bar
                // icons must follow the rendered surface, not the device setting.
                window.statusBarColor=surface.toArgb()
                window.navigationBarColor=surface.toArgb()
                if(android.os.Build.VERSION.SDK_INT >= 29) {
                    window.isStatusBarContrastEnforced=false
                    window.isNavigationBarContrastEnforced=false
                }
                WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars=surface.luminance()>0.5f
                    isAppearanceLightNavigationBars=surface.luminance()>0.5f
                }
            }
        }
        content(appearance)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnlandShell(destinations: List<Destination>, appearance: Appearance, initialTab: Int=0,
                brand: String="Anland", contextLabel: String="",
                secondaryTitle:String?=null, onSecondaryBack:()->Unit={},
                actions: @Composable RowScope.() -> Unit = {}, content: @Composable (Int, (Int) -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val pages=rememberSaveableStateHolder()
    BackHandler(secondaryTitle!=null) { onSecondaryBack() }
    BackHandler(secondaryTitle==null && tab != 0) { tab=0 }
    val surface=MaterialTheme.colorScheme.surface
    val backdrop=rememberLayerBackdrop { drawRect(surface); drawContent() }
    BoxWithConstraints {
        val rail=maxWidth >= 840.dp
        // Width follows the actual app window, including Android split screen.
        // Page state belongs to the destination, not to the rail/bottom-bar layout.
        Scaffold(containerColor=surface, bottomBar={
            if (!rail && secondaryTitle==null) {
                if (appearance.glass) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=18.dp, vertical=6.dp), contentAlignment=Alignment.Center) {
                    LiquidGlassBottomBar(selectedIndex=tab, onSelected={tab=it}, backdrop=backdrop, tabsCount=destinations.size) {
                        destinations.forEachIndexed { index, d ->
                            LiquidGlassBottomBarItem(modifier=Modifier.defaultMinSize(minWidth=64.dp), onClick={tab=index}) {
                                Icon(d.icon,d.title)
                                Text(d.title,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else NavigationBar(containerColor=surface,tonalElevation=0.dp) {
                    destinations.forEachIndexed { index,d -> NavigationBarItem(tab==index,{tab=index},icon={Icon(d.icon,null)},label={Text(d.title)}) }
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).then(if(appearance.glass) Modifier.layerBackdrop(backdrop) else Modifier)) {
                if(rail && secondaryTitle==null) Column(Modifier.width(224.dp).fillMaxHeight().padding(horizontal=16.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        SettingLeadingIcon(Icons.Outlined.Terminal)
                        Text(brand,style=MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    destinations.forEachIndexed { index,d ->
                        if(index==destinations.lastIndex) {
                            Spacer(Modifier.weight(1f))
                            if(contextLabel.isNotBlank()) Text(contextLabel,Modifier.padding(16.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        NavigationDrawerItem(label={Text(d.title)},selected=tab==index,onClick={tab=index},icon={Icon(d.icon,null)},shape=RoundedCornerShape(18.dp),
                            colors=NavigationDrawerItemDefaults.colors(unselectedContainerColor=surface,selectedContainerColor=MaterialTheme.colorScheme.primaryContainer))
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopAppBar(title={Text(secondaryTitle?:destinations[tab].title)},
                        navigationIcon={if(secondaryTitle!=null)IconButton(onClick=onSecondaryBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.design_back))}},
                        actions={if(secondaryTitle==null)actions()},windowInsets=WindowInsets(0,0,0,0),colors=TopAppBarDefaults.topAppBarColors(containerColor=surface,scrolledContainerColor=surface))
                    Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.TopCenter) {
                        Box(Modifier.widthIn(max=1440.dp).fillMaxSize()) {
                            pages.SaveableStateProvider(tab) { content(tab) { tab=it } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=920.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings(state: Appearance) {
    val context=LocalContext.current
    // Live color previews may change multiple preferences. Cancel restores all
    // three, including dynamic mode, rather than committing an accidental theme.
    var beforeColor by rememberSaveable{mutableStateOf(state.custom)}
    var beforeCustom by rememberSaveable{mutableStateOf(state.useCustom)}
    var beforeDynamic by rememberSaveable{mutableStateOf(state.dynamic)}
    TemplateAppearance(state.dynamic,state.mode,state.color,state.custom,state.useCustom,
        state::dynamic,state::mode,state::color,
        onCustomThemeColorChange=state::custom,
        onBeginCustomTheme={beforeColor=state.custom;beforeCustom=state.useCustom;beforeDynamic=state.dynamic},
        onCancelCustomTheme={state.restoreCustom(beforeColor,beforeCustom,beforeDynamic)},
        onOpenLanguage={context.startActivity(android.content.Intent(context,LanguageActivity::class.java))},
        liquidGlass=state.glass,onLiquidGlassChange=state::glass)
}
