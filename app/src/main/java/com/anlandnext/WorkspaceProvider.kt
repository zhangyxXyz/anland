package com.anlandnext

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.anlandnext.awl.Awl

/** Read-only presentation status for Shell. Mode changes always use a visible
 * host Activity with the same confirmation/transaction flow as local entry. */
class WorkspaceProvider:ContentProvider() {
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        if(callingPackage!=context!!.packageName && callingPackage!="com.anland.shell")
            throw SecurityException("Workspace status is shared with Anland Shell only")
        require(method=="status")
        val s=WorkspaceController.state.value
        val windows=Awl.getWindows()
        return Bundle().apply{
            putBoolean("desktop",s.desktop);putBoolean("busy",s.phase!=WorkspaceController.Phase.IDLE)
            putBoolean("available",windows!=null);putInt("count",windows?.size?:0)
        }
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor?=null
    override fun getType(uri:Uri)="vnd.android.cursor.item/vnd.anland.workspace"
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException()
}
