package com.anland.shell.connections

import com.jcraft.jsch.*
import java.security.MessageDigest
import java.util.Base64
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

class HostTrustRequired(val profile:ConnectionProfile,val presentedKey:String):Exception("SSH host verification required") {
    val changed get()=profile.hostKey.isNotEmpty()
    val fingerprint get()=fingerprint(presentedKey)
    companion object {
        fun fingerprint(key:String):String="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)))
    }
}

/** Strict verification happens during key exchange, BEFORE credentials are sent.
 * Unknown/changed keys return a structured challenge. Only the UI may approve
 * and persist a key; reconnects and background app launches never auto-trust. */
object SshLogin {
    fun connect(profile:ConnectionProfile):Session {
        require(profile.kind=="ssh" && profile.valid())
        val jsch=JSch()
        var observed:String?=null
        jsch.setHostKeyRepository(object:HostKeyRepository {
            override fun check(host:String,key:ByteArray):Int {
                observed=Base64.getEncoder().encodeToString(key)
                return if(profile.hostKey.isEmpty())HostKeyRepository.NOT_INCLUDED
                    else if(MessageDigest.isEqual(Base64.getDecoder().decode(profile.hostKey),key))HostKeyRepository.OK
                    else HostKeyRepository.CHANGED
            }
            override fun add(hostkey:HostKey,ui:UserInfo?)=Unit
            override fun remove(host:String,type:String?)=Unit
            override fun remove(host:String,type:String?,key:ByteArray?)=Unit
            override fun getKnownHostsRepositoryID()="Anland connection ${profile.id}"
            override fun getHostKey():Array<HostKey> = emptyArray()
            override fun getHostKey(host:String?,type:String?):Array<HostKey> = emptyArray()
        })
        if(profile.auth=="key") {
            val key=profile.privateKey.toByteArray(Charsets.UTF_8)
            val phrase=profile.passphrase.toByteArray(Charsets.UTF_8)
            try{jsch.addIdentity(profile.id,key,null,phrase)}finally{key.fill(0);phrase.fill(0)}
        }
        val session=jsch.getSession(profile.username,profile.host,profile.port)
        session.setConfig("StrictHostKeyChecking","yes")
        session.setConfig("PreferredAuthentications",if(profile.auth=="key")"publickey" else "password")
        session.setConfig("MaxAuthTries","1")
        if(profile.auth=="password") {
            val password=profile.password.toByteArray(Charsets.UTF_8)
            try{session.setPassword(password)}finally{password.fill(0)}
        }
        session.setServerAliveInterval(15000)
        session.setServerAliveCountMax(3)
        try{session.connect(15000);return session}
        catch(e:Exception) {
            session.disconnect();jsch.removeAllIdentity()
            val key=observed
            if(key!=null && key!=profile.hostKey)throw HostTrustRequired(profile,key)
            throw e
        }
    }

    class Console(val session:Session,val channel:ChannelExec,val input:InputStream,val output:OutputStream):Closeable {
        override fun close(){channel.disconnect();session.disconnect()}
    }
    fun console(profile:ConnectionProfile):Console {
        val session=connect(profile)
        try {
            // Match the existing line-oriented console: a persistent shell, no
            // terminal escape handling or accidental local container preamble.
            val channel=session.openChannel("exec") as ChannelExec
            channel.setCommand("exec sh 2>&1")
            val input=channel.inputStream
            val output=channel.outputStream
            channel.connect(15000)
            return Console(session,channel,input,output)
        } catch(e:Exception){session.disconnect();throw e}
    }
}
