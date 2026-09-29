package com.anland.shell.ui

import android.content.Context
import android.content.ContextWrapper
import com.anland.design.backup.*
import java.io.File
import java.util.UUID

/** Uses disposable preference namespaces; never replaces the user's settings or vault. */
object MaintenanceProbe {
    @JvmStatic fun run(targetContext: Context): String {
        val prefix="maintenance-test.${UUID.randomUUID()}"
        val names=mutableSetOf<String>()
        val isolated=object: ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$prefix.$name".also { names.add(it) },mode)
        }
        val directory=File(targetContext.cacheDir,prefix).apply { mkdirs() }
        try {
            val prefs=isolated.getSharedPreferences("shell",Context.MODE_PRIVATE)
            prefs.edit().putInt("console_font_sp",14).putString("history","original history").commit()
            val portable=AppPreferencesBackup(isolated,setOf("shell"))
            val exported=portable.export()
            check(!String(exported.openStream().readBytes()).contains("history"))
            val archive=BackupArchive.pack(listOf(exported),File(directory,"settings.zip"),"disposable password")
            val restored=BackupArchive.unpack(archive,File(directory,"restored"),"disposable password")
            prefs.edit().putInt("console_font_sp",22).putString("history","newer history").commit()
            portable.restore(portable.decode(restored))
            check(prefs.getInt("console_font_sp",0)==14)
            check(prefs.getString("history",null)=="newer history")
            val otherApp=object: ContextWrapper(isolated) { override fun getPackageName()="com.anlandnext" }
            check(runCatching { AppPreferencesBackup(otherApp,setOf("shell")).decode(restored) }.isFailure)

            val manager=BackupManager(isolated)
            val settings=manager.settings().copy(encryptionPassword="archive test secret",
                webDav=WebDavConfig("https://example.invalid/dav","test user","dav test secret"))
            manager.saveSettings(settings)
            check(manager.settings().encryptionPassword==settings.encryptionPassword)
            check(manager.settings().webDav.password==settings.webDav.password)
            val stored=names.flatMap { targetContext.getSharedPreferences(it,Context.MODE_PRIVATE).all.values }.joinToString()
            check(!stored.contains("archive test secret") && !stored.contains("dav test secret"))
            return "PASS: encrypted backup roundtrip, history preservation, cross-app rejection, Keystore-backed secrets\n"
        } finally {
            names.forEach { targetContext.deleteSharedPreferences(it) }
            directory.deleteRecursively()
        }
    }
}
