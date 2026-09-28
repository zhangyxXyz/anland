package com.anland.design

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

/** Both APKs render the same card and read the host's committed state. The
 * display server is shared by containers; do not pretend this is per-container. */
@Composable fun WorkspaceModeCard() {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var status by remember { mutableStateOf<android.os.Bundle?>(null) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                status=withContext(Dispatchers.IO){try{context.contentResolver.call(Uri.parse("content://com.anlandnext.workspace"),"status",null,null)}catch(_:Exception){null}}
                delay(1500)
            }
        }
    }
    val desktop=status?.getBoolean("desktop")==true
    val busy=status?.getBoolean("busy")==true
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.workspace_scope),style=MaterialTheme.typography.labelMedium)
            Text(stringResource(if(desktop)R.string.workspace_desktop_mode else R.string.workspace_independent_mode),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(if(status==null || status?.getBoolean("available")!=true)R.string.workspace_unavailable else R.string.workspace_window_count,status?.getInt("count")?:0),style=MaterialTheme.typography.bodyMedium)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick={openWorkspace(context)},enabled=!busy){Icon(Icons.Outlined.DesktopWindows,null);Spacer(Modifier.width(8.dp));Text(stringResource(if(desktop)R.string.workspace_open else R.string.workspace_to_desktop))}
                if(desktop)TextButton(onClick={openWorkspace(context,"independent")},enabled=!busy){Icon(Icons.Outlined.OpenInNew,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.workspace_to_independent))}
            }
        }
    }
}

fun openWorkspace(context:Context,action:String="open") {
    try {context.startActivity(Intent().setClassName("com.anlandnext","com.anlandnext.WorkspaceActivity").putExtra("action",action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    catch(_:android.content.ActivityNotFoundException){Toast.makeText(context,R.string.workspace_host_update,Toast.LENGTH_LONG).show()}
}
