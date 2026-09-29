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
internal fun ShellSettings(state:ShellState,appearance:Appearance,route:String?,navigate:(String?)->Unit) {
    val context=LocalContext.current
    if(route=="user") {
        Page {
            SettingGroup(stringResource(R.string.user_settings)) {
                Text(stringResource(if(Prefs.launchCredential(context,state.active).isEmpty())R.string.user_local_help else R.string.credential_launch_help),Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                NavigationSettingItem(stringResource(R.string.credentials_title),icon=Icons.Outlined.Key,onClick={context.startActivity(Intent(context,com.anland.shell.connections.CredentialsActivity::class.java))})
                (listOf("")+state.users).distinct().forEach { name->
                    SettingItem(name.ifBlank{stringResource(R.string.user_auto)},icon=Icons.Outlined.Person,onClick={state.user(name)},trailingContent={RadioButton(state.user==name,{state.user(name)})})
                }
            }
        }
        return
    }
    if(route=="env") {
        var env by rememberSaveable(state.active){mutableStateOf(Prefs.launchEnv(context,state.active))}
        val bad=EnvVars.invalidLine(env)
        Page {
            SettingGroup(stringResource(R.string.env_settings)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.env_defaults_fmt,EnvVars.format(DsCli.defaultEnvPairs())),style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(env,{env=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.env_btn))},minLines=6,isError=bad!=null,shape=MaterialTheme.shapes.large)
                    if(bad!=null)Text(stringResource(R.string.env_invalid_line_fmt,bad),color=MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick={env=""}){Text(stringResource(R.string.env_clear))}
                        Button(onClick={Prefs.setLaunchEnv(context,state.active,env);navigate(null)},enabled=bad==null){Text(stringResource(R.string.env_save))}
                    }
                }
            }
        }
        return
    }
    Page {
        AppearanceSettings(appearance)
        com.anland.design.maintenance.MaintenanceEntries(ShellMaintenanceActivity::class.java)
        SettingGroup(stringResource(R.string.credentials_title)) {
            NavigationSettingItem(stringResource(R.string.credentials_manage),description=stringResource(R.string.credentials_summary),icon=Icons.Outlined.Key,onClick={context.startActivity(Intent(context,com.anland.shell.connections.CredentialsActivity::class.java))})
        }
        SettingGroup(state.active.ifBlank{stringResource(R.string.no_container_selected)}) {
            NavigationSettingItem(stringResource(R.string.windows_entry),description=stringResource(R.string.windows_entry_help),icon=Icons.Outlined.Window,onClick={openWindows(context)})
            NavigationSettingItem(stringResource(R.string.user_settings),value=state.user.ifBlank{stringResource(R.string.user_auto)},icon=Icons.Outlined.Person,enabled=state.active.isNotBlank(),onClick={navigate("user")})
            NavigationSettingItem(stringResource(R.string.env_settings),icon=Icons.Outlined.Terminal,enabled=state.active.isNotBlank(),onClick={navigate("env")})
        }
        Text(stringResource(R.string.desktop_mode_help),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
