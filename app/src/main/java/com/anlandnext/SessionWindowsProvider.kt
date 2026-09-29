package com.anlandnext

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.anlandnext.awl.Awl

/** Read-only launch preflight. Observe the daemon, never infer mode from a preference. */
class SessionWindowsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(callingPackage == "com.anland.shell" || callingPackage == "com.anlandnext")
        require(method == "windows")
        return readSessionWindows()
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
}

/** Shared by the warm provider path and the user-initiated cold-start handoff. */
internal fun readSessionWindows(): Bundle {
    val windows = Awl.getWindows() ?: error("Anland daemon unavailable")
    return Bundle().apply {
        putInt("independent", windows.count { Awl.applicationId(it.id) != "org.freedesktop.Xwayland" })
        putLongArray("window_ids", windows.map { it.id }.toLongArray())
        putBoolean("auto_attach", WlBinder.configGet("auto_attach") == 1)
    }
}
