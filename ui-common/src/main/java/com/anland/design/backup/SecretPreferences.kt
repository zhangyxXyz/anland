package com.anland.design.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** This store is deliberately excluded from portable backups. */
internal class SecretPreferences(context: Context, private val alias: String) {
    private val prefs = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
    @Synchronized private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create) { "Backup credential key is unavailable; reconfigure WebDAV" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(name: String): String {
        val raw = prefs.getString(name,null) ?: return ""
        val bytes = Base64.decode(raw,Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            String(doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        }
    }
    fun write(name: String, value: String) {
        if(value.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(true)) }
        prefs.edit().putString(name,Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)).apply()
    }
}
