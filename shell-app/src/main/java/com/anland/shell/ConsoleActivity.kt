package com.anland.shell

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anland.design.*
import com.anland.shell.ds.DsCli
import com.anland.shell.ds.EnvVars
import com.anland.shell.connections.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream

/**
 * In-container console over the persistent session started by DsCli.consoleArgv
 * (DS_NO_PROXY=1 … run sh): lines go to stdin, output streams from a reader, and
 * cd/env state persists for the session. The selected launch user (auto-detected
 * desktop user by default) matches app launches, so ~/.anlandx is authorized.
 * This is a plain pipe, not a PTY: no prompt echo, Tab completion or control
 * characters. Volume keys adjust font size. A ViewModel retains the process
 * through configuration changes; finishing the console releases it.
 */
class ConsoleSession(app:Application):AndroidViewModel(app) {
    private val context get()=getApplication<Application>()
    var output by mutableStateOf("");private set
    var ready by mutableStateOf(false);private set
    var dead by mutableStateOf(false);private set
    var user by mutableStateOf("");private set
    var title by mutableStateOf("");private set
    var hostChallenge by mutableStateOf<HostTrustRequired?>(null);private set
    var font by mutableIntStateOf(Prefs.consoleFontSp(app));private set
    private var started=false
    private val lifecycle=Any()
    private var closed=false
    private var proc:Process?=null
    private var stdin:OutputStream?=null
    private var ssh:SshLogin.Console?=null
    private var requestedContainer=""
    private var requestedCredential=""
    private val writer=Mutex()
    fun font(delta:Int){Prefs.setConsoleFontSp(context,font+delta);font=Prefs.consoleFontSp(context)}
    private fun append(text:String){output=(output+text).takeLast(128*1024)}
    fun start(container:String,credentialId:String="") {
        if(started)return
        started=true
        requestedContainer=container;requestedCredential=credentialId
        viewModelScope.launch {
            try {
                val profile=withContext(Dispatchers.IO){if(credentialId.isEmpty())null else CredentialStore(context).get(credentialId)}
                title=profile?.name?:container
                if(profile?.kind=="ssh") {
                    user=profile.username
                    val connection=withContext(Dispatchers.IO){
                        val created=SshLogin.console(profile)
                        synchronized(lifecycle) {
                            if(closed){created.close();throw CancellationException("Console closed")}
                            ssh=created;stdin=created.output
                        }
                        created
                    }
                    ready=true
                    readOutput(connection.input)
                    append("\n${context.getString(R.string.console_ended)}\n")
                    return@launch
                }
                val localContainer=profile?.container?:container
                // Resolve the launch user first: home, environment and X access
                // must agree with normal app launches, including auto selection.
                user=withContext(Dispatchers.IO){
                    val selected=profile?.username?:Prefs.launchUser(context,localContainer).ifEmpty{DsCli.autoUser(localContainer)}
                    if(profile!=null)LocalLogin.authenticate(context,profile)
                    else LaunchLogin.authenticateLaunch(context,localContainer,selected)
                    selected
                }
                val child=withContext(Dispatchers.IO){
                    val created=ProcessBuilder(*DsCli.consoleArgv(localContainer,user)).redirectErrorStream(true).start()
                    // Creation may finish after the activity was closed. Publish the
                    // child under the same lock as cleanup so cancellation cannot leak it.
                    synchronized(lifecycle) {
                        if(closed){created.destroyForcibly();throw CancellationException("Console closed")}
                        proc=created;stdin=created.outputStream
                    }
                    created
                }
                val custom=EnvVars.parse(Prefs.launchEnv(context,localContainer))
                val merged=EnvVars.merge(DsCli.defaultEnvPairs(),custom)
                append(context.getString(R.string.console_env_note,EnvVars.format(merged).replace("\n"," "))+"\n")
                // Preamble: cd ~ + built-ins/customizations + session env and
                // DISPLAY. Echo locally; the non-tty shell has no prompt.
                write(DsCli.consolePreamble(custom))
                ready=true
                readOutput(child.inputStream)
                withContext(Dispatchers.IO) {
                    val code=child.waitFor()
                    withContext(Dispatchers.Main){append("\n${context.getString(R.string.console_ended)} (exit $code)\n")}
                }
            } catch(e:CancellationException){throw e}
            catch(e:HostTrustRequired){hostChallenge=e}
            catch(e:Exception){append("\n${context.getString(R.string.credential_connection_failed)}\n${e.message}\n")}
            finally {ready=false;dead=true;synchronized(lifecycle){ssh?.close();ssh=null;proc?.destroy();proc=null;stdin=null}}
        }
    }
    private suspend fun readOutput(input:java.io.InputStream)=withContext(Dispatchers.IO) {
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val chunk=CharArray(4096)
            while(true){val n=reader.read(chunk);if(n<0)break;val text=String(chunk,0,n);withContext(Dispatchers.Main){append(text)}}
        }
    }
    fun dismissHost(){hostChallenge=null;append(context.getString(R.string.credential_host_cancelled)+"\n")}
    fun trustHost() {
        val challenge=hostChallenge?:return
        hostChallenge=null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store=CredentialStore(context)
                    val latest=store.get(challenge.profile.id)
                    check(latest.host==challenge.profile.host && latest.port==challenge.profile.port){"Connection changed"}
                    store.put(latest.copy(hostKey=challenge.presentedKey))
                }
                started=false;dead=false;start(requestedContainer,requestedCredential)
            } catch(e:Exception){append(context.getString(R.string.credential_connection_failed)+"\n")}
        }
    }
    private suspend fun write(line:String)=writer.withLock { withContext(Dispatchers.IO){stdin?.let{it.write((line+"\n").toByteArray(Charsets.UTF_8));it.flush()}} }
    fun send(command:String){if(!ready)return;append("$ $command\n");if(command.isNotBlank())Prefs.addHistory(context,command)
        viewModelScope.launch{try{write(command)}catch(e:Exception){ready=false;dead=true;append("\n${e.message}\n")}}
    }
    override fun onCleared(){synchronized(lifecycle){closed=true;ssh?.close();proc?.destroyForcibly();runCatching{stdin?.close()}};super.onCleared()}
}

