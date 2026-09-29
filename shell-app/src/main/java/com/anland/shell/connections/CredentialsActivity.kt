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
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
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
    var discardEditor by mutableStateOf(false)
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null)
    var openConsole by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false);private set
    init{reload()}
    private suspend fun load() {
        val data=withContext(Dispatchers.IO){store.list() to DsCli.listContainers().map{it.name}}
        profiles=data.first;containers=data.second
        defaults=profiles.filter{it.container.isNotBlank() && Prefs.launchCredential(context,it.container)==it.id}.map{it.id}.toSet()
        loaded=true
    }
    private fun work(block:suspend ()->Unit) {
        if(busy)return
        busy=true
        viewModelScope.launch {
            try{block()}catch(e:CancellationException){throw e}
            catch(e:Exception){
                val keyStoreFailure=generateSequence<Throwable>(e){it.cause}.any {
                    (android.os.Build.VERSION.SDK_INT>=33 && it is android.security.KeyStoreException) || it is java.security.KeyStoreException
                }
                error=context.getString(if(keyStoreFailure)R.string.credentials_keystore_unavailable else R.string.credential_operation_failed)
            }
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
            // Keep the gate enabled after credential edits. Changed identities
            // must fail verification, never silently become unverified launches.
            check(previous==null || previous.container==profile.container ||
                Prefs.launchCredential(context,previous.container)!=previous.id) {
                context.getString(R.string.ssh_launch_disable_before_move)
            }
            store.put(profile)
        }
        editor=null;load()
        if(login)openConsole=cleaned.id
    }
    fun delete(profile:ConnectionProfile)=work {
        withContext(Dispatchers.IO){
            store.remove(profile.id)
            if(profile.container.isNotBlank() && Prefs.launchCredential(context,profile.container)==profile.id)
                Prefs.setLaunchCredential(context,profile.container,"")
        }
        load()
    }
    fun useForLaunch(profile:ConnectionProfile,onApplied:()->Unit={},container:String=profile.container)=work {
        try {
            withContext(Dispatchers.IO){
                val current=store.get(profile.id)
                val target=if(current.kind=="ssh")current.copy(container=container)else current
                LaunchLogin.authenticate(context,target)
                if(target!=current)store.put(target)
                if(current.container.isNotBlank() && current.container!=target.container &&
                    Prefs.launchCredential(context,current.container)==current.id)
                    Prefs.setLaunchCredential(context,current.container,"")
                Prefs.setVerifiedLaunchUser(context,target.container,target.username,target.id)
            }
            load()
            onApplied()
        } catch(e:CancellationException){throw e}
        catch(e:Exception){error=e.message?:context.getString(R.string.credential_auth_failed)}
    }
    fun setLaunchVerification(profile:ConnectionProfile,enabled:Boolean,onApplied:()->Unit={}) {
        if(enabled) { useForLaunch(profile,onApplied);return }
        work {
            if(profile.container.isNotBlank() && Prefs.launchCredential(context,profile.container)==profile.id)
                Prefs.setLaunchCredential(context,profile.container,"")
            load()
            onApplied()
        }
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
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this);super.onCreate(savedInstanceState)
        setContent { WithAnlandTheme {
            val back={if(!state.busy){if(state.editor!=null)state.discardEditor=true else finish()}}
            BackHandler(onBack=back)
            Scaffold(containerColor=MaterialTheme.colorScheme.surface,topBar={TopAppBar(
                title={Text(stringResource(if(state.editor==null)R.string.credentials_title else R.string.credential_edit))},
                navigationIcon={IconButton(onClick=back,enabled=!state.busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(com.anland.design.R.string.design_back))}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface)
            )}) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    CredentialsPage(state,window)
                }
            }
        } }
    }
}

