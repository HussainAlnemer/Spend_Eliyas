package com.alnemer.spend.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Database passphrase management.
 * A random 32-byte passphrase is generated once, encrypted with an AES key that
 * lives inside the Android hardware Keystore (non-exportable), and stored in prefs.
 * The plaintext passphrase never touches disk.
 */
object CryptoPrefs {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "spend_db_key"
    private const val PREFS = "spend_secure"
    private const val K_WRAPPED = "db_pass_wrapped"
    private const val K_IV = "db_pass_iv"

    fun dbPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(K_WRAPPED, null)
        val ivB64 = prefs.getString(K_IV, null)
        if (wrapped != null && ivB64 != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit()
            .putString(K_WRAPPED, Base64.encodeToString(cipher.doFinal(pass), Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return pass
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }
}
