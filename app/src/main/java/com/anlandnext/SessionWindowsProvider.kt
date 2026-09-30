package com.anlandnext

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.anlandnext.awl.Awl

/** Launch preflight. Observe the daemon and migrate automatic window display if needed. */
class SessionWindowsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(callingPackage == "com.anland.shell" || callingPackage == "com.anlandnext")
        require(method == "windows")
        return readSessionWindows(arg,requireNotNull(context))
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
}

/** Shared by the warm provider path and the user-initiated cold-start handoff. */
internal fun readSessionWindows(container:String?,context:android.content.Context): Bundle {
    if(!container.isNullOrBlank() && !Awl.supportsContainerSelection())
        error(context.getString(R.string.container_module_update_needed))
    val windows = (Awl.getWindows() ?: error("Anland daemon unavailable")).filter { container.isNullOrBlank() || Awl.containerName(it.id)==container }
    return Bundle().apply {
        putString("container",container)
        putInt("independent", windows.count { Awl.applicationId(it.id) != "org.freedesktop.Xwayland" })
        putLongArray("window_ids", windows.map { it.id }.toLongArray())
        putBoolean("auto_attach", WlBinder.ensureAutoAttach())
    }
}
