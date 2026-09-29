package com.anland.shell.ui

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProtection
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

object KeystoreProbe {
    @JvmStatic fun run():String {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val prefix="anland.probe.${UUID.randomUUID()}"
        val report=StringBuilder()
        fun probe(name:String,body:(String)->Unit) {
            val alias="$prefix.$name"
            try{body(alias);report.append("$name: PASS\n")}
            catch(e:Exception){report.append("$name: ${e.javaClass.simpleName}: ${e.cause?.message?:e.message}\n")}
            finally{runCatching{store.deleteEntry(alias)}}
        }
        for(bits in listOf(128,256))probe("generate-aes-gcm-$bits") { alias ->
            KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(bits).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
            }.generateKey()
        }
        probe("import-aes-gcm-256") { alias ->
            val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
            store.setEntry(alias,KeyStore.SecretKeyEntry(key),KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
            val encrypt=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,store.getKey(alias,null))}
            val ciphertext=encrypt.doFinal("test".toByteArray())
            val decrypt=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,store.getKey(alias,null),javax.crypto.spec.GCMParameterSpec(128,encrypt.iv))}
            check(String(decrypt.doFinal(ciphertext))=="test")
        }
        probe("generate-rsa-oaep") { alias ->
            KeyPairGenerator.getInstance("RSA","AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests("SHA-256","SHA-1").setEncryptionPaddings("OAEPPadding").build())
            }.generateKeyPair()
        }
        return report.toString()
    }
}