class ConsoleActivity:AppCompatActivity() {
    private val session:ConsoleSession by viewModels()
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState:Bundle?) {
        applySavedAppearance(this)
        super.onCreate(savedInstanceState)
        val container=intent.getStringExtra("container").orEmpty()
        val credential=intent.getStringExtra("credential_id").orEmpty()
        if(container.isBlank()&&credential.isBlank()){finish();return}
        session.start(container,credential)
        setContent{WithAnlandTheme{
            var command by rememberSaveable{mutableStateOf("")}
            var history by rememberSaveable{mutableStateOf(false)}
            var historySearch by rememberSaveable{mutableStateOf("")}
            var exit by rememberSaveable{mutableStateOf(false)}
            var options by remember{mutableStateOf(false)}
            val clipboard=LocalClipboardManager.current
            val back={if(session.dead)finish()else exit=true}
            BackHandler(onBack=back)
            val scroll=rememberScrollState()
            // Keep following output only while already at the bottom; reading older
            // output must not be interrupted by another arriving chunk.
            var follow by remember{mutableStateOf(true)}
            LaunchedEffect(scroll){snapshotFlow{scroll.value to scroll.maxValue}.collect{(value,max)->if(!scroll.isScrollInProgress && value>=max)follow=true else if(scroll.isScrollInProgress)follow=value>=max-32}}
            LaunchedEffect(session.output){if(follow)scroll.scrollTo(scroll.maxValue)}
            val send={if(session.ready){session.send(command);command=""}}
            BoxWithConstraints {
                val wide=maxWidth>=840.dp
                Scaffold(containerColor=MaterialTheme.colorScheme.surface,topBar={TopAppBar(
                    title={Column {Text(stringResource(R.string.enter_console));Text(session.title+if(session.user.isNotBlank())" / ${session.user}" else "",style=MaterialTheme.typography.labelMedium)}},
                    navigationIcon={IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(com.anland.design.R.string.design_back))}},
                    actions={
                        IconButton(onClick={clipboard.setText(AnnotatedString(session.output))}){Icon(Icons.Outlined.ContentCopy,stringResource(R.string.design_copy_output))}
                        IconButton(onClick={history=!history}){Icon(Icons.Outlined.History,stringResource(R.string.history))}
                        Box {
                            IconButton(onClick={options=true}){Icon(Icons.Outlined.MoreVert,stringResource(R.string.design_console_options))}
                            DropdownMenu(options,{options=false}) {
                                DropdownMenuItem(text={Text(stringResource(R.string.design_font_larger))},onClick={session.font(1)})
                                DropdownMenuItem(text={Text(stringResource(R.string.design_font_smaller))},onClick={session.font(-1)})
                            }
                        }
                    },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
                }){padding->Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    Card(Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Terminal,null)
                                Text(session.title,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                                StatusPill(stringResource(if(session.dead)R.string.console_ended else if(session.ready)R.string.design_session_ready else R.string.design_connecting),active=session.ready)
                            }
                            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                            if(!session.ready&&!session.dead)LinearProgressIndicator(Modifier.fillMaxWidth())
                            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)){Text(session.output,Modifier.padding(18.dp),fontFamily=FontFamily.Monospace,fontSize=session.font.sp)}
                            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                OutlinedTextField(command,{command=it},Modifier.weight(1f),enabled=session.ready,singleLine=true,
                                    leadingIcon={Text("$",fontFamily=FontFamily.Monospace)},placeholder={Text(stringResource(R.string.console_hint))},textStyle=MaterialTheme.typography.bodyLarge.copy(fontFamily=FontFamily.Monospace,fontSize=session.font.sp),
                                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),shape=RoundedCornerShape(18.dp))
                                FilledTonalIconButton(onClick=send,enabled=session.ready,modifier=Modifier.size(52.dp)){Icon(Icons.AutoMirrored.Outlined.Send,stringResource(R.string.console_send))}
                            }
                        }
                    }
                    if(wide && history)Surface(Modifier.width(300.dp).fillMaxHeight(),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                        ConsoleHistory(Prefs.history(this@ConsoleActivity).asReversed(),historySearch,{historySearch=it},{command=it},{history=false})
                    }
                }}
                if(!wide && history)ModalBottomSheet(onDismissRequest={history=false}) {
                    Box(Modifier.fillMaxWidth().heightIn(max=540.dp).navigationBarsPadding()) {
                        ConsoleHistory(Prefs.history(this@ConsoleActivity).asReversed(),historySearch,{historySearch=it},{command=it;history=false},{history=false})
                    }
                }
            }
            if(exit)AlertDialog(onDismissRequest={exit=false},text={Text(stringResource(R.string.console_exit_confirm))},confirmButton={TextButton(onClick={finish()}){Text(stringResource(R.string.dialog_ok))}},dismissButton={TextButton(onClick={exit=false}){Text(stringResource(android.R.string.cancel))}})
            session.hostChallenge?.let { challenge ->
                AlertDialog(onDismissRequest=session::dismissHost,
                    title={Text(stringResource(if(challenge.changed)R.string.credential_host_changed else R.string.credential_host_new))},
                    text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("${challenge.profile.host}:${challenge.profile.port}")
                        Text(stringResource(R.string.credential_host_check))
                        SelectionContainer{Text(challenge.fingerprint,fontFamily=FontFamily.Monospace)}
                        if(challenge.changed)Text(stringResource(R.string.credential_host_previous,HostTrustRequired.fingerprint(challenge.profile.hostKey)))
                    }},
                    confirmButton={TextButton(onClick=session::trustHost){Text(stringResource(if(challenge.changed)R.string.credential_host_replace else R.string.credential_host_trust))}},
                    dismissButton={TextButton(onClick=session::dismissHost){Text(stringResource(android.R.string.cancel))}})
            }
        }}
    }
    /** Volume keys adjust the console font size (persisted), as before. */
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(keyCode==KeyEvent.KEYCODE_VOLUME_UP||keyCode==KeyEvent.KEYCODE_VOLUME_DOWN){session.font(if(keyCode==KeyEvent.KEYCODE_VOLUME_UP)1 else -1);return true}
        return super.onKeyDown(keyCode,event)
    }
}


/** History selection edits the composer only. Sending is always a separate action. */
@Composable
private fun ConsoleHistory(history:List<String>,query:String,onQuery:(String)->Unit,onSelect:(String)->Unit,onClose:()->Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.history),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            IconButton(onClick=onClose){Icon(Icons.Outlined.Close,stringResource(android.R.string.cancel))}
        }
        WorkspaceSearch(query,onQuery,stringResource(R.string.design_search_history),Modifier.fillMaxWidth())
        val filtered=history.filter{it.contains(query,true)}
        if(filtered.isEmpty())Text(stringResource(R.string.history_empty),style=MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(filtered){item->
                Surface(onClick={onSelect(item)},shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                    Text(item,Modifier.fillMaxWidth().padding(vertical=16.dp,horizontal=12.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Text(stringResource(R.string.design_history_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
