package com.anland.shell

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anland.design.*
import com.anland.shell.ds.*
import com.anland.shell.ui.IconLoader
import kotlinx.coroutines.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppsPage(state:ShellState,icons:IconLoader) {
    val context=LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember {mutableStateOf<AppEntry?>(null)}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SettingGroup(state.active.ifBlank{stringResource(R.string.no_container_selected)}) {
            NavigationSettingItem(stringResource(R.string.windows_entry),description=stringResource(R.string.windows_entry_help),icon=Icons.Outlined.OpenInNew,onClick={openWindows(context)})
        }
        OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text(stringResource(R.string.search_apps))},leadingIcon={Icon(Icons.Outlined.Search,null)})
        if(state.anlandxInstalled==false) Text(stringResource(R.string.tab_apps_no_anlandx),color=MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.app_count_fmt,state.apps.size),style=MaterialTheme.typography.labelLarge)
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val active=state.containers.firstOrNull{it.name==state.active}
        if(active!=null && !active.running()) Button(onClick={state.changeRunning(active)},enabled=!state.busy){Text(stringResource(R.string.start))}
        else if(state.apps.isEmpty()) Text(stringResource(R.string.no_apps),Modifier.padding(16.dp))
        LazyVerticalGrid(GridCells.Adaptive(132.dp),Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)) {
            items(state.apps.filter{it.name.contains(search,true)||it.id.contains(search,true)},key={it.id}) { app->
                var bitmap by remember(app.iconKey()){mutableStateOf<Bitmap?>(icons.peek(app))}
                DisposableEffect(app.iconKey()) {
                    var current=true
                    icons.load(app){_,image->if(current)bitmap=image}
                    onDispose{current=false}
                }
                Card(Modifier.combinedClickable(onClick={context.startActivity(Shortcuts.launchIntent(context,app))},onLongClick={selected=app})) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),null,Modifier.size(56.dp))else Icon(Icons.Outlined.Apps,null,Modifier.size(56.dp))
                        Text(app.name,style=MaterialTheme.typography.labelLarge,maxLines=2,minLines=2)
                    }
                }
            }
        }
    }
    selected?.let { app->AlertDialog(onDismissRequest={selected=null},title={Text(app.name)},text={Text("container: ${app.container}\nid: ${app.id}\nexec: ${app.exec}\nicon: ${app.icon}\ndesktop: ${app.desktopPath}")},confirmButton={TextButton(onClick={
        // Like the original launcher, wait for the icon before requesting the pinned
        // shortcut; a cache miss must not silently replace it with a letter tile.
        Toast.makeText(context,R.string.pinned_request,Toast.LENGTH_SHORT).show()
        icons.load(app){_,bitmap->if(!Shortcuts.pin(context,app,bitmap))Toast.makeText(context,R.string.pinned_fallback,Toast.LENGTH_LONG).show()}
        selected=null
    }){Text(stringResource(R.string.pin_shortcut))}},dismissButton={TextButton(onClick={selected=null}){Text(stringResource(R.string.dialog_ok))}}) }
}
