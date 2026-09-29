package com.anland.shell.ui

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import com.anland.shell.Prefs
import com.anland.shell.connections.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Uses an existing disposable PAM fixture and isolated vault/preferences. */
object LaunchCredentialProbe {
    @JvmStatic fun run(context:Context,args:Bundle):String = runBlocking {
        val prefix="launch-probe-${UUID.randomUUID()}"
        val directory=File(context.cacheDir,prefix).apply{mkdirs()}
        val isolated=object:Application() {
            init{attachBaseContext(context)}
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("$prefix-$name",mode)
        }
        val container=requireNotNull(args.getString("container"))
        val user=requireNotNull(args.getString("user"))
        val first=ConnectionProfile(name="First test account",container=container,username=user,password="anland-temporary-auth-probe-2026")
        val second=first.copy(id=UUID.randomUUID().toString(),name="Second test account")
        val wrong=first.copy(id=UUID.randomUUID().toString(),name="Wrong password",password="wrong-password")
        val store=CredentialStore(isolated)
        listOf(first,second,wrong).forEach(store::put)
        val state=withContext(Dispatchers.Main){CredentialsState(isolated)}
        suspend fun awaitIdle()=withTimeout(30000) {
            while(withContext(Dispatchers.Main){state.busy})delay(50)
        }
        suspend fun toggle(profile:ConnectionProfile,enabled:Boolean) {
            android.util.Log.i("LaunchCredentialProbe","toggle ${profile.name}: $enabled")
            withContext(Dispatchers.Main){state.error=null;state.setLaunchVerification(profile,enabled)}
            awaitIdle()
        }
        try {
            android.util.Log.i("LaunchCredentialProbe","initial load")
            awaitIdle()
            toggle(first,true)
            check(Prefs.launchCredential(isolated,container)==first.id)
            check(Prefs.launchUser(isolated,container)==user)
            LaunchLogin.authenticateLaunch(isolated,container,user)
            toggle(wrong,true)
            check(withContext(Dispatchers.Main){state.error!=null}){"Wrong password enabled verification"}
            check(Prefs.launchCredential(isolated,container)==first.id){"Failed verification replaced binding"}
            toggle(second,true)
            check(withContext(Dispatchers.Main){state.defaults==setOf(second.id)}){"Multiple bindings for one container"}
            toggle(first,false)
            check(Prefs.launchCredential(isolated,container)==second.id){"Stale switch cleared another binding"}
            store.put(second.copy(password="wrong-password"))
            check(runCatching{LaunchLogin.authenticateLaunch(isolated,container,user)}.isFailure){"Launch bypassed invalid password"}
            toggle(second,false)
            check(Prefs.launchCredential(isolated,container).isEmpty())
            check(Prefs.launchUser(isolated,container)==user){"Disabling verification changed launch user"}
            LaunchLogin.authenticateLaunch(isolated,container,user)
            toggle(first,true)
            Prefs.setLaunchUser(isolated,container,"root")
            check(Prefs.launchCredential(isolated,container).isEmpty())
            "PASS: valid/invalid PAM enable, single binding, stale switch, launch rejection, disable preserves user, direct-user reset\n"
        } finally {
            withContext(Dispatchers.Main){state.viewModelScope.cancel()}
            context.deleteSharedPreferences("$prefix-shell")
            directory.listFiles()?.forEach{it.delete()};directory.delete()
        }
    }
}
