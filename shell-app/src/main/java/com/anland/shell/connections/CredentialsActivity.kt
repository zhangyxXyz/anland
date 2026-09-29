package com.anland.shell.connections

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anland.design.*
import com.anland.shell.ConsoleActivity
import com.anland.shell.Prefs
import com.anland.shell.R
import com.anland.shell.ds.DsCli
import kotlinx.coroutines.*

class CredentialsState(app:Application):AndroidViewModel(app) {
    private val context get()=getApplication<Application>()
    private val store=CredentialStore(app)
    var profiles by mutableStateOf<List<ConnectionProfile>>(emptyList());private set
    var containers by mutableStateOf<List<String>>(emptyList());private set
    var defaults by mutableStateOf<Set<String>>(emptySet());private set
    // ViewModel memory only: no SavedStateHandle/rememberSaveable containing secrets.
    var editor by mutableStateOf<ConnectionProfile?>(null)
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null)
    var openConsole by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false);private set
    init{reload()}
    private suspend fun load() {
        val data=withContext(Dispatchers.IO){store.list() to DsCli.listContainers().map{it.name}}
        profiles=data.first;containers=data.second
        defaults=profiles.filter{it.kind=="local" && Prefs.launchCredential(context,it.container)==it.id}.map{it.id}.toSet()
        loaded=true
    }
    private fun work(block:suspend ()->Unit) {
        if(busy)return
        busy=true
        viewModelScope.launch {
            try{block()}catch(e:CancellationException){throw e}
            catch(e:Exception){error=context.getString(R.string.credential_operation_failed)}
            finally{busy=false}
        }
    }
    fun reload()=work{load()}
    fun create(kind:String,container:String) {
        editor=ConnectionProfile(kind=kind,container=if(kind=="local")container else "")
    }
    fun save(login:Boolean)=work {
        val draft=editor?:return@work
        val cleaned=draft.copy(name=draft.name.trim(),username=draft.username.trim(),host=draft.host.trim().removeSurrounding("[","]"),
            password=if(draft.kind=="local"||draft.auth=="password")draft.password else "",
            privateKey=if(draft.kind=="ssh"&&draft.auth=="key")draft.privateKey else "",
            passphrase=if(draft.kind=="ssh"&&draft.auth=="key")draft.passphrase else "")
        withContext(Dispatchers.IO) {
            val previous=store.list().firstOrNull{it.id==cleaned.id}
            val profile=if(previous!=null && (previous.host!=cleaned.host || previous.port!=cleaned.port))cleaned.copy(hostKey="")else cleaned
            store.put(profile)
            if(previous?.kind=="local" && Prefs.launchCredential(context,previous.container)==previous.id &&
                (profile.container!=previous.container || profile.username!=previous.username)) {
                Prefs.setLaunchCredential(context,previous.container,"")
            }
        }
        editor=null;load()
        if(login)openConsole=cleaned.id
    }
    fun delete(profile:ConnectionProfile)=work {
        withContext(Dispatchers.IO){
            store.remove(profile.id)
            if(profile.kind=="local" && Prefs.launchCredential(context,profile.container)==profile.id)
                Prefs.setLaunchCredential(context,profile.container,"")
        }
        load()
    }
    fun useForLaunch(profile:ConnectionProfile)=work {
        try {
            withContext(Dispatchers.IO){
                LocalLogin.authenticate(context,profile)
                Prefs.setLaunchUser(context,profile.container,profile.username)
                Prefs.setLaunchCredential(context,profile.container,profile.id)
            }
            load()
        } catch(e:CancellationException){throw e}
        catch(e:Exception){error=e.message?:context.getString(R.string.credential_auth_failed)}
    }
    fun importKey(uri:android.net.Uri)=work {
        val id=editor?.id?:return@work
        val key=withContext(Dispatchers.IO){
            context.contentResolver.openInputStream(uri)?.use {
                val output=java.io.ByteArrayOutputStream()
                val chunk=ByteArray(8192)
                while(true){val count=it.read(chunk);if(count<0)break;require(output.size()+count<=256*1024);output.write(chunk,0,count)}
                val bytes=output.toByteArray()
                try{require(bytes.size<=256*1024);String(bytes,Charsets.UTF_8)}finally{bytes.fill(0)}
            }?:error("Cannot read key")
        }
        if(editor?.id==id)editor=editor?.copy(privateKey=key)
    }
}

