package com.anlandnext

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anland.design.*
import com.anlandnext.awl.Awl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Generic explicit activation by a desktop entry's declared window app ID.
 * Wait for creation events, never restart clients or change global auto_attach.
 * Ambiguous matches stay selectable rather than guessing across containers.
 */
class OpenWindowActivity:AppCompatActivity() {
    private val changes=Channel<Unit>(Channel.CONFLATED)
    private var matches by mutableStateOf<List<Awl.WlWindow>>(emptyList())
    private var failed by mutableStateOf(false)
    private val callback=object:Awl.Callback {
        override fun onWindowCreated(id:Long,title:String?){changes.trySend(Unit)}
        override fun onWindowDestroyed(id:Long){changes.trySend(Unit)}
        override fun onWindowAttached(id:Long){changes.trySend(Unit)}
        override fun onWindowDetached(id:Long){changes.trySend(Unit)}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this);super.onCreate(savedInstanceState)
        val targets=listOfNotNull(intent.getStringExtra("window_app_id"),intent.getStringExtra("desktop_id"))
            .filter{it.isNotBlank()}.map{it.removeSuffix(".desktop")}
        if(targets.isEmpty()){finish();return}
        setContent{WithAnlandTheme{Surface{Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text(stringResource(R.string.window_open),style=MaterialTheme.typography.headlineSmall)
            if(failed)Text(stringResource(R.string.window_open_timeout))else if(matches.isEmpty())CircularProgressIndicator()
            if(failed)TextButton(onClick={startActivity(android.content.Intent(this@OpenWindowActivity,MainActivity::class.java));finish()}){Text(stringResource(R.string.windows_title))}
            matches.forEach{w->NavigationSettingItem(w.title.orEmpty(),onClick={open(w)})}
            TextButton(onClick={finish()}){Text(stringResource(android.R.string.cancel))}
        }}}}
        lifecycleScope.launch {
            var selectedFromMultiple=false
            while(isActive){
                val changed=withTimeoutOrNull(20000){changes.receive();true}==true
                if(!changed){if(matches.isEmpty())failed=true;continue}
                delay(150) // coalesce creation bursts before deciding single vs chooser
                matches=withContext(Dispatchers.IO){Awl.getWindows().orEmpty().filter{w->
                    val id=Awl.applicationId(w.id)?.removeSuffix(".desktop")
                    id!=null && targets.any{it.equals(id,ignoreCase=true)}
                }}
                if(matches.size>1)selectedFromMultiple=true
                if(matches.size==1 && !selectedFromMultiple){open(matches.single());break}
                if(matches.isNotEmpty())failed=false
            }
        }
    }
    private fun open(window:Awl.WlWindow){Awl.attachWindow(this,window.id,window.title);finish()}
    override fun onStart(){super.onStart();Awl.registerCallback(callback);changes.trySend(Unit)}
    override fun onStop(){Awl.unregisterCallback(callback);super.onStop()}
}
