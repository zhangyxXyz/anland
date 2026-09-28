package com.anlandnext

import android.content.Context
import android.os.Bundle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anland.design.*
import com.anlandnext.awl.Awl
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
 * Swiping away / killing the background is fixed to minimize-and-keep-alive.
 * Explicit Close remains on the window-list menu; window exit policy lives in the daemon.
 */
class WlSettingsActivity : MainActivity() { override val initialTab=1 }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WindowSettings(state:WindowState) {
    val prefs=LocalContext.current.getSharedPreferences("awl",Context.MODE_PRIVATE)
    var ime by remember { mutableIntStateOf(prefs.getInt("ime_mode",0)) }
    Page {
        if(!state.connected) Text(stringResource(R.string.status_daemon_unreachable),color=MaterialTheme.colorScheme.error)
        SettingGroup(stringResource(R.string.launch_behavior)) { AutoLaunch(state) }
        // #31: arbitrary daemon-side ratio, broadcast as preferred_scale to clients.
        // Preserve the original 200 ms drag debounce plus immediate release/preset.
        // Reading back config during a drag must not reset the user's current thumb position.
        SettingGroup(stringResource(R.string.zoom_title)) {
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
        }
        SettingGroup(stringResource(R.string.ime_mode_tip)) {
            listOf(R.string.ime_mode_inset,R.string.ime_mode_overlay).forEachIndexed { index,res -> SettingItem(stringResource(res),onClick={ime=index;prefs.edit().putInt("ime_mode",index).apply()},trailingContent={RadioButton(ime==index,null)}) }
        }
        // #34: fixed-size clients under Android resize: stretch / fit / centered 1:1.
        SettingGroup(stringResource(R.string.scale_mode_tip)) {
            listOf(R.string.scale_mode_stretch,R.string.scale_mode_fit,R.string.scale_mode_center).forEachIndexed { index,res->SettingItem(stringResource(res),enabled=state.connected && !state.writing,onClick={state.set("scale_mode",index)},trailingContent={RadioButton(state.config["scale_mode"]==index,null)}) }
        }
        SettingGroup(stringResource(R.string.advanced_title)) {
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
            }
            TextButton(onClick={state.set(mapOf("init_w" to width.toInt(),"init_h" to height.toInt()))},enabled=state.connected && !state.writing && (width.toIntOrNull()?:0) in 100..7680 && (height.toIntOrNull()?:0) in 100..4320,modifier=Modifier.padding(horizontal=16.dp)){Text(stringResource(R.string.apply))}
            // SC/HWC hands Wayland layers to SurfaceFlinger; GL renders per window.
            // Already-attached windows retain their backend until the next attachment.
            SettingItem(stringResource(R.string.sc_backend),description=stringResource(R.string.sc_backend_tip),descriptionMaxLines=6,trailingContent={Switch(state.config["sc_enabled"]==1,{state.set("sc_enabled",if(it)1 else 0)},enabled=state.connected && !state.writing)})
        }
    }
}
