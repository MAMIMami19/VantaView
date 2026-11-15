// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts provider passwords with a key that never leaves the Android Keystore. */
@Singleton
class CredentialCrypto @Inject constructor() {
    private val alias = "vantaview_credentials"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /** A playlist link kept encrypted, as "enc:<cipher text>" (an M3U link often holds a username and password). */
    fun protect(link: String): String = if (link.isEmpty() || link.startsWith(ENC)) link else ENC + encrypt(link)

    /** The real link from [protect]; anything else is returned as it is. */
    fun reveal(stored: String): String = if (stored.startsWith(ENC)) decrypt(stored.removePrefix(ENC)) else stored

    companion object { const val ENC = "enc:" }
}
