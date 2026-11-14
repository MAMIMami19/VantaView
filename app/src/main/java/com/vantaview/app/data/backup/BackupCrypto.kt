// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.backup

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : Exception("That backup password isn't right")

/**
 * Locks the secret part of a backup (playlist passwords, debrid keys) with the user's backup password,
 * so the file works on any device but is useless without the password.
 * PBKDF2 turns the password into a key; AES-GCM encrypts and detects a wrong password.
 */
object BackupCrypto {
    private const val ITERATIONS = 120_000
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun derive(password: String, salt: ByteArray, alg: String, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun lock(plain: String, password: String): JSONObject {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        // SHA-256 where available (Android 8+), SHA-1 on older devices; the file records which.
        val alg = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); "PBKDF2WithHmacSHA256" }
            .getOrDefault("PBKDF2WithHmacSHA1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, alg, ITERATIONS), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("kdf", alg).put("iterations", ITERATIONS)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(data))
    }

    fun unlock(box: JSONObject, password: String): String {
        val alg = box.optString("kdf", "PBKDF2WithHmacSHA1")
        val key = try {
            derive(password, unb64(box.getString("salt")), alg, box.optInt("iterations", ITERATIONS))
        } catch (e: java.security.NoSuchAlgorithmException) {
            throw IllegalStateException("This device can't open a backup made on a newer Android version")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(box.getString("iv"))))
            String(cipher.doFinal(unb64(box.getString("data"))), Charsets.UTF_8)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPasswordException()
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPasswordException()
        }
    }
}
