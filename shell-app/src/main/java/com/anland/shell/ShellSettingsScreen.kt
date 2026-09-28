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

@Composable
internal fun ShellSettings(state:ShellState,appearance:Appearance) {
    val context=LocalContext.current
    var userDialog by remember{mutableStateOf(false)}
    var envDialog by remember{mutableStateOf(false)}
    Page {
        AppearanceSettings(appearance)
        SettingGroup(state.active.ifBlank{stringResource(R.string.no_container_selected)}) {
            NavigationSettingItem(stringResource(R.string.windows_entry),description=stringResource(R.string.windows_entry_help),onClick={openWindows(context)})
            NavigationSettingItem(stringResource(R.string.user_settings),value=state.user.ifBlank{stringResource(R.string.user_auto)},enabled=state.active.isNotBlank(),onClick={userDialog=true})
            NavigationSettingItem(stringResource(R.string.env_settings),enabled=state.active.isNotBlank(),onClick={envDialog=true})
        }
        Text(stringResource(R.string.desktop_mode_help),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(userDialog)AlertDialog(onDismissRequest={userDialog=false},title={Text(stringResource(R.string.user_settings))},text={Column{(listOf("")+state.users).distinct().forEach { name -> TextButton(onClick={state.user(name);userDialog=false}){Text(name.ifBlank{stringResource(R.string.user_auto)})} }}},confirmButton={TextButton(onClick={userDialog=false}){Text(stringResource(android.R.string.cancel))}})
    if(envDialog) {
        var env by remember{mutableStateOf(Prefs.launchEnv(context,state.active))}
        val bad=EnvVars.invalidLine(env)
        AlertDialog(onDismissRequest={envDialog=false},title={Text(stringResource(R.string.env_settings))},text={Column {
            Text(stringResource(R.string.env_defaults_fmt,EnvVars.format(DsCli.defaultEnvPairs())),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(env,{env=it},label={Text(stringResource(R.string.env_btn))},minLines=5,isError=bad!=null)
            TextButton(onClick={env=""}){Text(stringResource(R.string.env_clear))}
            if(bad!=null)Text(stringResource(R.string.env_invalid_line_fmt,bad),color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(onClick={Prefs.setLaunchEnv(context,state.active,env);envDialog=false},enabled=bad==null){Text(stringResource(R.string.env_save))}},dismissButton={TextButton(onClick={envDialog=false}){Text(stringResource(android.R.string.cancel))}})
    }
}
