package com.anlandnext

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import com.anland.design.*
import com.anlandnext.awl.Awl
import com.anlandnext.awl.AwlWindowHost
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** A desktop is another host of the SAME Wayland toplevels. It does not start
 * an X server, a nested desktop or another instance of an application. Input,
 * buffer import, IME and clipboard use the independent host implementation. */
class WorkspaceActivity:AppCompatActivity() {
    private class Pane(val id:Long,title:String?) {
        var title by mutableStateOf(title.orEmpty())
        var icon by mutableStateOf<Bitmap?>(null)
        var minimized by mutableStateOf(false)
        var maximized by mutableStateOf(false)
        var x by mutableFloatStateOf(24f)
        var y by mutableFloatStateOf(24f)
        var width by mutableFloatStateOf(760f)
        var height by mutableFloatStateOf(500f)
        var host:AwlWindowHost?=null
        var frame=Rect()
    }
    private val panes=mutableStateListOf<Pane>()
    private var selected by mutableLongStateOf(-1)
    private var loading by mutableStateOf(true)
    private var unavailable by mutableStateOf(false)
    private var confirm by mutableStateOf<String?>(null)
    private var closePending by mutableStateOf(false)
    private var resumed=false
    private var participating=false
    private var initialized=false
    private var refreshJob:Job?=null
    private var requestedFocus=-1L
    private var touchTarget:Pane?=null
    private var hoverTarget:Pane?=null
    private val events=object:Awl.Callback {
        override fun onWindowCreated(id:Long,title:String?){refresh()}
        override fun onWindowDestroyed(id:Long){WorkspaceController.windowGone(id);refresh()}
        override fun onWindowAttached(id:Long){}
        override fun onWindowDetached(id:Long){}
    }
    override fun onCreate(state:Bundle?) {
        applySavedAppearance(this);super.onCreate(state)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){
            override fun handleOnBackPressed(){moveTaskToBack(true)}
        })
        requestedFocus=intent.getLongExtra("id",-1)
        setContent{WithAnlandTheme { Workspace() }}
        lifecycleScope.launch {
            WorkspaceController.state.collect{s->
                if(participating && s.phase==WorkspaceController.Phase.IDLE && !s.desktop && !s.error)
                    finishAndRemoveTask()
            }
        }
        refresh(initial=true)
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        requestedFocus=intent.getLongExtra("id",-1)
        if(intent.getStringExtra("action")=="independent")confirm="leave"
        refresh()
    }
    private fun refresh(initial:Boolean=false) {
        refreshJob?.cancel()
        refreshJob=lifecycleScope.launch {
            val windows=withContext(Dispatchers.IO){Awl.getWindows()}
            unavailable=windows==null;loading=false
            if(windows==null)return@launch
            val alive=windows.map{it.id}.toSet()
            panes.filter{it.id !in alive}.toList().forEach{drop(it)}
            windows.forEach{w->if(panes.none{it.id==w.id})panes.add(Pane(w.id,w.title).apply{
                x=24f+(panes.size%6)*32f;y=24f+(panes.size%6)*24f
            })}
            if(requestedFocus>=0) {
                panes.find{it.id==requestedFocus}?.let{focus(it);requestedFocus=-1}
            }
            if(selected<0)panes.lastOrNull()?.let{focus(it)}
            if(!initialized) {
                initialized=true
                val s=WorkspaceController.state.value
                if(s.desktop) {
                    if(intent.getStringExtra("action")=="independent")confirm="leave"
                } else if(s.phase==WorkspaceController.Phase.IDLE) {
                    if(windows.isEmpty())enter(windows) else confirm="enter"
                }
            }
        }
    }
    private fun enter(windows:List<Awl.WlWindow>?=Awl.getWindows()) {
        if(windows==null){unavailable=true;return}
        participating=true
        WorkspaceController.begin(true,windows)
    }
    private fun leave() {
        val windows=Awl.getWindows() ?: run{unavailable=true;return}
        participating=true
        WorkspaceController.begin(false,windows)
    }
    private fun drop(pane:Pane) {
        pane.host?.onPause();pane.host?.onStop();pane.host?.onDestroy();pane.host=null
        panes.remove(pane)
        if(selected==pane.id){selected=-1;panes.lastOrNull{!it.minimized}?.let{focus(it)}}
        if(touchTarget===pane)touchTarget=null
        if(hoverTarget===pane)hoverTarget=null
    }
    private fun focus(pane:Pane) {
        if(selected!=pane.id)panes.find{it.id==selected}?.host?.onWindowFocusChanged(false)
        pane.minimized=false;selected=pane.id
        panes.remove(pane);panes.add(pane)
        pane.host?.onWindowFocusChanged(hasWindowFocus())
        updateStack()
    }
    private fun updateStack(){panes.forEachIndexed{i,p->p.host?.setCompositionOrder(-10000+i)}}
    private fun minimize(pane:Pane) {
        pane.host?.onWindowFocusChanged(false);pane.host?.onPause()
        pane.minimized=true
        if(selected==pane.id){selected=-1;panes.lastOrNull{!it.minimized}?.let{focus(it)}}
    }
    private fun makeHost(pane:Pane):AwlWindowHost {
        pane.host?.let{return it}
        val host=AwlWindowHost(this,Intent().putExtra("id",pane.id).putExtra("title",pane.title),true,false,
            object:AwlWindowHost.Listener {
                override fun onClosed(id:Long) {
                    // Eviction during a mode switch is not client destruction.
                    // The fresh daemon snapshot decides which windows still live.
                    window.decorView.post{refresh()}
                }
                override fun onAttached(id:Long) {
                    if(selected==id)pane.host?.onWindowFocusChanged(hasWindowFocus())
                }
                override fun onIdentity(id:Long,title:String?,icon:Bitmap?) {
                    pane.title=title.orEmpty();pane.icon=icon
                }
            })
        pane.host=host
        host.onCreate(null);host.onStart()
        if(resumed)host.onResume()
        updateStack()
        return host
    }
    override fun onStart(){super.onStart();Awl.registerCallback(events)}
    override fun onResume(){super.onResume();resumed=true;Awl.ensureSubscribed();panes.filter{!it.minimized}.forEach{it.host?.onResume()};refresh()}
    override fun onPause(){resumed=false;panes.forEach{it.host?.onPause()};super.onPause()}
    override fun onStop(){Awl.unregisterCallback(events);super.onStop()}
    override fun onDestroy(){panes.toList().forEach{drop(it)};super.onDestroy()}
    override fun onWindowFocusChanged(focus:Boolean){super.onWindowFocusChanged(focus);panes.find{it.id==selected}?.host?.onWindowFocusChanged(focus)}
    override fun onPointerCaptureChanged(capture:Boolean){super.onPointerCaptureChanged(capture);panes.find{it.id==selected}?.host?.onPointerCaptureChanged(capture)}
    private fun paneAt(event:MotionEvent):Pane? {
        // The top pane's decorations occlude lower surfaces too. Testing only
        // SurfaceView rectangles lets a Close button click fall through to the
        // client underneath its title bar.
        val p=panes.asReversed().firstOrNull{!it.minimized && it.frame.contains(event.x.toInt(),event.y.toInt())} ?: return null
        val surface=p.host?.surfaceView ?: return null
        if(!surface.isShown)return null
        val r=Rect();surface.getGlobalVisibleRect(r)
        return if(r.contains(event.rawX.toInt(),event.rawY.toInt()))p else null
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(confirm!=null)return super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_DOWN){touchTarget=paneAt(event);touchTarget?.let{focus(it)}}
        val target=touchTarget
        val handled=target?.host?.dispatchTouchEvent(event)==true
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)touchTarget=null
        return handled || super.dispatchTouchEvent(event)
    }
    override fun onGenericMotionEvent(event:MotionEvent):Boolean {
        if(confirm!=null)return super.onGenericMotionEvent(event)
        val target=panes.find{it.id==selected && it.host?.hasPointerCapture()==true} ?: paneAt(event)
        if(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS)target?.let{focus(it)}
        if(hoverTarget!==target) {
            hoverTarget?.host?.let{host->val exit=MotionEvent.obtain(event);exit.action=MotionEvent.ACTION_HOVER_EXIT;host.onGenericMotionEvent(exit);exit.recycle()}
            hoverTarget=target
        }
        return target?.host?.onGenericMotionEvent(event)==true || super.onGenericMotionEvent(event)
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean =
        (confirm==null && panes.find{it.id==selected}?.host?.dispatchKeyEvent(event)==true) || super.dispatchKeyEvent(event)

    @Composable private fun Workspace() {
        val state by WorkspaceController.state.collectAsState()
        var menu by remember{mutableStateOf(false)}
        val show=state.phase!=WorkspaceController.Phase.LEAVING && (state.desktop || state.phase==WorkspaceController.Phase.ENTERING)
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal=12.dp)) {
                Row(Modifier.fillMaxWidth().height(64.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.DesktopWindows,null,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(com.anland.design.R.string.workspace_title),style=MaterialTheme.typography.titleLarge)
                        Text(stringResource(com.anland.design.R.string.workspace_scope),style=MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(onClick={try{startActivity(Intent().setClassName("com.anland.shell","com.anland.shell.ShellActivity"))}catch(_:android.content.ActivityNotFoundException){}}){Icon(Icons.Outlined.Apps,null);Spacer(Modifier.width(8.dp));Text(stringResource(com.anland.design.R.string.workspace_apps))}
                    IconButton(onClick={moveTaskToBack(true)}){Icon(Icons.Outlined.Home,stringResource(com.anland.design.R.string.workspace_hide))}
                    Box {
                        IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,stringResource(com.anland.design.R.string.workspace_menu))}
                        DropdownMenu(menu,{menu=false}) {
                            DropdownMenuItem(text={Text(stringResource(com.anland.design.R.string.workspace_to_independent))},enabled=state.phase==WorkspaceController.Phase.IDLE,onClick={menu=false;confirm="leave"})
                            DropdownMenuItem(text={Text(stringResource(com.anland.design.R.string.workspace_end))},enabled=state.phase==WorkspaceController.Phase.IDLE,onClick={menu=false;confirm="end"})
                        }
                    }
                }
                if(state.phase!=WorkspaceController.Phase.IDLE) {
                    Text(stringResource(com.anland.design.R.string.workspace_switching),style=MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    val availableW=maxWidth.value;val availableH=maxHeight.value
                    if(loading)CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else if(unavailable)Text(stringResource(com.anland.design.R.string.workspace_unavailable),Modifier.align(Alignment.Center))
                    else if(panes.isEmpty())Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){
                        Icon(Icons.Outlined.Widgets,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp));Text(stringResource(com.anland.design.R.string.workspace_empty))
                    }
                    if(show)panes.toList().forEachIndexed{index,pane->key(pane.id){
                        if(!pane.minimized) {
                            // API 36 defines per-SurfaceView composition order.
                            // Older systems show a single active pane to avoid
                            // undefined overlapping surface order, with Dock switching.
                            val tiled=Build.VERSION.SDK_INT<36
                            val full=pane.maximized || tiled
                            val w=if(full)availableW else pane.width.coerceIn(minOf(320f,availableW),availableW)
                            val h=if(full)availableH else pane.height.coerceIn(minOf(220f,availableH),availableH)
                            val x=if(full)0f else pane.x.coerceIn(0f,(availableW-w).coerceAtLeast(0f))
                            val y=if(full)0f else pane.y.coerceIn(0f,(availableH-h).coerceAtLeast(0f))
                            if(!tiled || pane.id==selected)PaneView(pane,Modifier.offset{x.dp.roundToPx().let{IntOffset(it,y.dp.roundToPx())}}.size(w.dp,h.dp).zIndex(index.toFloat()),availableW,availableH)
                        }
                    }}
                }
                LazyRow(Modifier.fillMaxWidth().height(64.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    items(panes.toList(),key={it.id}){p->FilterChip(selected=selected==p.id && !p.minimized,onClick={focus(p)},label={Text(p.title.ifBlank{"#${p.id}"},maxLines=1,modifier=Modifier.widthIn(max=180.dp))},leadingIcon={p.icon?.let{Image(it.asImageBitmap(),null,Modifier.size(22.dp))}?:Icon(Icons.Outlined.Window,null)})}
                }
            }
        }
        confirm?.let{action->
            AlertDialog(onDismissRequest={confirm=null;if(!show)finishAndRemoveTask()},
                title={Text(stringResource(when(action){"enter"->com.anland.design.R.string.workspace_to_desktop;"leave"->com.anland.design.R.string.workspace_to_independent;else->com.anland.design.R.string.workspace_end}))},
                text={Text(stringResource(when(action){"enter"->com.anland.design.R.string.workspace_enter_confirm;"leave"->com.anland.design.R.string.workspace_leave_confirm;else->com.anland.design.R.string.workspace_end_confirm},panes.size))},
                confirmButton={TextButton(onClick={confirm=null;when(action){"enter"->enter();"leave"->leave();else->endSession()}}){Text(stringResource(android.R.string.ok))}},
                dismissButton={TextButton(onClick={confirm=null;if(!show)finishAndRemoveTask()}){Text(stringResource(android.R.string.cancel))}})
        }
        if(state.error)AlertDialog(onDismissRequest={WorkspaceController.clearError();if(!state.desktop)finishAndRemoveTask()},text={Text(stringResource(com.anland.design.R.string.workspace_failed))},confirmButton={TextButton(onClick={WorkspaceController.clearError();if(!state.desktop)finishAndRemoveTask()}){Text(stringResource(android.R.string.ok))}})
        if(closePending)AlertDialog(onDismissRequest={closePending=false},text={Text(stringResource(com.anland.design.R.string.workspace_close_pending))},confirmButton={TextButton(onClick={closePending=false}){Text(stringResource(android.R.string.ok))}})
    }
    @Composable private fun PaneView(pane:Pane,modifier:Modifier,maxW:Float,maxH:Float) {
        val density=LocalDensity.current.density
        Surface(modifier.onGloballyPositioned{val r=it.boundsInWindow();pane.frame=Rect(r.left.toInt(),r.top.toInt(),r.right.toInt(),r.bottom.toInt())},tonalElevation=if(selected==pane.id)8.dp else 2.dp,shadowElevation=8.dp) {
            Column {
                Row(Modifier.fillMaxWidth().height(44.dp),verticalAlignment=Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).fillMaxHeight().pointerInput(pane.id,maxW,maxH){detectDragGestures(onDragStart={focus(pane)}){change,amount->
                        change.consume();if(!pane.maximized){pane.x=(pane.x+amount.x/density).coerceIn(0f,(maxW-minOf(pane.width,maxW)).coerceAtLeast(0f));pane.y=(pane.y+amount.y/density).coerceIn(0f,(maxH-minOf(pane.height,maxH)).coerceAtLeast(0f))}
                    }}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        pane.icon?.let{Image(it.asImageBitmap(),null,Modifier.size(20.dp))}
                        Text(pane.title.ifBlank{"#${pane.id}"},maxLines=1,style=MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick={minimize(pane)},Modifier.size(40.dp)){Icon(Icons.Outlined.Minimize,stringResource(com.anland.design.R.string.workspace_minimize))}
                    IconButton(onClick={focus(pane);pane.maximized=!pane.maximized},Modifier.size(40.dp)){Icon(if(pane.maximized)Icons.Outlined.FilterNone else Icons.Outlined.CropSquare,stringResource(com.anland.design.R.string.workspace_maximize))}
                    IconButton(onClick={Awl.closeWindow(pane.id)},Modifier.size(40.dp)){Icon(Icons.Outlined.Close,stringResource(com.anland.design.R.string.workspace_close))}
                }
                AndroidView(factory={makeHost(pane).view},modifier=Modifier.weight(1f).fillMaxWidth(),
                    onRelease={pane.host?.onPause();pane.host?.onStop();pane.host?.onDestroy();pane.host=null})
                if(!pane.maximized)Row(Modifier.fillMaxWidth().height(16.dp),horizontalArrangement=Arrangement.End){
                    Icon(Icons.Outlined.DragHandle,stringResource(com.anland.design.R.string.workspace_resize),Modifier.width(48.dp).fillMaxHeight().pointerInput(pane.id,maxW,maxH){detectDragGestures(onDragStart={focus(pane)}){change,amount->change.consume();pane.width=(pane.width+amount.x/density).coerceIn(minOf(320f,maxW),maxW);pane.height=(pane.height+amount.y/density).coerceIn(minOf(220f,maxH),maxH)}})
                }
            }
        }
    }
    private fun endSession(){
        lifecycleScope.launch {
            withContext(Dispatchers.IO){Awl.getWindows()?.forEach{Awl.closeWindow(it.id)}}
            delay(1500);val remaining=withContext(Dispatchers.IO){Awl.getWindows()}
            if(remaining?.isEmpty()==true)leave() else {closePending=true;refresh()}
        }
    }
}
