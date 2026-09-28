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

/** Business operations remain in DsCli/Prefs/Shortcuts; Compose owns presentation only. */
class ShellState(app:Application):AndroidViewModel(app) {
    var containers by mutableStateOf<List<ContainerState>>(emptyList()); private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var users by mutableStateOf<List<String>>(emptyList()); private set
    var active by mutableStateOf(Prefs.activeContainer(app)); private set
    var user by mutableStateOf(Prefs.launchUser(app,active)); private set
    var anlandxInstalled by mutableStateOf<Boolean?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    private val context get()=getApplication<Application>()
    private var refreshJob:Job?=null
    // Original launcher semantics: once per instance, start the selected container
    // if needed. AppLaunchActivity independently ensures it is running for shortcuts.
    private var autoStarted=false
    fun refresh() {
        if(busy)return
        refreshJob?.cancel()
        refreshJob=viewModelScope.launch {
            val list=withContext(Dispatchers.IO){DsCli.listContainers()}
            containers=list
            if(list.none {it.name==active}) {
                active=(list.firstOrNull{it.running()}?:list.firstOrNull())?.name.orEmpty()
                Prefs.setActiveContainer(context,active)
            }
            user=Prefs.launchUser(context,active)
            apps=emptyList(); users=emptyList(); anlandxInstalled=null
            val selected=list.firstOrNull{it.name==active}
            val needAutoStart=!autoStarted && selected!=null && !selected.running()
            if(selected!=null)autoStarted=true
            if(needAutoStart) { changeRunning(selected!!); return@launch }
            if(list.any {it.name==active && it.running()}) {
                val name=active
                val launchUser=user
                val data=withContext(Dispatchers.IO){Triple(DsCli.listDesktopDump(name),DsCli.listUsers(name),DsCli.anlandxInstalled(name,launchUser))}
                if(name==active){
                    if(data.first.ok) apps=DesktopEntry.parse(name,data.first.stdout)
                    else error=data.first.error?:data.first.stderr
                    users=data.second
                    anlandxInstalled=data.third
                }
            }
            if(withContext(Dispatchers.IO){DsCli.ds()==null})error=DsCli.unavailableReason()
        }
    }
    fun select(name:String) { if(busy)return; active=name; Prefs.setActiveContainer(context,name); refresh() }
    // anlandx and launch environment are per user; re-probe after changing the user.
    fun user(name:String) { user=name; Prefs.setLaunchUser(context,active,name); refresh() }
    fun changeRunning(container:ContainerState) {
        if(busy)return
        refreshJob?.cancel(); busy=true
        viewModelScope.launch {
            try {
                val ok=withContext(Dispatchers.IO){
                    val result=if(container.running())DsCli.stop(container.name)else DsCli.start(container.name)
                    if(!result.ok) throw IllegalStateException(result.error?:result.stderr.ifBlank{"exit ${result.exit}"})
                    container.running() || DsCli.awaitRunning(container.name,90000)
                }
                if(!ok)error=context.getString(R.string.start_timeout_fmt,container.name)
            } catch(e:CancellationException){throw e} catch(e:Exception){error=e.message}
            finally {busy=false;refresh()}
        }
    }
}
