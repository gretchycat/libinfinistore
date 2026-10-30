package com.infinifolder.sftp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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

enum class SftpAuthType {
    PASSWORD,
    KEY_CERT
}

data class SftpCredentials(
    val authType: SftpAuthType,
    val password: String? = null,
    val privateKey: String? = null,
    val certificate: String? = null,
    val passphrase: String? = null
)

class SftpCredentialStore(
    private val context: Context? = null,
    private val prefs: SharedPreferences = requireNotNull(context) { "Context or SharedPreferences required" }
        .getSharedPreferences("sftp_vault", Context.MODE_PRIVATE)
) {

    private val keyAlias = "infinifolder_sftp_master_key"

    fun save(connectionId: String, credentials: SftpCredentials) {
        val json = JSONObject().apply {
            put("authType", credentials.authType.name)
            put("password", credentials.password ?: "")
            put("privateKey", credentials.privateKey ?: "")
            put("certificate", credentials.certificate ?: "")
            put("passphrase", credentials.passphrase ?: "")
        }
        val encrypted = encryptString(json.toString())
        prefs.edit().putString("cred_$connectionId", encrypted).apply()
    }

    fun load(connectionId: String): SftpCredentials? {
        val encrypted = prefs.getString("cred_$connectionId", null) ?: return null
        return try {
            val decrypted = decryptString(encrypted) ?: return null
            val json = JSONObject(decrypted)
            val authTypeName = json.optString("authType", SftpAuthType.PASSWORD.name)
            val authType = try { SftpAuthType.valueOf(authTypeName) } catch (_: Exception) { SftpAuthType.PASSWORD }
            SftpCredentials(
                authType = authType,
                password = json.optString("password").takeIf { it.isNotEmpty() },
                privateKey = json.optString("privateKey").takeIf { it.isNotEmpty() },
                certificate = json.optString("certificate").takeIf { it.isNotEmpty() },
                passphrase = json.optString("passphrase").takeIf { it.isNotEmpty() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun remove(connectionId: String) {
        prefs.edit().remove("cred_$connectionId").apply()
    }

    // =========================================================================
    // AES-GCM Encryption with KeyStore / Fallback
    // =========================================================================

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
            // Fallback for headless JVM / Unit test environments without AndroidKeyStore provider
            getFallbackKey()
        }
    }

    private fun getFallbackKey(): SecretKey {
        val pkgName = try { context?.packageName } catch (_: Exception) { null } ?: "com.infinifolder"
        val raw = ("inf_sftp_salt_" + pkgName).toByteArray(StandardCharsets.UTF_8)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(raw)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun base64Encode(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            Base64.decode(str, Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    private fun encryptString(plainText: String): String {
        val secretKey = getSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
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
            val iv = combined.copyOfRange(0, 12)
            val cipherText = combined.copyOfRange(12, combined.size)
            val secretKey = getSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
