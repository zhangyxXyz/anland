package com.anlandnext

import android.content.Context
import android.os.Bundle
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anland.design.*
import com.anlandnext.awl.Awl
import com.anlandnext.awl.WindowTaskService
import com.anlandnext.awl.AwlWindowActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Window behavior settings.
 * IME mode stays in the APK-local "awl" SharedPreferences, read by AwlWindowActivity.
 * Display zoom, scaling, auto-attach and backend remain daemon config.json values
 * read/written over Binder. Compose never maintains a second persistent config copy.
 *
 * Opening this page only reads settings. Switch/slider callbacks represent user
 * actions; the initial state must never write back to the daemon.
 *
 * Task removal can request a graceful close. Backgrounding, process death and
 * configuration changes still keep Linux windows alive; they are not Close.
 */
class WlSettingsActivity : MainActivity() {
    override val initialTab=1
    // Existing window-menu links enter the Configuration tab directly.
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WindowSettings(state:WindowState) {
    val context=LocalContext.current
    val prefs=context.getSharedPreferences("awl",Context.MODE_PRIVATE)
    // Re-read on display/configuration changes, including rotation. These are
    // pixels on this Activity's display, not dp or the IME-reduced content area.
    val configuration=LocalConfiguration.current
    val screenSize=remember(context,configuration) { screenSizePixels(context) }
    var ime by remember { mutableIntStateOf(prefs.getInt("ime_mode",0)) }
    var closeOnSwipe by remember { mutableStateOf(prefs.getBoolean(WindowTaskService.PREF,false)) }
    val showContainer=rememberShowContainerName()
    Page {
        Text(stringResource(R.string.config_shared_help),style=MaterialTheme.typography.bodyMedium)
        if(!state.connected) Text(stringResource(R.string.status_daemon_unreachable),color=MaterialTheme.colorScheme.error)
        SettingGroup(stringResource(R.string.config_tasks)) {
            AutoLaunch(state)
            SettingItem(stringResource(R.string.close_on_swipe),description=stringResource(R.string.close_on_swipe_help),descriptionMaxLines=8,
                trailingContent={Switch(closeOnSwipe,{closeOnSwipe=it;prefs.edit().putBoolean(WindowTaskService.PREF,it).apply();WindowTaskService.sync(context)})})
            SettingItem(stringResource(R.string.task_container_name),description=stringResource(R.string.task_container_name_help),descriptionMaxLines=8,
                trailingContent={Switch(showContainer,{prefs.edit().putBoolean(AwlWindowActivity.SHOW_CONTAINER_NAME,it).apply()})})
        }
        // #31: arbitrary daemon-side ratio, broadcast as preferred_scale to clients.
        // Preserve the original 200 ms drag debounce plus immediate release/preset.
        // Reading back config during a drag must not reset the user's current thumb position.
        SettingGroup(stringResource(R.string.config_display)) {
            SettingItem(stringResource(R.string.hide_titlebars),description=stringResource(R.string.hide_titlebars_help),descriptionMaxLines=8,
                trailingContent={Switch(state.config["hide_decorations"]==1,{state.set("hide_decorations",if(it)1 else 0)},enabled=state.connected && !state.writing && (state.config["hide_decorations"]?:-1)>=0)})
            Text(stringResource(R.string.zoom_title),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.zoom_help),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium)
            val actual=state.config["zoom"]?.takeIf{it in 50..300}?:100
            var zoom by remember { mutableFloatStateOf(actual.toFloat()) }
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(actual,dragging) { if(!dragging)zoom=actual.toFloat() }
            LaunchedEffect(zoom,dragging) { if(dragging) { delay(200); state.set("zoom",zoom.toInt()) } }
            Text("${zoom.toInt()}%",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.headlineMedium)
            Slider(zoom,{dragging=true;zoom=it},Modifier.padding(horizontal=16.dp),enabled=state.connected,valueRange=50f..300f,onValueChangeFinished={state.set("zoom",zoom.toInt());dragging=false})
            FlowRow(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(100,150,175,200,250).forEach { pct->FilterChip(actual==pct,{state.set("zoom",pct)},label={Text("$pct%")},enabled=state.connected && !state.writing) } }
            // X11 clients receive the zoom-adjusted X size on resize; the existing
            // renderer then scales their buffer over the Android window.
            SettingItem(stringResource(R.string.xwayland_scale),trailingContent={Switch(state.config["xwayland_scale"]==1,{state.set("xwayland_scale",if(it)1 else 0)},enabled=state.connected && !state.writing)})
            HorizontalDivider(Modifier.padding(horizontal=20.dp))
            Text(stringResource(R.string.scale_mode_tip),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.titleSmall)
            listOf(R.string.scale_mode_stretch,R.string.scale_mode_fit,R.string.scale_mode_center).forEachIndexed { index,res->SettingItem(stringResource(res),enabled=state.connected && !state.writing,onClick={state.set("scale_mode",index)},trailingContent={RadioButton(state.config["scale_mode"]==index,null)}) }
            HorizontalDivider(Modifier.padding(horizontal=20.dp))
            Text(stringResource(R.string.advanced_title),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.titleSmall)
            // #33: first-frame configure placeholder only; new windows use this until
            // Android supplies a measured surface. It is not the fullscreen target.
            // Values must remain within the domains accepted by the daemon.
            var width by remember(state.config["init_w"]) { mutableStateOf((state.config["init_w"]?.takeIf{it>0}?:800).toString()) }
            var height by remember(state.config["init_h"]) { mutableStateOf((state.config["init_h"]?.takeIf{it>0}?:600).toString()) }
            Text(stringResource(R.string.init_size_tip),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(width,{width=it},Modifier.weight(1f),label={Text(stringResource(R.string.init_size_w))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(height,{height=it},Modifier.weight(1f),label={Text(stringResource(R.string.init_size_h))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            FlowRow(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(800 to 600,1024 to 768,1280 to 720,1920 to 1080).forEach { (w,h) ->
                    FilterChip(width==w.toString() && height==h.toString(),{width=w.toString();height=h.toString();state.set(mapOf("init_w" to w,"init_h" to h))},label={Text("$w×$h")},enabled=state.connected && !state.writing)
                }
                val (screenWidth,screenHeight)=screenSize
                FilterChip(
                    selected=width==screenWidth.toString() && height==screenHeight.toString(),
                    onClick={
                        // Resolve again at the tap; setting this preset is a one-off
                        // write, never automatic synchronization with the display.
                        val (w,h)=screenSizePixels(context)
                        if(w in 100..7680 && h in 100..4320) {
                            width=w.toString();height=h.toString()
                            state.set(mapOf("init_w" to w,"init_h" to h))
                        }
                    },
                    label={Text(stringResource(R.string.init_size_screen,screenWidth,screenHeight))},
                    leadingIcon={Icon(Icons.Outlined.FitScreen,null,Modifier.size(18.dp))},
                    colors=FilterChipDefaults.filterChipColors(
                        containerColor=MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor=MaterialTheme.colorScheme.onTertiaryContainer,
                        iconColor=MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    enabled=state.connected && !state.writing && screenWidth in 100..7680 && screenHeight in 100..4320
                )
            }
            TextButton(onClick={state.set(mapOf("init_w" to width.toInt(),"init_h" to height.toInt()))},enabled=state.connected && !state.writing && (width.toIntOrNull()?:0) in 100..7680 && (height.toIntOrNull()?:0) in 100..4320,modifier=Modifier.padding(horizontal=16.dp)){Text(stringResource(R.string.apply))}
            // SC/HWC hands Wayland layers to SurfaceFlinger; GL renders per window.
            // Already-attached windows retain their backend until the next attachment.
            SettingItem(stringResource(R.string.sc_backend),description=stringResource(R.string.sc_backend_tip),descriptionMaxLines=6,trailingContent={Switch(state.config["sc_enabled"]==1,{state.set("sc_enabled",if(it)1 else 0)},enabled=state.connected && !state.writing)})
        }
        SettingGroup(stringResource(R.string.config_input)) {
            Text(stringResource(R.string.ime_mode_tip),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium)
            listOf(R.string.ime_mode_inset,R.string.ime_mode_overlay).forEachIndexed { index,res -> SettingItem(stringResource(res),onClick={ime=index;prefs.edit().putInt("ime_mode",index).apply()},trailingContent={RadioButton(ime==index,null)}) }
        }
    }
}

/** Observe the shared preference so restored pages and live lists agree. */
@Composable
internal fun rememberShowContainerName():Boolean {
    val context=LocalContext.current
    val prefs=remember(context) { context.getSharedPreferences("awl",Context.MODE_PRIVATE) }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(AwlWindowActivity.SHOW_CONTAINER_NAME,false)) }
    DisposableEffect(prefs) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if(key==null || key==AwlWindowActivity.SHOW_CONTAINER_NAME)
                enabled=prefs.getBoolean(AwlWindowActivity.SHOW_CONTAINER_NAME,false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled=prefs.getBoolean(AwlWindowActivity.SHOW_CONTAINER_NAME,false)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@Suppress("DEPRECATION")
private fun screenSizePixels(context:Context):Pair<Int,Int> {
    val manager=context.getSystemService(WindowManager::class.java)
    if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R) {
        val bounds=manager.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }
    val metrics=DisplayMetrics()
    manager.defaultDisplay.getRealMetrics(metrics)
    return metrics.widthPixels to metrics.heightPixels
}
