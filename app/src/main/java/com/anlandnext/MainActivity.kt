package com.anlandnext

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WindowState : ViewModel() {
    var windows by mutableStateOf<List<Awl.WlWindow>>(emptyList()); private set
    var config by mutableStateOf<Map<String,Int>>(emptyMap()); private set
    var connected by mutableStateOf(false); private set
    var writing by mutableStateOf(false); private set
    var error by mutableStateOf(false)
    private val gate=Mutex()
    private val keys=listOf("auto_attach","zoom","scale_mode","xwayland_scale","init_w","init_h","sc_enabled")
    fun refresh() { viewModelScope.launch { gate.withLock {
            val snapshot=withContext(Dispatchers.IO) {
                val windows=Awl.getWindows()
                windows to keys.associateWith { WlBinder.configGet(it) }
            }
            // libawl subscription/ref-count operations belong to the main thread.
            // A daemon restart invalidates the previous event subscription.
            if(snapshot.first!=null) Awl.ensureSubscribed()
        connected=snapshot.first!=null; windows=snapshot.first.orEmpty(); config=snapshot.second
    } } }
    fun set(values: Map<String,Int>) { viewModelScope.launch { gate.withLock {
        writing=true
        try {
            val result=withContext(Dispatchers.IO) {
                val results=values.map { (key,value)->WlBinder.configSet(key,value) }
                results.all { it==0 } to keys.associateWith { WlBinder.configGet(it) }
            }
            config=result.second; error=!result.first
        } finally { writing=false }
    } } }
    fun set(key:String,value:Int)=set(mapOf(key to value))
    fun close(id:Long) { viewModelScope.launch { withContext(Dispatchers.IO) { Awl.closeWindow(id) }; refresh() } }
}

/** Window-list management uses libawl, the same public API as consumer apps.
 * The package-authenticated host can manage every window; consumer callers are
 * scoped by uid in the daemon. Each tap brings forward/re-attaches that window;
 * Close asks the daemon to let the client exit cleanly.
 * Subscribe while visible and pull a fresh snapshot on return: events are live-edge
 * only, so a resumed activity must not rely on events it missed in the background.
 */
open class MainActivity : AppCompatActivity() {
    private val state:WindowState by viewModels()
    protected open val initialTab=0
    private val handler=Handler(Looper.getMainLooper())
    private var visible=false
    private val refresher=Runnable { if(visible) state.refresh() }
    // A re-attach bursts detach+attach; destruction can burst destroy+detach.
    // Coalesce those into one snapshot, as in the original window-list implementation.
    private fun scheduleRefresh() { handler.post { if(visible) { handler.removeCallbacks(refresher); handler.postDelayed(refresher,60) } } }
    private val events=object:Awl.Callback {
        override fun onWindowCreated(id:Long,title:String?)=scheduleRefresh()
        override fun onWindowDestroyed(id:Long)=scheduleRefresh()
        override fun onWindowAttached(id:Long)=scheduleRefresh()
        override fun onWindowDetached(id:Long)=scheduleRefresh()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this)
        super.onCreate(savedInstanceState)
        setContent { WithAnlandTheme { appearance ->
            AnlandShell(listOf(Destination(getString(R.string.windows_title),Icons.Outlined.Window),Destination(getString(R.string.settings_title),Icons.Outlined.Tune),Destination(getString(R.string.appearance_title),Icons.Outlined.Palette)),appearance,initialTab,
                actions={IconButton(onClick=state::refresh){Icon(Icons.Outlined.Refresh,getString(R.string.refresh))}}) { tab, _ ->
                when(tab) { 0->WindowsPage(state); 1->WindowSettings(state); else->Page { AppearanceSettings(appearance) } }
            }
            if(state.error) AlertDialog(onDismissRequest={state.error=false},title={Text(stringResource(R.string.setting_write_failed))},confirmButton={TextButton(onClick={state.error=false}){Text(stringResource(R.string.dialog_ok))}})
        } }
    }
    override fun onStart() { super.onStart(); visible=true; Awl.registerCallback(events); state.refresh() }
    override fun onStop() { visible=false; handler.removeCallbacks(refresher); Awl.unregisterCallback(events); super.onStop() }
}

@Composable
internal fun AutoLaunch(state:WindowState) {
    SettingItem(stringResource(R.string.auto_attach),description=stringResource(R.string.auto_attach_tip),enabled=state.connected,
        trailingContent={Switch(state.config["auto_attach"]==1,{state.set("auto_attach",if(it)1 else 0)},enabled=state.connected && !state.writing)})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowsPage(state:WindowState) {
    val context=LocalContext.current
    var menu by remember { mutableStateOf<Awl.WlWindow?>(null) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Card(colors=CardDefaults.cardColors(containerColor=if(state.connected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(stringResource(R.string.app_name),style=MaterialTheme.typography.labelLarge)
                Text(if(state.connected)stringResource(R.string.status_window_count,state.windows.size)else stringResource(R.string.status_daemon_unreachable),style=MaterialTheme.typography.headlineSmall)
            }
        } }
        item { SettingGroup(stringResource(R.string.launch_behavior)) { AutoLaunch(state) } }
        if(state.connected && state.windows.isEmpty()) item { Text(stringResource(R.string.status_empty),Modifier.padding(20.dp),style=MaterialTheme.typography.bodyLarge) }
        items(state.windows,key={it.id}) { window ->
            Card(Modifier.combinedClickable(onClick={Awl.attachWindow(context,window.id,window.title)},onLongClick={menu=window})) {
                Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Window,null,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal=16.dp)) {
                        Text(window.title?.takeIf { it.isNotBlank() }?:stringResource(R.string.window_fallback_title,window.id),style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(if(window.attached)R.string.state_visible else R.string.state_background),style=MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick={menu=window}){Icon(Icons.Outlined.MoreVert,stringResource(R.string.menu_window_info))}
                }
            }
        }
    }
    menu?.let { w -> AlertDialog(onDismissRequest={menu=null},title={Text(w.title.orEmpty())},text={Text(stringResource(R.string.window_info_format,w.id,w.title.orEmpty(),stringResource(if(w.attached)R.string.state_visible else R.string.state_background)))},
        confirmButton={TextButton(onClick={state.close(w.id);menu=null}){Text(stringResource(R.string.menu_close))}},dismissButton={TextButton(onClick={menu=null}){Text(stringResource(R.string.dialog_ok))}}) }
}
