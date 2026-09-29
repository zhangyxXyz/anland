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
import com.anland.shell.connections.CredentialsPage
import com.anland.shell.connections.CredentialsState
import kotlinx.coroutines.*

class ShellActivity:AppCompatActivity() {
    private val state:ShellState by viewModels()
    private val credentials:CredentialsState by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this)
        super.onCreate(savedInstanceState)
        setContent { WithAnlandTheme { appearance ->
            val icons=state.icons
            var route by rememberSaveable{mutableStateOf<String?>(null)}
            var environmentContainer by rememberSaveable{mutableStateOf("")}
            val secondaryTitle=when {
                credentials.editor!=null -> getString(R.string.credential_edit)
                route=="user" -> getString(R.string.launch_user_title_fmt,state.active)
                route=="env" -> getString(R.string.env_editor_title_fmt,environmentContainer)
                else -> null
            }
            AnlandShell(listOf(Destination(getString(R.string.tab_apps),Icons.Outlined.Apps),Destination(getString(R.string.tab_containers),Icons.Outlined.Storage),Destination(getString(R.string.tab_credentials),Icons.Outlined.Key),Destination(getString(R.string.shell_settings),Icons.Outlined.Settings)),appearance,
                brand=getString(R.string.app_name),contextLabel=state.active,
                secondaryTitle=secondaryTitle,onSecondaryBack={
                    if(!credentials.busy) {
                        if(credentials.editor!=null)credentials.discardEditor=true else route=null
                    }
                },
                actions={
                    var choose by remember{mutableStateOf(false)}
                    Box {
                        TextButton(onClick={choose=true},enabled=state.containers.isNotEmpty()&&!state.busy){Text(state.active);Icon(Icons.Outlined.ExpandMore,null)}
                        DropdownMenu(choose,{choose=false}){state.containers.forEach{container->DropdownMenuItem(text={Text(container.name)},onClick={state.select(container.name);choose=false})}}
                    }
                    IconButton(onClick={state.refresh();credentials.reload()},enabled=!state.busy&&!credentials.busy){Icon(Icons.Outlined.Refresh,getString(R.string.refresh))}
                }) { tab, navigate ->
                when {
                    route=="env" -> LaunchEnvironmentPage(environmentContainer){route=null}
                    route=="user" -> LaunchUserPage(state,credentials)
                    tab==0 -> AppsPage(state,icons)
                    tab==1 -> ContainersPage(state,
                        onEnvironment={container->environmentContainer=container;route="env"},
                        onCredentials={navigate(2)},onApps={navigate(0)})
                    tab==2 -> CredentialsPage(credentials,window,onLaunchChanged=state::refresh) {
                        LaunchAccountSummary(state,credentials){route="user"}
                    }
                    else -> ShellSettings(appearance)
                }
            }
            state.error?.let { message->AlertDialog(onDismissRequest={state.error=null},title={Text(stringResource(R.string.error_title))},text={Text(message)},confirmButton={TextButton(onClick={state.error=null}){Text(stringResource(R.string.dialog_ok))}}) }
        } }
    }
    override fun onResume(){super.onResume();state.refresh();credentials.reload()}
}

internal fun openWindows(context:android.content.Context) {
    val intent=context.packageManager.getLaunchIntentForPackage("com.anlandnext")
    if(intent!=null)context.startActivity(intent) else Toast.makeText(context,R.string.host_missing,Toast.LENGTH_LONG).show()
}
