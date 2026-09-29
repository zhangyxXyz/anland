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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anland.design.*
import com.anland.shell.ds.*
import com.anland.shell.ui.IconLoader
import kotlinx.coroutines.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppsPage(state:ShellState,icons:IconLoader) {
    val context=LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var listView by rememberSaveable { mutableStateOf(false) }
    var selected by remember {mutableStateOf<AppEntry?>(null)}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        DesktopBanner(state.apps.filter{it.desktopSession})
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WorkspaceSearch(search,{search=it},stringResource(R.string.search_apps),Modifier.weight(1f))
            IconToggleButton(listView,{listView=it}){Icon(if(listView)Icons.Outlined.GridView else Icons.Outlined.ViewList,stringResource(if(listView)R.string.design_grid_view else R.string.design_list_view))}
        }
        if(state.anlandxInstalled==false) Text(stringResource(R.string.tab_apps_no_anlandx),color=MaterialTheme.colorScheme.error)
        val applications=state.apps.filter{!it.desktopSession}
        val filtered=applications.filter{it.name.contains(search,true)||it.id.contains(search,true)}
        Text(stringResource(R.string.app_count_fmt,filtered.size),Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val active=state.containers.firstOrNull{it.name==state.active}
        if(active!=null && !active.running()) Button(onClick={state.changeRunning(active)},enabled=!state.busy){Text(stringResource(R.string.start))}
        else if(filtered.isEmpty()&&!state.busy) EmptyWorkspace(stringResource(if(search.isEmpty())R.string.no_apps else R.string.design_no_results),Icons.Outlined.Apps)
        LazyVerticalGrid(if(listView)GridCells.Adaptive(300.dp) else GridCells.Adaptive(140.dp),Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)) {
            items(filtered,key={it.id}) { app->
                var bitmap by remember(app.iconKey()){mutableStateOf<Bitmap?>(icons.peek(app))}
                DisposableEffect(app.iconKey()) {
                    var current=true
                    icons.load(app){_,image->if(current)bitmap=image}
                    onDispose{current=false}
                }
                Card(Modifier.combinedClickable(onClick={context.startActivity(Shortcuts.launchIntent(context,app))},onLongClick={selected=app}),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                    if(listView) Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        AppIcon(bitmap,Modifier.size(48.dp))
                        Text(app.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    } else Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        AppIcon(bitmap,Modifier.size(68.dp))
                        Text(app.name,Modifier.fillMaxWidth(),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelLarge,maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis)
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

@Composable
private fun DesktopBanner(desktops:List<AppEntry>) {
    val context=LocalContext.current
    Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
        // Keep the original vertical order at every window width. The heading
        // and both actions share a 38 dp icon column and a 14 dp text gap.
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            desktops.forEach { desktop ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    SettingLeadingIcon(Icons.Outlined.DesktopWindows)
                    Column(Modifier.weight(1f)) {
                        Text(if(desktops.size==1)stringResource(R.string.desktop_entry)else desktop.name,style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.desktop_entry_help),style=MaterialTheme.typography.bodySmall)
                    }
                }
                DesktopBannerAction(Icons.Outlined.DesktopWindows,stringResource(R.string.desktop_enter),tonal=true) {
                    context.startActivity(Shortcuts.launchIntent(context,desktop))
                }
            }
            DesktopBannerAction(Icons.Outlined.OpenInNew,stringResource(R.string.windows_entry)) {
                openWindows(context)
            }
        }
    }
}

@Composable
private fun DesktopBannerAction(icon:ImageVector,label:String,tonal:Boolean=false,onClick:()->Unit) {
    val content: @Composable RowScope.()->Unit = {
        Box(Modifier.width(38.dp),contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label)
    }
    val padding=PaddingValues(start=0.dp,end=16.dp,top=10.dp,bottom=10.dp)
    if(tonal) FilledTonalButton(onClick=onClick,modifier=Modifier.heightIn(min=48.dp),contentPadding=padding,content=content)
    else TextButton(onClick=onClick,modifier=Modifier.heightIn(min=48.dp),contentPadding=padding,content=content)
}

@Composable
private fun AppIcon(bitmap:Bitmap?,modifier:Modifier) {
    val monochrome=remember(bitmap){bitmap?.let{IconLoader.isMonochrome(it)}==true}
    Box(modifier,contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap.asImageBitmap(),null,Modifier.fillMaxSize(),colorFilter=if(monochrome)ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)else null)
        else SettingLeadingIcon(Icons.Outlined.Apps)
    }
}
