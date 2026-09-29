package com.anland.shell.ui

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import com.anland.shell.Prefs
import com.anland.shell.R
import com.anland.shell.connections.*
import com.anland.shell.ds.DsCli
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Explicit device regression using the requested saved SSH connection. Secrets
 * never leave the target app. All mutations use an isolated vault/preferences;
 * neither the real credential nor the user's launch settings are changed. */
object SshLaunchCredentialProbe {
    @JvmStatic fun run(context:Context,args:Bundle):String = runBlocking {
        val container=requireNotNull(args.getString("container"))
        val name=requireNotNull(args.getString("profile_name"))
        val original=CredentialStore(context).list().single{it.kind=="ssh" && it.name==name}
        val prefix="ssh-launch-probe-${UUID.randomUUID()}"
        val directory=File(context.cacheDir,prefix).apply{mkdirs()}
        val isolated=object:Application() {
            init{attachBaseContext(context)}
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("$prefix-$name",mode)
        }
        val beforeUser=Prefs.launchUser(context,container)
        val beforeBinding=Prefs.launchCredential(context,container)
        val first=original.copy(id=UUID.randomUUID().toString(),name="SSH verification test",container=container)
        val second=first.copy(id=UUID.randomUUID().toString())
        val store=CredentialStore(isolated)
        listOf(first,second).forEach(store::put)
        val state=withContext(Dispatchers.Main){CredentialsState(isolated)}
        suspend fun awaitIdle()=withTimeout(120000) {
            while(withContext(Dispatchers.Main){state.busy})delay(50)
        }
        suspend fun toggle(profile:ConnectionProfile,enabled:Boolean) {
            withContext(Dispatchers.Main){state.error=null;state.setLaunchVerification(profile,enabled)}
            awaitIdle()
        }
        fun blocked(profile:ConnectionProfile,reason:Int=R.string.ssh_launch_auth_failed) {
            store.put(profile)
            val failure=runCatching{LaunchLogin.authenticateLaunch(isolated,container,first.username)}.exceptionOrNull()
            android.util.Log.i("SshLaunchProbe","Blocked check port=${profile.port} container=${profile.container}: ${failure?.message}")
            check(failure!=null){"Invalid SSH launch was accepted"}
            check(failure.message==context.getString(reason)){"Unexpected rejection: ${failure.message}"}
            check(Prefs.launchCredential(isolated,container)==first.id){"Failed authentication removed the gate"}
        }
        try {
            awaitIdle()
            toggle(first,true)
            check(withContext(Dispatchers.Main){state.error==null}){"Could not enable valid SSH verification"}
            check(Prefs.launchCredential(isolated,container)==first.id)
            check(Prefs.launchUser(isolated,container)==first.username)
            LaunchLogin.authenticateLaunch(isolated,container,first.username)
            check(runCatching{LaunchLogin.authenticateLaunch(isolated,container,"wrong-launch-user")}.isFailure)
            blocked(first.copy(hostKey=""),R.string.ssh_launch_trust_required)
            blocked(first.copy(hostKey=java.util.Base64.getEncoder().encodeToString(ByteArray(32))),R.string.ssh_launch_wrong_container)
            blocked(first.copy(port=9))
            val pair=KeyPair.genKeyPair(JSch(),KeyPair.RSA,2048)
            val buffer=ByteArrayOutputStream()
            val badKey=try{pair.writePrivateKey(buffer);buffer.toString("UTF-8")}finally{pair.dispose()}
            blocked(first.copy(auth="key",privateKey=badKey,passphrase="",password=""))
            blocked(first.copy(username="missing-anland-launch-user"),R.string.credential_user_mismatch)
            blocked(first.copy(container="different-container"),R.string.credential_user_mismatch)
            val impostorPort=args.getString("impostor_port")?.toInt()
            if(impostorPort!=null)blocked(first.copy(port=impostorPort),R.string.ssh_launch_wrong_container)
            store.remove(first.id)
            check(runCatching{LaunchLogin.authenticateLaunch(isolated,container,first.username)}.isFailure) {
                "Missing credential bypassed the gate"
            }
            store.put(first)
            toggle(second,true)
            check(withContext(Dispatchers.Main){state.defaults==setOf(second.id)})
            toggle(first,false)
            check(Prefs.launchCredential(isolated,container)==second.id){"Stale switch cleared another profile"}
            // Editing a bound user must keep the gate and reject mismatched launches.
            withContext(Dispatchers.Main){state.editor=second.copy(username="wrong-launch-user");state.save(false)}
            awaitIdle()
            check(Prefs.launchCredential(isolated,container)==second.id)
            check(runCatching{LaunchLogin.authenticateLaunch(isolated,container,first.username)}.isFailure)
            store.put(second)
            toggle(second,false)
            check(Prefs.launchCredential(isolated,container).isEmpty())
            check(Prefs.launchUser(isolated,container)==first.username)
            toggle(first,true)
            withContext(Dispatchers.Main){state.delete(first)}
            awaitIdle()
            check(Prefs.launchCredential(isolated,container).isEmpty())
            check(Prefs.launchUser(context,container)==beforeUser && Prefs.launchCredential(context,container)==beforeBinding)
            check(CredentialStore(context).get(original.id)==original){"Real credential changed"}
            val leftover=DsCli.runSh(container,"find /run -maxdepth 1 -type d -name 'anland-ssh-launch-*' -print")
            check(leftover.ok && leftover.stdout.isBlank()){"Verification files were not cleaned"}
            "PASS: SSH enable/login, user and host rejection, unreachable host, unauthorized key, missing credential, " +
                "container proof, binding replacement, stale switch, edit protection, disable/delete, " +
                "temporary-file cleanup and unchanged real credentials/preferences\n"
        } finally {
            withContext(Dispatchers.Main){state.viewModelScope.cancel()}
            context.deleteSharedPreferences("$prefix-shell")
            directory.listFiles()?.forEach{it.delete()};directory.delete()
        }
    }
}