class CredentialsActivity:AppCompatActivity() {
    private val state:CredentialsState by viewModels()
    @OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this);super.onCreate(savedInstanceState)
        setContent { WithAnlandTheme {
            var delete by remember{mutableStateOf<ConnectionProfile?>(null)}
            var discard by remember{mutableStateOf(false)}
            val back={if(state.editor!=null)discard=true else finish()}
            BackHandler{if(!state.busy)back()}
            // Protect secrets in recents/screenshots. Ordinary metadata/list pages
            // remain capturable; switching away destroys the in-memory editor.
            DisposableEffect(state.editor!=null) {
                if(state.editor!=null)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose{window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
            }
            LaunchedEffect(state.openConsole) {
                state.openConsole?.let { id->
                    state.openConsole=null
                    startActivity(Intent(this@CredentialsActivity,ConsoleActivity::class.java).putExtra("credential_id",id))
                }
            }
            Scaffold(containerColor=MaterialTheme.colorScheme.surface,topBar={TopAppBar(
                title={Text(stringResource(if(state.editor==null)R.string.credentials_title else R.string.credential_edit))},
                navigationIcon={IconButton(onClick=back,enabled=!state.busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(com.anland.design.R.string.design_back))}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface)
            )}) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    val draft=state.editor
                    if(draft!=null)ProfileEditor(state,draft)
                    else Page {
                        Text(stringResource(R.string.credentials_help),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("local" to R.string.credential_local,"ssh" to R.string.credential_ssh).forEach { (kind,label)->
                            SettingGroup(stringResource(label)) {
                                val connections=state.profiles.filter{it.kind==kind}
                                if(connections.isEmpty())Text(stringResource(R.string.credential_empty),Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium)
                                connections.forEach { profile ->
                                    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            SettingLeadingIcon(if(kind=="ssh")Icons.Outlined.Dns else Icons.Outlined.Person)
                                            Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                                                Text(profile.name,style=MaterialTheme.typography.titleMedium)
                                                Text(if(kind=="local")"${profile.username} · ${profile.container}"else "${profile.username}@${profile.host}:${profile.port}",style=MaterialTheme.typography.bodySmall)
                                                if(profile.id in state.defaults)Text(stringResource(R.string.credential_default),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick={state.editor=profile},enabled=!state.busy){Icon(Icons.Outlined.Edit,stringResource(R.string.credential_edit))}
                                            IconButton(onClick={delete=profile},enabled=!state.busy){Icon(Icons.Outlined.DeleteOutline,stringResource(R.string.credential_delete))}
                                        }
                                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(onClick={state.openConsole=profile.id},enabled=!state.busy){Icon(Icons.Outlined.Login,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.credential_login))}
                                            if(kind=="local")TextButton(onClick={state.useForLaunch(profile)},enabled=!state.busy){Text(stringResource(R.string.credential_use_launch))}
                                        }
                                    }
                                }
                                TextButton(onClick={state.create(kind,Prefs.activeContainer(this@CredentialsActivity))},enabled=state.loaded&&!state.busy,modifier=Modifier.padding(horizontal=16.dp)) {
                                    Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.credential_add))
                                }
                            }
                        }
                    }
                    if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            delete?.let { profile->AlertDialog(onDismissRequest={delete=null},title={Text(stringResource(R.string.credential_delete))},text={Text(stringResource(R.string.credential_delete_confirm,profile.name))},
                confirmButton={TextButton(onClick={state.delete(profile);delete=null}){Text(stringResource(R.string.credential_delete))}},
                dismissButton={TextButton(onClick={delete=null}){Text(stringResource(android.R.string.cancel))}}) }
            if(discard)AlertDialog(onDismissRequest={discard=false},text={Text(stringResource(R.string.credential_discard))},
                confirmButton={TextButton(onClick={state.editor=null;discard=false}){Text(stringResource(R.string.dialog_ok))}},
                dismissButton={TextButton(onClick={discard=false}){Text(stringResource(android.R.string.cancel))}})
            state.error?.let { message->AlertDialog(onDismissRequest={state.error=null},title={Text(stringResource(R.string.error_title))},text={Text(message)},
                confirmButton={TextButton(onClick={state.error=null}){Text(stringResource(R.string.dialog_ok))}}) }
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(state:CredentialsState,profile:ConnectionProfile) {
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)state.importKey(uri)}
    Page {
        SettingGroup(stringResource(if(profile.kind=="ssh")R.string.credential_ssh else R.string.credential_local)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(profile.name,{state.editor=profile.copy(name=it.take(120))},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.credential_name))},singleLine=true,enabled=!state.busy)
                if(profile.kind=="local") {
                    Text(stringResource(R.string.credential_local_help),style=MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        state.containers.forEach { name->FilterChip(profile.container==name,{state.editor=profile.copy(container=name)},label={Text(name)},enabled=!state.busy) }
                    }
                    if(state.containers.isEmpty())Text(stringResource(R.string.no_container_selected),color=MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.credential_ssh_help),style=MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(profile.host,{state.editor=profile.copy(host=it.take(253))},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.credential_host))},singleLine=true,enabled=!state.busy)
                    var port by remember(profile.id){mutableStateOf(profile.port.toString())}
                    OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5);state.editor=profile.copy(port=port.toIntOrNull()?:0)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.credential_port))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=profile.port !in 1..65535,enabled=!state.busy)
                }
                OutlinedTextField(profile.username,{state.editor=profile.copy(username=it.take(128))},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.credential_username))},singleLine=true,enabled=!state.busy)
                if(profile.kind=="ssh")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(profile.auth=="password",{state.editor=profile.copy(auth="password")},label={Text(stringResource(R.string.credential_password))},enabled=!state.busy)
                    FilterChip(profile.auth=="key",{state.editor=profile.copy(auth="key")},label={Text(stringResource(R.string.credential_key))},enabled=!state.busy)
                }
                if(profile.kind=="local"||profile.auth=="password") {
                    SecretField(profile.password,{state.editor=profile.copy(password=it.take(4096))},R.string.credential_password,!state.busy)
                } else {
                    OutlinedButton(onClick={import.launch(arrayOf("*/*"))},enabled=!state.busy){Icon(Icons.Outlined.FileOpen,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.credential_import_key))}
                    OutlinedTextField(profile.privateKey,{state.editor=profile.copy(privateKey=it.take(256*1024))},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.credential_key))},minLines=3,maxLines=5,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!state.busy)
                    SecretField(profile.passphrase,{state.editor=profile.copy(passphrase=it.take(4096))},R.string.credential_passphrase,!state.busy)
                }
                Text(stringResource(R.string.credential_storage_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Button(onClick={state.save(true)},enabled=profile.valid()&&!state.busy){Text(stringResource(R.string.credential_save_login))}
                    OutlinedButton(onClick={state.save(false)},enabled=profile.valid()&&!state.busy){Text(stringResource(R.string.credential_save))}
                }
            }
        }
    }
}

@Composable
private fun SecretField(value:String,change:(String)->Unit,label:Int,enabled:Boolean) {
    OutlinedTextField(value,change,Modifier.fillMaxWidth(),label={Text(stringResource(label))},singleLine=true,
        visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=enabled)
}
