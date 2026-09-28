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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anland.design.*
import com.anland.shell.ds.*
import com.anland.shell.ui.IconLoader
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContainersPage(state:ShellState, onSettings:()->Unit, onApps:()->Unit) {
    val context=LocalContext.current
    var stop by remember{mutableStateOf<ContainerState?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(state.containers.isEmpty())item{EmptyWorkspace(stringResource(R.string.status_no_containers),Icons.Outlined.Storage)}
        items(state.containers,key={it.name}) { container ->
            val selected=container.name==state.active
            Card(onClick={state.select(container.name)},shape=RoundedCornerShape(28.dp),border=BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        SettingLeadingIcon(Icons.Outlined.Storage)
                        Text(container.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                        StatusPill(stringResource(if(container.running())R.string.design_running else R.string.stopped),active=container.running())
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    val desktops=if(container.name==state.active)state.apps.filter{it.desktopSession}else emptyList()
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        WorkspaceAction(stringResource(R.string.apps),stringResource(R.string.search_apps),Icons.Outlined.Apps,Modifier.weight(1f),!state.busy){state.select(container.name);onApps()}
                        WorkspaceAction(stringResource(R.string.enter_console),stringResource(R.string.design_console_detail),Icons.Outlined.Terminal,Modifier.weight(1f),container.running()&&!state.busy){context.startActivity(Intent(context,ConsoleActivity::class.java).putExtra("container",container.name))}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        desktops.firstOrNull()?.let{desktop->WorkspaceAction(stringResource(R.string.desktop_entry),stringResource(R.string.desktop_entry_help),Icons.Outlined.DesktopWindows,Modifier.weight(1f),!state.busy){context.startActivity(Shortcuts.launchIntent(context,desktop))}}
                        WorkspaceAction(stringResource(R.string.design_startup_settings),stringResource(R.string.env_settings),Icons.Outlined.Tune,Modifier.weight(1f),!state.busy){state.select(container.name);onSettings()}
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        if(selected)Text(stringResource(R.string.design_selected_container),Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary) else Spacer(Modifier.weight(1f))
                        TextButton(onClick={if(container.running())stop=container else state.changeRunning(container)},enabled=!state.busy,colors=ButtonDefaults.textButtonColors(contentColor=if(container.running())MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                            Icon(if(container.running())Icons.Outlined.PowerSettingsNew else Icons.Outlined.PlayArrow,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(stringResource(if(container.running())R.string.stop else R.string.start))
                        }
                    }
                }
            }
        }
    }
    stop?.let{container->AlertDialog(onDismissRequest={stop=null},text={Text(stringResource(R.string.stop_confirm_fmt,container.name))},confirmButton={TextButton(onClick={state.changeRunning(container);stop=null}){Text(stringResource(R.string.stop))}},dismissButton={TextButton(onClick={stop=null}){Text(stringResource(android.R.string.cancel))}})}
}
