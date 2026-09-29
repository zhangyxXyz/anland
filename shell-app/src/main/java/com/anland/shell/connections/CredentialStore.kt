package com.anland.shell.connections

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One authenticated, atomic vault in noBackupFilesDir. Key material never leaves
 * Android Keystore. Missing/invalid keys and corrupt ciphertext fail closed: do
 * not replace an unreadable vault with an empty list or silently drop credentials. */
class CredentialStore(context:Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"connections.v1"))
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        check(create){"Credential key unavailable"}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun list():List<ConnectionProfile> = synchronized(LOCK) {
        if(!file.baseFile.exists())return@synchronized emptyList()
        val bytes=file.readFully()
        require(bytes.size>=29 && bytes[0]==1.toByte()){"Invalid credential vault"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(AAD)
        val clear=cipher.doFinal(bytes.copyOfRange(13,bytes.size))
        try {val array=JSONArray(String(clear,Charsets.UTF_8));List(array.length()){ConnectionProfile.fromJson(array.getJSONObject(it))}}
        finally{clear.fill(0)}
    }
    fun get(id:String)=list().firstOrNull{it.id==id}?:error("Saved connection unavailable")
    fun put(profile:ConnectionProfile)=synchronized(LOCK) {
        require(profile.valid()){"Incomplete connection"}
        write(list().filterNot{it.id==profile.id}+profile)
    }
    fun remove(id:String)=synchronized(LOCK){write(list().filterNot{it.id==id})}
    fun replaceAll(profiles:List<ConnectionProfile>)=synchronized(LOCK) {
        require(profiles.all{it.valid()} && profiles.map{it.id}.distinct().size==profiles.size){"Invalid connection backup"}
        // Do not overwrite an unreadable existing vault.
        list()
        write(profiles)
    }
    private fun write(profiles:List<ConnectionProfile>) {
        val array=JSONArray();profiles.forEach{array.put(it.toJson())}
        val clear=array.toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(AAD)
        val bytes=try{byteArrayOf(1)+cipher.iv+cipher.doFinal(clear)}finally{clear.fill(0)}
        val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
    }
    companion object {
        private val LOCK=Any()
        private const val ALIAS="anland.connections.v1"
        private val AAD="com.anland.shell/connections/v1".toByteArray(Charsets.UTF_8)
    }
}
