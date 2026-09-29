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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WindowState : ViewModel() {
    var windows by mutableStateOf<List<Awl.WlWindow>>(emptyList()); private set
    var config by mutableStateOf<Map<String,Int>>(emptyMap()); private set
    var connected by mutableStateOf(false); private set
    var writing by mutableStateOf(false); private set
    var error by mutableStateOf(false)
    var closeFailed by mutableStateOf(false)
    var closeWaiting by mutableStateOf(false)
    var closing by mutableStateOf<Set<Long>>(emptySet()); private set
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
    fun close(id:Long) {
        if(id in closing)return
        closing=closing+id
        viewModelScope.launch {
            try {
                val rc=withContext(Dispatchers.IO){Awl.closeWindow(id)}
                if(rc!=0){closeFailed=true;return@launch}
                // A successful request is not a closed window. The client may
                // need to show an unsaved-document dialog. Never kill it or retry.
                delay(1500)
                val remaining=withContext(Dispatchers.IO){Awl.getWindows()}
                closeWaiting=remaining?.any{it.id==id}==true
                refresh()
            } finally {closing=closing-id}
        }
    }
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
    protected open val openWindowSettings=false
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
            var windowSettings by rememberSaveable { mutableStateOf(openWindowSettings) }
            AnlandShell(listOf(Destination(getString(R.string.windows_title),Icons.Outlined.Window),Destination(getString(R.string.app_settings),Icons.Outlined.Settings)),appearance,initialTab,brand=getString(R.string.app_name),contextLabel=if(state.connected)getString(R.string.design_connected)else getString(R.string.status_daemon_unreachable),
                secondaryTitle=if(windowSettings)getString(R.string.settings_title)else null,onSecondaryBack={windowSettings=false},
                actions={IconButton(onClick=state::refresh){Icon(Icons.Outlined.Refresh,getString(R.string.refresh))}}) { tab, _ ->
                when {
                    windowSettings -> WindowSettings(state)
                    tab==0 -> WindowsPage(state)
                    else -> Page {
                        AppearanceSettings(appearance)
                        SettingGroup(stringResource(R.string.windows_title)) {
                            NavigationSettingItem(stringResource(R.string.settings_title),description=stringResource(R.string.window_settings_summary),icon=Icons.Outlined.Tune,onClick={windowSettings=true})
                        }
                    }
                }
            }
            if(state.error) AlertDialog(onDismissRequest={state.error=false},title={Text(stringResource(R.string.setting_write_failed))},confirmButton={TextButton(onClick={state.error=false}){Text(stringResource(R.string.dialog_ok))}})
            if(state.closeFailed||state.closeWaiting) AlertDialog(onDismissRequest={state.closeFailed=false;state.closeWaiting=false},
                text={Text(stringResource(if(state.closeFailed)R.string.window_close_failed else R.string.window_close_waiting))},
                confirmButton={TextButton(onClick={state.closeFailed=false;state.closeWaiting=false}){Text(stringResource(R.string.dialog_ok))}})
        } }
    }
    override fun onStart() { super.onStart(); visible=true; Awl.registerCallback(events); state.refresh() }
    override fun onStop() { visible=false; handler.removeCallbacks(refresher); Awl.unregisterCallback(events); super.onStop() }
}

@Composable
internal fun AutoLaunch(state:WindowState) {
    SettingItem(stringResource(R.string.auto_attach),description=stringResource(R.string.auto_attach_tip),icon=Icons.Outlined.OpenInNew,descriptionMaxLines=5,enabled=state.connected,
        trailingContent={Switch(state.config["auto_attach"]==1,{state.set("auto_attach",if(it)1 else 0)},enabled=state.connected && !state.writing)})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowsPage(state:WindowState) {
    val context=LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf<Awl.WlWindow?>(null) }
    var info by remember { mutableStateOf<Awl.WlWindow?>(null) }
    val filtered=state.windows.filter{search.isBlank() || it.title.orEmpty().contains(search,true)}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=if(state.connected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.fillMaxWidth().padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                SettingLeadingIcon(Icons.Outlined.Window)
                Text(if(state.connected)stringResource(R.string.status_window_count,state.windows.size)else stringResource(R.string.status_daemon_unreachable),style=MaterialTheme.typography.titleLarge)
            }
        }
        SettingGroup("") { AutoLaunch(state) }
        WorkspaceSearch(search,{search=it},stringResource(R.string.design_search_windows),Modifier.fillMaxWidth())
        if(state.connected && filtered.isEmpty())EmptyWorkspace(stringResource(if(search.isEmpty())R.string.status_empty else R.string.design_no_results),Icons.Outlined.Window)
        LazyVerticalGrid(GridCells.Adaptive(340.dp),Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            items(filtered,key={it.id}) { window ->
                var menu by remember{mutableStateOf(false)}
                Card(Modifier.combinedClickable(onClick={Awl.attachWindow(context,window.id,window.title)},onLongClick={menu=true}),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            SettingLeadingIcon(Icons.Outlined.Window)
                            Spacer(Modifier.weight(1f))
                            Box {
                                IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,stringResource(R.string.menu_window_info))}
                                DropdownMenu(menu,{menu=false}) {
                                    DropdownMenuItem(text={Text(stringResource(R.string.window_open))},leadingIcon={Icon(Icons.Outlined.OpenInNew,null)},onClick={menu=false;Awl.attachWindow(context,window.id,window.title)})
                                    DropdownMenuItem(text={Text(stringResource(R.string.window_close))},leadingIcon={Icon(Icons.Outlined.Close,null)},enabled=window.id !in state.closing,onClick={menu=false;confirmClose=window})
                                    DropdownMenuItem(text={Text(stringResource(R.string.menu_window_info))},leadingIcon={Icon(Icons.Outlined.Info,null)},onClick={menu=false;info=window})
                                }
                            }
                        }
                        Text(window.title?.takeIf{it.isNotBlank()}?:stringResource(R.string.window_fallback_title,window.id),style=MaterialTheme.typography.titleMedium,maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            StatusPill(stringResource(if(window.attached)R.string.state_visible else R.string.state_background),Modifier.weight(1f,false),window.attached)
                            FilledTonalIconButton(onClick={Awl.attachWindow(context,window.id,window.title)}){Icon(Icons.Outlined.OpenInNew,stringResource(R.string.window_open))}
                        }
                    }
                }
            }
        }
    }
    info?.let{w->AlertDialog(onDismissRequest={info=null},title={Text(stringResource(R.string.menu_window_info))},text={Text(stringResource(R.string.window_info_format,w.id,w.title.orEmpty(),stringResource(if(w.attached)R.string.state_visible else R.string.state_background)))},confirmButton={TextButton(onClick={info=null}){Text(stringResource(R.string.dialog_ok))}})}
    confirmClose?.let { w -> AlertDialog(onDismissRequest={confirmClose=null},title={Text(stringResource(R.string.window_close))},
        text={Text(stringResource(R.string.window_close_confirm,w.title.orEmpty()))},
        confirmButton={TextButton(onClick={state.close(w.id);confirmClose=null}){Text(stringResource(R.string.window_close))}},
        dismissButton={TextButton(onClick={confirmClose=null}){Text(stringResource(android.R.string.cancel))}}) }
}