/** Shared by the primary destination and the standalone credentials activity.
 * The editor remains in ViewModel memory and protects the owning window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CredentialsPage(state:CredentialsState,window:android.view.Window,
                    onLaunchChanged:()->Unit={},header:@Composable ()->Unit={}) {
    val context=LocalContext.current
    var delete by remember { mutableStateOf<ConnectionProfile?>(null) }
    var chooseKind by remember { mutableStateOf(false) }
    var sshLaunch by remember { mutableStateOf<ConnectionProfile?>(null) }
    BackHandler(state.editor!=null) { if(!state.busy)state.discardEditor=true }
    DisposableEffect(state.editor!=null,window) {
        if(state.editor!=null)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(state.openConsole) {
        state.openConsole?.let { id ->
            state.openConsole=null
            context.startActivity(Intent(context,ConsoleActivity::class.java).putExtra("credential_id",id))
        }
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        val draft=state.editor
        if(draft!=null)key(draft.id){ProfileEditor(state,draft)}
        else Page {
            header()
            SettingGroup(stringResource(R.string.launch_saved_credentials)) {
                    if(state.profiles.isEmpty())Text(stringResource(R.string.credentials_empty),Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium)
                    state.profiles.forEachIndexed { index,profile ->
                        val kind=profile.kind
                        if(index>0)HorizontalDivider(Modifier.padding(horizontal=20.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                SettingLeadingIcon(if(kind=="ssh")Icons.Outlined.Dns else Icons.Outlined.Person)
                                Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                                    Text(profile.name,style=MaterialTheme.typography.titleMedium)
                                    Text(if(kind=="local")"${profile.username} · ${profile.container}"else "${profile.username}@${profile.host}:${profile.port}",style=MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                    val label=stringResource(R.string.credential_verify_launch)
                                    Switch(checked=profile.id in state.defaults,enabled=!state.busy,
                                        modifier=Modifier.semantics{contentDescription=label},
                                        onCheckedChange={enabled->
                                            if(enabled && kind=="ssh")sshLaunch=profile
                                            else state.setLaunchVerification(profile,enabled,onLaunchChanged)
                                        })
                                    Text(label,style=MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(if(kind=="local")stringResource(R.string.credential_verify_launch_help)
                                else if(profile.container.isBlank())stringResource(R.string.ssh_launch_help)
                                else stringResource(R.string.ssh_launch_bound_help,profile.container,profile.username),
                                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick={state.openConsole=profile.id},enabled=!state.busy){Icon(Icons.AutoMirrored.Outlined.Login,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.credential_login))}
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick={state.editor=profile},enabled=!state.busy){Icon(Icons.Outlined.Edit,stringResource(R.string.credential_edit))}
                                IconButton(onClick={delete=profile},enabled=!state.busy){Icon(Icons.Outlined.DeleteOutline,stringResource(R.string.credential_delete))}
                            }
                        }
                    }
                    TextButton(onClick={chooseKind=true},enabled=state.loaded&&!state.busy,modifier=Modifier.padding(horizontal=16.dp)) {
                        Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.credentials_add))
                    }
            }
        }
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    sshLaunch?.let { profile ->
        var selected by remember(profile.id) { mutableStateOf(
            profile.container.takeIf{it in state.containers}
                ?:Prefs.activeContainer(context).takeIf{it in state.containers}
                ?:state.containers.firstOrNull().orEmpty()) }
        AlertDialog(onDismissRequest={sshLaunch=null},title={Text(stringResource(R.string.ssh_launch_bind_title))},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ssh_launch_bind_help,profile.username))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    state.containers.forEach { name ->
                        FilterChip(selected==name,{selected=name},label={Text(name)})
                    }
                }
                if(state.containers.isEmpty())Text(stringResource(R.string.no_container_selected))
            }},confirmButton={TextButton(enabled=selected.isNotBlank()&&!state.busy,onClick={
                state.useForLaunch(profile,onLaunchChanged,selected);sshLaunch=null
            }){Text(stringResource(R.string.ssh_launch_bind_confirm))}},
            dismissButton={TextButton(onClick={sshLaunch=null}){Text(stringResource(android.R.string.cancel))}})
    }
    if(chooseKind)AlertDialog(onDismissRequest={chooseKind=false},title={Text(stringResource(R.string.credentials_add))},
        text={Column {
            NavigationSettingItem(stringResource(R.string.credentials_local_account),
                description=stringResource(R.string.credentials_local_account_help),icon=Icons.Outlined.Person,
                onClick={chooseKind=false;state.create("local",Prefs.activeContainer(context))})
            NavigationSettingItem(stringResource(R.string.credentials_remote_ssh),
                description=stringResource(R.string.credentials_remote_ssh_help),icon=Icons.Outlined.Dns,
                onClick={chooseKind=false;state.create("ssh","")})
        }},confirmButton={},dismissButton={TextButton(onClick={chooseKind=false}){Text(stringResource(android.R.string.cancel))}})
    delete?.let { profile -> AlertDialog(onDismissRequest={delete=null},title={Text(stringResource(R.string.credential_delete))},text={Text(stringResource(R.string.credential_delete_confirm,profile.name))},
        confirmButton={TextButton(onClick={state.delete(profile);delete=null}){Text(stringResource(R.string.credential_delete))}},
        dismissButton={TextButton(onClick={delete=null}){Text(stringResource(android.R.string.cancel))}}) }
    if(state.discardEditor)AlertDialog(onDismissRequest={state.discardEditor=false},text={Text(stringResource(R.string.credential_discard))},
        confirmButton={TextButton(onClick={state.editor=null;state.discardEditor=false}){Text(stringResource(R.string.dialog_ok))}},
        dismissButton={TextButton(onClick={state.discardEditor=false}){Text(stringResource(android.R.string.cancel))}})
    CredentialsError(state)
}

@Composable
fun CredentialsError(state:CredentialsState) {
    // Only fixed, non-secret error messages are capturable; the editor behind stays secure.
    state.error?.let { message -> AlertDialog(onDismissRequest={state.error=null},title={Text(stringResource(R.string.error_title))},text={Text(message)},
        properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOff),
        confirmButton={TextButton(onClick={state.error=null}){Text(stringResource(R.string.dialog_ok))}}) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(state:CredentialsState,profile:ConnectionProfile) {
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)state.importKey(uri)}
    Page {
        SettingGroup(stringResource(if(profile.kind=="ssh")R.string.credentials_remote_ssh else R.string.credentials_local_account)) {
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
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value,change,Modifier.fillMaxWidth(),label={Text(stringResource(label))},singleLine=true,
        visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon={IconButton(onClick={visible=!visible},enabled=enabled) {
            Icon(if(visible)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                stringResource(if(visible)R.string.credentials_hide_password else R.string.credentials_show_password))
        }},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=enabled)
}
