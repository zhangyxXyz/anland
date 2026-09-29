package com.anland.shell.connections

import android.content.Context
import com.anland.shell.Prefs
import com.anland.shell.R
import com.anland.shell.ds.DsCli
import com.anland.shell.ds.RootExec
import com.anland.shell.ds.ShellUtils
import com.jcraft.jsch.ChannelExec
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Shared gate for app, desktop, shortcut and local-console launches. An enabled
 * binding never falls back to an unverified root-authorized launch on failure. */
object LaunchLogin {
    internal fun ensureRunning(context:Context,container:String) {
        check(container.isNotBlank()){context.getString(R.string.credential_user_mismatch)}
        if(DsCli.pid(container)<=0) {
            val result=DsCli.start(container)
            check(result.ok && DsCli.awaitRunning(container,90000)){context.getString(R.string.credential_container_failed)}
        }
    }

    @JvmStatic fun authenticateLaunch(context:Context,container:String,user:String) {
        val id=Prefs.launchCredential(context,container)
        if(id.isEmpty())return
        val profile=CredentialStore(context).get(id)
        check(profile.container==container && profile.username==user){context.getString(R.string.credential_user_mismatch)}
        authenticate(context,profile)
    }

    fun authenticate(context:Context,profile:ConnectionProfile) {
        when(profile.kind) {
            "local"->LocalLogin.authenticate(context,profile)
            "ssh"->authenticateSsh(context,profile)
            else->error(context.getString(R.string.credential_user_mismatch))
        }
    }

    private fun authenticateSsh(context:Context,profile:ConnectionProfile) {
        check(profile.valid() && profile.container.isNotBlank()){context.getString(R.string.credential_user_mismatch)}
        check(profile.hostKey.isNotBlank()){context.getString(R.string.ssh_launch_trust_required)}
        ensureRunning(context,profile.container)
        val helper=context.assets.open("ssh-launch-identity.py").bufferedReader().use{it.readText()}
        val command="python3 -c "+ShellUtils.shQuote(helper)
        // Bypass the daemon's fixed-size request buffer, as the PAM helper does.
        val bin=DsCli.ds()?:error(context.getString(R.string.credential_container_failed))
        fun runHelper(arguments:String,timeout:Long)=RootExec.exec(
            "DS_NO_PROXY=1 ${ShellUtils.shQuote(bin)} -n ${ShellUtils.shQuote(profile.container)} run "+
                ShellUtils.shQuote(command+arguments),timeout)
        // Know the cleanup target before starting the helper: a failed/timed-out
        // invocation may still have created its proof before losing stdout.
        val path="/run/anland-ssh-launch-${UUID.randomUUID()}/proof"
        try {
            val prepared=runHelper(" prepare "+ShellUtils.shQuote(profile.username)+" "+ShellUtils.shQuote(path),20000)
            if(!prepared.ok)android.util.Log.w("SshLaunch","Identity helper failed: exit=${prepared.exit}, reason=${prepared.error}")
            check(prepared.ok){context.getString(R.string.ssh_launch_identity_unavailable)}
            val identity=JSONObject(prepared.stdout.trim())
            check(identity.getString("path")==path){context.getString(R.string.ssh_launch_identity_unavailable)}
            val trusted=runCatching{Base64.getDecoder().decode(profile.hostKey)}.getOrNull()
            val keys=identity.getJSONArray("host_keys")
            check(trusted!=null && (0 until keys.length()).any {
                MessageDigest.isEqual(trusted,Base64.getDecoder().decode(keys.getString(it)))
            }){context.getString(R.string.ssh_launch_wrong_container)}
            val session=try{SshLogin.connect(profile)}catch(e:HostTrustRequired) {
                error(context.getString(R.string.ssh_launch_trust_required))
            }catch(e:Exception){error(context.getString(R.string.ssh_launch_auth_failed))}
            try {
                val channel=session.openChannel("exec") as ChannelExec
                // A successful SSH authentication alone is insufficient: cloned
                // images can share host keys, and forced commands can change uid.
                channel.setCommand("id -u && cat -- "+ShellUtils.shQuote(path))
                val output=BoundedOutput()
                channel.setOutputStream(output)
                channel.setErrStream(BoundedOutput())
                try {
                    channel.connect(15000)
                    val deadline=android.os.SystemClock.elapsedRealtime()+15000
                    while(!channel.isClosed && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(25)
                    val expected="${identity.getLong("uid")}\n${identity.getString("proof")}\n"
                    check(channel.isClosed && channel.exitStatus==0 && output.text()==expected) {
                        context.getString(R.string.ssh_launch_wrong_container)
                    }
                }finally{channel.disconnect()}
            }finally{session.disconnect()}
        }finally {
            // Remove the proof on success, failed authentication and exceptions.
            // Cleanup failure also blocks launch rather than leaving a live proof.
            val cleaned=runHelper(" cleanup "+ShellUtils.shQuote(path),15000)
            if(!cleaned.ok)android.util.Log.w("SshLaunch","Proof cleanup failed: exit=${cleaned.exit}, reason=${cleaned.error}")
            check(cleaned.ok){context.getString(R.string.ssh_launch_cleanup_failed)}
        }
    }

    private class BoundedOutput:OutputStream() {
        private val bytes=ByteArrayOutputStream()
        @Synchronized override fun write(value:Int) {
            check(bytes.size()<4096){"SSH identity response too large"};bytes.write(value)
        }
        @Synchronized override fun write(buffer:ByteArray,offset:Int,length:Int) {
            check(length<=4096-bytes.size()){"SSH identity response too large"};bytes.write(buffer,offset,length)
        }
        @Synchronized fun text()=bytes.toString("UTF-8")
    }
}
