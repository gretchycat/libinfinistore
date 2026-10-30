package com.infinifolder.ftp

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FtpCredentialStore(
    private val context: Context? = null,
    private val prefs: SharedPreferences = requireNotNull(context) { "Context or SharedPreferences required" }
        .getSharedPreferences("ftp_vault", Context.MODE_PRIVATE)
) {

    private val keyAlias = "infinifolder_ftp_master_key"

    fun save(connectionId: String, credentials: FtpCredentials) {
        val json = JSONObject().apply {
            put("password", credentials.password ?: "")
        }
        val encrypted = encryptString(json.toString())
        prefs.edit().putString("cred_$connectionId", encrypted).apply()
    }

    fun load(connectionId: String): FtpCredentials? {
        val encrypted = prefs.getString("cred_$connectionId", null) ?: return null
        return try {
            val decrypted = decryptString(encrypted) ?: return null
            val json = JSONObject(decrypted)
            FtpCredentials(
                password = json.optString("password").takeIf { it.isNotEmpty() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun remove(connectionId: String) {
        prefs.edit().remove("cred_$connectionId").apply()
    }

    private fun getSecretKey(): SecretKey {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(keyAlias)) {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                kg.init(spec)
                kg.generateKey()
            } else {
                (ks.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
            }
        } catch (_: Throwable) {
            getFallbackKey()
        }
    }

    private fun getFallbackKey(): SecretKey {
        var keyHex = prefs.getString("fallback_master_key", null)
        if (keyHex == null) {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            keyHex = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("fallback_master_key", keyHex).apply()
        }
        val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun base64Encode(bytes: ByteArray): String {
        return try {
            val res = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (res != null) res else java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            val res = Base64.decode(str, Base64.NO_WRAP)
            if (res != null) res else java.util.Base64.getDecoder().decode(str)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    private fun encryptString(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getSecretKey()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return base64Encode(combined)
    }

    private fun decryptString(encryptedBase64: String): String? {
        return try {
            val combined = base64Decode(encryptedBase64)
            if (combined.size < 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = getSecretKey()
            val spec = GCMParameterSpec(128, combined, 0, 12)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plainTextBytes = cipher.doFinal(combined, 12, combined.size - 12)
            String(plainTextBytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }
}
