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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContainersPage(state:ShellState, onApps:()->Unit) {
    val context=LocalContext.current
    var stop by remember{mutableStateOf<ContainerState?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(state.containers.isEmpty())item{Text(stringResource(R.string.status_no_containers))}
        items(state.containers,key={it.name}) { container ->
            Card(onClick={state.select(container.name)},colors=CardDefaults.cardColors(containerColor=if(container.name==state.active)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(container.name,style=MaterialTheme.typography.titleLarge)
                    Text(if(container.running())stringResource(R.string.running_fmt,container.pid)else stringResource(R.string.stopped))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick={state.select(container.name);onApps()},enabled=!state.busy){Text(stringResource(R.string.apps))}
                        OutlinedButton(onClick={context.startActivity(Intent(context,ConsoleActivity::class.java).putExtra("container",container.name))},enabled=container.running()&&!state.busy){Text(stringResource(R.string.enter_console))}
                        OutlinedButton(onClick={if(container.running())stop=container else state.changeRunning(container)},enabled=!state.busy){Text(stringResource(if(container.running())R.string.stop else R.string.start))}
                    }
                }
            }
        }
    }
    stop?.let{container->AlertDialog(onDismissRequest={stop=null},text={Text(stringResource(R.string.stop_confirm_fmt,container.name))},confirmButton={TextButton(onClick={state.changeRunning(container);stop=null}){Text(stringResource(R.string.stop))}},dismissButton={TextButton(onClick={stop=null}){Text(stringResource(android.R.string.cancel))}})}
}
