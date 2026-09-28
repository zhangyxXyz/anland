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

class ShellActivity:AppCompatActivity() {
    private val state:ShellState by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this)
        super.onCreate(savedInstanceState)
        setContent { WithAnlandTheme { appearance ->
            val icons=state.icons
            var settingsRoute by rememberSaveable{mutableStateOf<String?>(null)}
            AnlandShell(listOf(Destination(getString(R.string.tab_apps),Icons.Outlined.Apps),Destination(getString(R.string.tab_containers),Icons.Outlined.Storage),Destination(getString(R.string.shell_settings),Icons.Outlined.Settings)),appearance,
                brand=getString(R.string.app_name),contextLabel=state.active,
                secondaryTitle=settingsRoute?.let{getString(if(it=="user")R.string.user_settings else R.string.env_settings)},onSecondaryBack={settingsRoute=null},
                actions={
                    var choose by remember{mutableStateOf(false)}
                    Box {
                        TextButton(onClick={choose=true},enabled=state.containers.isNotEmpty()&&!state.busy){Text(state.active);Icon(Icons.Outlined.ExpandMore,null)}
                        DropdownMenu(choose,{choose=false}){state.containers.forEach{container->DropdownMenuItem(text={Text(container.name)},onClick={state.select(container.name);choose=false})}}
                    }
                    IconButton(onClick=state::refresh,enabled=!state.busy){Icon(Icons.Outlined.Refresh,getString(R.string.refresh))}
                }) { tab, navigate ->
                when(tab){0->AppsPage(state,icons);1->ContainersPage(state,onSettings={navigate(2)},onApps={navigate(0)});else->ShellSettings(state,appearance,settingsRoute){settingsRoute=it}}
            }
            state.error?.let { message->AlertDialog(onDismissRequest={state.error=null},title={Text(stringResource(R.string.error_title))},text={Text(message)},confirmButton={TextButton(onClick={state.error=null}){Text(stringResource(R.string.dialog_ok))}}) }
        } }
    }
    override fun onResume(){super.onResume();state.refresh()}
}

internal fun openWindows(context:android.content.Context) {
    val intent=context.packageManager.getLaunchIntentForPackage("com.anlandnext")
    if(intent!=null)context.startActivity(intent) else Toast.makeText(context,R.string.host_missing,Toast.LENGTH_LONG).show()
}
