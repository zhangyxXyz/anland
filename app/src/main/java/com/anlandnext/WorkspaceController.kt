package com.anlandnext

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.anlandnext.awl.Awl
import com.anlandnext.awl.AwlWindowHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation belongs to the host, not to a launcher's copy of a preference.
 * No DISPLAY, session bus, client process or global auto_attach setting changes.
 * One workspace spans this Anland display server, including all its containers.
 */
object WorkspaceController : Awl.WindowRouter {
    enum class Phase { IDLE, ENTERING, LEAVING }
    data class State(val desktop:Boolean=false, val phase:Phase=Phase.IDLE, val error:Boolean=false)
    val uri:Uri=Uri.parse("content://com.anlandnext.workspace/state")
    private lateinit var app:Context
    private val main=Handler(Looper.getMainLooper())
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val pending=linkedMapOf<Long,Awl.WlWindow>()
    private var moving:Long?=null
    private var generation=0L
    private var origin=false
    private val prefs get()=app.getSharedPreferences("workspace",Context.MODE_PRIVATE)

    fun initialize(context:Context) {
        app=context.applicationContext
        // A killed UI cannot finish a transaction. Recover the committed mode;
        // live windows are enumerated again rather than persisting reusable IDs.
        mutable.value=State(prefs.getBoolean("desktop",false),error=prefs.getBoolean("transition",false))
        prefs.edit().putBoolean("transition",false).apply()
        Awl.setWindowRouter(this)
    }
    fun open(context:Context, action:String="open", id:Long=-1) {
        context.startActivity(Intent(context,WorkspaceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("action",action).putExtra("id",id))
    }
    override fun route(context:Context,id:Long,title:String?):Boolean {
        val s=mutable.value
        if(s.phase==Phase.LEAVING || (!s.desktop && s.phase!=Phase.ENTERING))return false
        open(context,id=id)
        return true
    }
    private fun publish(s:State) {
        mutable.value=s
        app.contentResolver.notifyChange(uri,null)
    }
    fun clearError() { publish(mutable.value.copy(error=false)) }
    fun begin(desktop:Boolean, windows:List<Awl.WlWindow>):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(mutable.value.phase!=Phase.IDLE)return false
        if(mutable.value.desktop==desktop)return true
        origin=mutable.value.desktop
        pending.clear();windows.forEach{pending[it.id]=it};moving=null
        prefs.edit().putBoolean("transition",true).apply()
        val token=++generation
        publish(State(origin,if(desktop)Phase.ENTERING else Phase.LEAVING))
        if(pending.isEmpty())commit(desktop)
        else {
            main.postDelayed({if(generation==token && mutable.value.phase!=Phase.IDLE)rollback()},30000)
            if(!desktop)nextIndependent()
        }
        return true
    }
    override fun onAttached(id:Long,embedded:Boolean) {
        val phase=mutable.value.phase
        if(phase==Phase.ENTERING && embedded) {
            pending.remove(id)
            if(pending.isEmpty())commit(true)
        } else if(phase==Phase.LEAVING && !embedded && moving==id) {
            pending.remove(id);moving=null
            main.post{nextIndependent()}
        }
    }
    override fun onAttachFailed(id:Long,embedded:Boolean) {
        if(id in pending)main.post{rollback()}
    }
    fun windowGone(id:Long) {
        pending.remove(id)
        if(moving==id)moving=null
        when(mutable.value.phase) {
            Phase.ENTERING->if(pending.isEmpty())commit(true)
            Phase.LEAVING->nextIndependent()
            else->Unit
        }
        app.contentResolver.notifyChange(uri,null)
    }
    private fun nextIndependent() {
        if(mutable.value.phase!=Phase.LEAVING || moving!=null)return
        val live=Awl.getWindows() ?: run{rollback();return}
        pending.keys.retainAll(live.map{it.id}.toSet())
        val next=pending.values.firstOrNull() ?: run{commit(false);return}
        moving=next.id
        // Each target acknowledges a real Surface attachment before the next
        // task starts. Merely accepting startActivity is not a successful move.
        Awl.attachWindow(app,next.id,next.title)
    }
    private fun commit(desktop:Boolean) {
        generation++
        prefs.edit().putBoolean("desktop",desktop).putBoolean("transition",false).apply()
        pending.clear();moving=null
        publish(State(desktop))
        if(desktop) {
            // All target surfaces accepted. Only now remove obsolete Recents
            // cards; finishing their Activities does not close Linux clients.
            Awl.getWindows()?.forEach{AwlWindowHost.retireIndependentTasks(app,it.id)}
        }
    }
    fun rollback() {
        if(mutable.value.phase==Phase.IDLE)return
        generation++;pending.clear();moving=null
        prefs.edit().putBoolean("transition",false).apply()
        publish(State(origin,error=true))
        if(origin)open(app)
        else {
            // Preserve every Linux window. Restoring a task is a presentation
            // rollback, never a process restart or a new DISPLAY/session.
            Awl.getWindows()?.forEach{Awl.attachWindow(app,it.id,it.title)}
        }
    }
}

class AnlandApplication:Application() {
    override fun onCreate(){super.onCreate();WorkspaceController.initialize(this)}
}
