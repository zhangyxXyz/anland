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
            val icons=remember{IconLoader(this)}
            AnlandShell(listOf(Destination(getString(R.string.tab_apps),Icons.Outlined.Apps),Destination(getString(R.string.tab_containers),Icons.Outlined.Storage),Destination(getString(R.string.shell_settings),Icons.Outlined.Settings)),appearance,
                actions={IconButton(onClick=state::refresh,enabled=!state.busy){Icon(Icons.Outlined.Refresh,getString(R.string.refresh))}}) { tab, navigate ->
                when(tab){0->AppsPage(state,icons);1->ContainersPage(state) { navigate(0) };else->ShellSettings(state,appearance)}
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
