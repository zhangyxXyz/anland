package com.anland.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.anland.shell.ds.RootExec
import com.anland.design.LinuxThemePolicy
import java.io.File

/** One signed, serialized writer for the shared Linux session. Only Shell needs root access. */
class AppearanceProvider : ContentProvider() {
    override fun onCreate() = true

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "publish")
        val caller = callingPackage ?: context!!.packageName
        require(caller in setOf("com.anland.shell", "com.anlandnext"))
        // Bundle the same monitor as the module so existing installations can recover
        // through an APK update alone, without flashing a module or rebooting.
        val monitor = File(context!!.filesDir, "android-appearance.sh")
        val source = context!!.assets.open("appearance.sh").bufferedReader().use { it.readText() }
        if (!monitor.exists() || monitor.readText() != source) monitor.writeText(source)
        val command = LinuxThemePolicy.command(caller, arg ?: "System", extras?.getBoolean("claim") == true,
            monitor.absolutePath)
        val result = RootExec.exec(command, 5000)
        if (!result.ok) android.util.Log.w("AnlandAppearance",
            "Root bridge exit=${result.exit}: ${result.error ?: result.stderr.take(500)}")
        return Bundle().apply { putBoolean("ok", result.ok) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
