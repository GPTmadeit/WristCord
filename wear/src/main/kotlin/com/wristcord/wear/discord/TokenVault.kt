package com.wristcord.wear.discord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Discord token, encrypted with an AES key that lives in the watch's hardware-backed
 * Android Keystore. The key never leaves the keystore, so a copy of the app's files is not a
 * copy of the account. Backups are off for the whole app (allowBackup=false).
 */
class TokenVault(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): String? {
        val blob = prefs.getString(K_TOKEN, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.onFailure {
            // A key invalidated by a device reset leaves an unreadable blob: forget it.
            Log.w(TAG, "token unreadable, clearing", it)
            clear()
        }.getOrNull()
    }

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val out = cipher.iv + ct
        prefs.edit().putString(K_TOKEN, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        prefs.edit().remove(K_TOKEN).apply()
    }

    val hasToken: Boolean get() = prefs.contains(K_TOKEN)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val TAG = "WristCord/Vault"
        const val PREFS = "wristcord_account"
        const val K_TOKEN = "token"
        const val ALIAS = "wristcord.discord.token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
