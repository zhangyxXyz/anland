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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.ViewList
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
import androidx.compose.ui.platform.LocalDensity
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
    var listView by rememberSaveable { mutableStateOf(true) }
    var selected by remember {mutableStateOf<AppEntry?>(null)}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        DesktopBanner(state.apps.filter{it.desktopSession})
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WorkspaceSearch(search,{search=it},stringResource(R.string.search_apps),Modifier.weight(1f))
            IconToggleButton(listView,{listView=it}){Icon(if(listView)Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,stringResource(if(listView)R.string.design_grid_view else R.string.design_list_view))}
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopBanner(desktops:List<AppEntry>) {
    val fontScale=LocalDensity.current.fontScale.coerceAtLeast(1f)
    // Use the available page width, including split screen and enlarged text,
    // rather than the device orientation. Keep every discovered desktop launchable.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if(desktops.isEmpty()) {
            WindowDisplayCard(Modifier.fillMaxWidth())
        } else if(maxWidth >= 840.dp * fontScale) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                DesktopEntryCard(desktops,Modifier.weight(0.62f).fillMaxHeight())
                WindowDisplayCard(Modifier.weight(0.38f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                DesktopEntryCard(desktops,Modifier.fillMaxWidth())
                WindowDisplayCard(Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopEntryCard(desktops:List<AppEntry>,modifier:Modifier) {
    val context=LocalContext.current
    val colors=MaterialTheme.colorScheme
    val fontScale=LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(modifier=modifier,shape=RoundedCornerShape(24.dp),color=colors.primaryContainer.copy(alpha=0.55f),
        contentColor=colors.onSurface,border=BorderStroke(1.dp,colors.primary.copy(alpha=0.22f))) {
        // FlowRow keeps the action beside the heading when space permits, then
        // wraps it naturally without clipping translated labels or larger text.
        FlowRow(Modifier.heightIn(min=128.dp).padding(20.dp),
            horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically),
            itemVerticalAlignment=Alignment.CenterVertically) {
            BannerHeading(Icons.Outlined.DesktopWindows,stringResource(R.string.desktop_entry),
                stringResource(R.string.desktop_entry_help),Modifier.widthIn(min=240.dp * fontScale).weight(1f))
            desktops.forEach { desktop ->
                Button(onClick={context.startActivity(Shortcuts.launchIntent(context,desktop))},
                    modifier=Modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp)) {
                    Text(if(desktops.size==1)stringResource(R.string.desktop_enter)else desktop.name,
                        textAlign=TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun WindowDisplayCard(modifier:Modifier) {
    val context=LocalContext.current
    val colors=MaterialTheme.colorScheme
    Surface(onClick={openWindows(context)},modifier=modifier,shape=RoundedCornerShape(24.dp),
        color=colors.surfaceContainerLow,contentColor=colors.onSurface,border=BorderStroke(1.dp,colors.outlineVariant)) {
        Row(Modifier.heightIn(min=128.dp).padding(20.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            BannerHeading(Icons.Outlined.Tune,stringResource(R.string.windows_entry),
                stringResource(R.string.windows_entry_summary),Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight,null,Modifier.size(24.dp),tint=colors.primary)
        }
    }
}

@Composable
private fun BannerHeading(icon:ImageVector,title:String,detail:String,modifier:Modifier) {
    Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(60.dp),contentAlignment=Alignment.Center) {
                Icon(icon,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppIcon(bitmap:Bitmap?,modifier:Modifier) {
    val monochrome=remember(bitmap){bitmap?.let{IconLoader.isMonochrome(it)}==true}
    Box(modifier,contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap.asImageBitmap(),null,Modifier.fillMaxSize(),colorFilter=if(monochrome)ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)else null)
        else SettingLeadingIcon(Icons.Outlined.Apps)
    }
}
