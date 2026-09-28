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
}

@Stable
class Appearance(context: Context) {
    private val prefs = context.getSharedPreferences("anland_appearance", Context.MODE_PRIVATE)
    var mode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("mode", "System")!!) }.getOrDefault(ThemeMode.System))
        private set
    var dynamic by mutableStateOf(prefs.getBoolean("dynamic", true)); private set
    var glass by mutableStateOf(prefs.getBoolean("glass", true)); private set
    var color by mutableStateOf(runCatching { ThemeColor.valueOf(prefs.getString("color", "Teal")!!) }.getOrDefault(ThemeColor.Teal)); private set
    fun mode(value: ThemeMode) { mode=value; prefs.edit().putString("mode",value.name).apply(); AppCompatDelegate.setDefaultNightMode(value.appCompatNightMode) }
    fun dynamic(value: Boolean) { dynamic=value; prefs.edit().putBoolean("dynamic",value).apply() }
    fun glass(value: Boolean) { glass=value; prefs.edit().putBoolean("glass",value).apply() }
    fun color(value: ThemeColor) { color=value; dynamic(false); prefs.edit().putString("color",value.name).apply() }
}

@Composable
fun WithAnlandTheme(content: @Composable (Appearance) -> Unit) {
    val context=LocalContext.current
    val appearance=remember { Appearance(context) }
    ShellTheme(appearance.mode, appearance.dynamic, appearance.color, "", false) {
        val surface=MaterialTheme.colorScheme.surface
        SideEffect {
            var owner=context
            while(owner is ContextWrapper && owner !is Activity)owner=owner.baseContext
            (owner as? Activity)?.window?.let { window ->
                // Custom theme mode can differ from the system mode: system bar
                // icons must follow the rendered surface, not the device setting.
                window.statusBarColor=surface.toArgb()
                window.navigationBarColor=surface.toArgb()
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
                actions: @Composable RowScope.() -> Unit = {}, content: @Composable (Int, (Int) -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    BackHandler(tab != 0) { tab=0 }
    val surface=MaterialTheme.colorScheme.surface
    val backdrop=rememberLayerBackdrop { drawRect(surface); drawContent() }
    BoxWithConstraints {
        val rail=maxWidth >= 840.dp
        Scaffold(topBar={ TopAppBar(title={ Text(destinations[tab].title) }, actions=actions) }, bottomBar={
            if (!rail) {
                if (appearance.glass) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=18.dp, vertical=6.dp), contentAlignment=Alignment.Center) {
                    LiquidGlassBottomBar(selectedIndex=tab, onSelected={tab=it}, backdrop=backdrop, tabsCount=destinations.size) {
                        destinations.forEachIndexed { index, d ->
                            LiquidGlassBottomBarItem(modifier=Modifier.defaultMinSize(minWidth=64.dp), onClick={tab=index}) {
                                Icon(d.icon,d.title)
                                Text(d.title,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else NavigationBar {
                    destinations.forEachIndexed { index,d -> NavigationBarItem(tab==index,{tab=index},icon={Icon(d.icon,null)},label={Text(d.title)}) }
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).then(if(appearance.glass) Modifier.layerBackdrop(backdrop) else Modifier)) {
                if(rail) NavigationRail {
                    Spacer(Modifier.weight(1f))
                    destinations.forEachIndexed { index,d -> NavigationRailItem(tab==index,{tab=index},icon={Icon(d.icon,null)},label={Text(d.title)}) }
                    Spacer(Modifier.weight(1f))
                }
                Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.TopCenter) {
                    Box(Modifier.widthIn(max=1040.dp).fillMaxSize()) { content(tab) { tab=it } }
                }
            }
        }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings(state: Appearance) {
    SettingGroup(stringResource(R.string.appearance)) {
        Row(Modifier.padding(horizontal=16.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode -> FilterChip(state.mode==mode,{state.mode(mode)},label={Text(stringResource(when(mode){ThemeMode.System->R.string.follow_system;ThemeMode.Light->R.string.light_theme;ThemeMode.Dark->R.string.dark_theme}))}) }
        }
        SettingItem(stringResource(R.string.dynamic_color),trailingContent={Switch(state.dynamic,state::dynamic)})
        SettingItem(stringResource(R.string.glass_navigation),trailingContent={Switch(state.glass,state::glass)})
        Text(stringResource(R.string.theme_color),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(ThemeColor.Teal,ThemeColor.Blue,ThemeColor.Purple,ThemeColor.Pink,ThemeColor.Orange).forEach { color ->
                FilterChip(!state.dynamic && state.color==color,{state.color(color)},label={Text("●",color=Color(color.argb))})
            }
        }
    }
}
