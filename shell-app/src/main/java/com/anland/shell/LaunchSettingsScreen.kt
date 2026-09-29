package com.anland.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anland.design.*
import com.anland.shell.connections.CredentialsState
import com.anland.shell.connections.CredentialsError
import com.anland.shell.ds.DsCli
import com.anland.shell.ds.EnvVars

@Composable
internal fun LaunchAccountSummary(state:ShellState,credentials:CredentialsState,onChoose:()->Unit) {
    val context=LocalContext.current
    val profile=credentials.profiles.firstOrNull { it.id==Prefs.launchCredential(context,state.active) }
    SettingGroup(state.active.ifBlank { stringResource(R.string.no_container_selected) }) {
        NavigationSettingItem(stringResource(R.string.user_settings),
            value=state.user.ifBlank { stringResource(R.string.user_auto) },
            description=profile?.let { stringResource(R.string.launch_credential_fmt,it.name) }
                ?:stringResource(R.string.launch_user_summary),
            icon=Icons.Outlined.Person,enabled=state.active.isNotBlank()&&!credentials.busy&&!state.busy,onClick=onChoose)
    }
}

@Composable
internal fun LaunchUserPage(state:ShellState,credentials:CredentialsState) {
    val context=LocalContext.current
    val bound=Prefs.launchCredential(context,state.active)
    Page {
        val profiles=credentials.profiles.filter { it.container==state.active }
        if(profiles.isNotEmpty())SettingGroup(stringResource(R.string.launch_saved_credentials)) {
            profiles.forEach { profile ->
                SettingItem(profile.name,description=profile.username,icon=Icons.Outlined.Key,
                    enabled=!credentials.busy&&!state.busy,
                    onClick={credentials.useForLaunch(profile,state::refresh)},
                    trailingContent={RadioButton(bound==profile.id,
                        onClick={credentials.useForLaunch(profile,state::refresh)},enabled=!credentials.busy&&!state.busy)})
            }
        }
        SettingGroup(stringResource(R.string.launch_direct_user)) {
            Text(stringResource(R.string.user_local_help),Modifier.padding(horizontal=20.dp,vertical=12.dp),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            (listOf("")+state.users+state.user).distinct().forEach { name ->
                val choose={state.user(name);credentials.reload()}
                SettingItem(name.ifBlank { stringResource(R.string.user_auto) },icon=Icons.Outlined.Person,
                    enabled=!credentials.busy&&!state.busy,onClick=choose,
                    trailingContent={RadioButton(bound.isEmpty()&&state.user==name,onClick=choose,
                        enabled=!credentials.busy&&!state.busy)})
            }
        }
    }
    CredentialsError(credentials)
}

@Composable
internal fun LaunchEnvironmentPage(container:String,onSaved:()->Unit) {
    val context=LocalContext.current
    var env by rememberSaveable(container) { mutableStateOf(Prefs.launchEnv(context,container)) }
    val bad=EnvVars.invalidLine(env)
    Page {
        SettingGroup(container) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.env_defaults_fmt,EnvVars.format(DsCli.defaultEnvPairs())),
                    style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(env,{env=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.env_btn))},
                    supportingText={Text(stringResource(R.string.env_editor_hint))},
                    minLines=6,isError=bad!=null,shape=MaterialTheme.shapes.large)
                if(bad!=null)Text(stringResource(R.string.env_invalid_line_fmt,bad),color=MaterialTheme.colorScheme.error)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick={env=""}){Text(stringResource(R.string.env_clear))}
                    Button(onClick={Prefs.setLaunchEnv(context,container,env);onSaved()},enabled=bad==null) {
                        Text(stringResource(R.string.env_save))
                    }
                }
            }
        }
    }
}
