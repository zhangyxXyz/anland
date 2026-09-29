package com.anland.shell.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.anland.shell.connections.*
import com.anland.shell.ds.DsCli
import com.anland.shell.ds.ShellUtils
import java.io.File
import java.util.UUID

/** Opt-in device checks against an isolated loopback SSH/PAM fixture. Never
 * reads a user's credentials, changes their password or edits their profiles. */
object CredentialProbe {
    @JvmStatic fun run(context:Context,args:Bundle):String {
        val directory=File(context.cacheDir,"credential-probe-${UUID.randomUUID()}").apply{mkdirs()}
        val isolated=object:ContextWrapper(context){override fun getNoBackupFilesDir()=directory}
        val store=CredentialStore(isolated)
        val password="anland-temporary-auth-probe-2026"
        val container=args.getString("container")?:error("fixture container missing")
        val path=args.getString("fixture")?:error("fixture path missing")
        val user=args.getString("user")?:error("fixture user missing")
        val local=ConnectionProfile(name="Temporary local probe",container=container,username=user,password=password)
        try {
            store.put(local)
            check(CredentialStore(isolated).get(local.id).password==password){"Vault round trip failed"}
            val file=File(directory,"connections.v1")
            val saved=file.readBytes()
            check(!String(saved,Charsets.ISO_8859_1).contains(password)){"Plaintext credential found"}
            val tampered=saved.clone();tampered[tampered.lastIndex]=(tampered.last().toInt() xor 1).toByte()
            file.writeBytes(tampered)
            check(runCatching{store.list()}.isFailure){"Corrupt vault accepted"}
            check(file.readBytes().contentEquals(tampered)){"Corrupt vault overwritten"}
            file.writeBytes(saved)
            LocalLogin.authenticate(context,local)
            check(runCatching{LocalLogin.authenticate(context,local.copy(password="wrong-password"))}.isFailure){"Incorrect local password accepted"}
            val key=DsCli.runSh(container,"cat "+ShellUtils.shQuote("$path/client"))
            check(key.ok){"Cannot read temporary test key"}
            val profile=ConnectionProfile(name="Temporary SSH probe",kind="ssh",username=user,host="127.0.0.1",port=args.getInt("port",22389),password=password)
            val challenge=runCatching{SshLogin.connect(profile).disconnect()}.exceptionOrNull()
            check(challenge is HostTrustRequired && !challenge.changed){"Unknown host accepted"}
            val trusted=profile.copy(hostKey=challenge.presentedKey)
            SshLogin.console(trusted).use { connection ->
                connection.output.write("cd /tmp\nprintf 'ANLAND_AUTH_OK:'\npwd\nexit\n".toByteArray())
                connection.output.flush()
                val result=connection.input.bufferedReader().readText()
                check(result.contains("ANLAND_AUTH_OK:/tmp")){"SSH console did not preserve shell state"}
            }
            check(runCatching{SshLogin.connect(trusted.copy(password="wrong-password")).disconnect()}.isFailure){"Incorrect SSH password accepted"}
            val changed=runCatching{SshLogin.connect(trusted.copy(hostKey=java.util.Base64.getEncoder().encodeToString(ByteArray(32)))).disconnect()}.exceptionOrNull()
            check(changed is HostTrustRequired && changed.changed){"Changed host key accepted"}
            val privateKey=trusted.copy(auth="key",password="",privateKey=key.stdout,passphrase="anland-probe-passphrase")
            SshLogin.connect(privateKey).disconnect()
            check(runCatching{SshLogin.connect(privateKey.copy(passphrase="wrong")).disconnect()}.isFailure){"Wrong key passphrase accepted"}
            store.remove(local.id);check(store.list().isEmpty())
            return "PASS: encrypted vault, tamper rejection, local PAM correct/wrong password, SSH host trust/change, password/key login and persistent shell\n"
        } finally { directory.listFiles()?.forEach{it.delete()};directory.delete() }
    }
}
