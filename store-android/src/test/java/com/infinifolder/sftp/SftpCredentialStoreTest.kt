package com.infinifolder.sftp

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class SftpCredentialStoreTest {

    private fun createMockPrefs(): SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        val editorHandler = object : InvocationHandler {
            val pending = mutableMapOf<String, Any?>()
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
                when (method.name) {
                    "putString" -> {
                        pending[args!![0] as String] = args[1]
                        return proxy
                    }
                    "remove" -> {
                        pending[args!![0] as String] = null
                        return proxy
                    }
                    "apply", "commit" -> {
                        for ((k, v) in pending) {
                            if (v == null) map.remove(k) else map[k] = v
                        }
                        pending.clear()
                        return true
                    }
                }
                return null
            }
        }

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            editorHandler
        ) as SharedPreferences.Editor

        val prefsHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getString" -> {
                    val key = args!![0] as String
                    val def = args[1] as String?
                    map[key] as? String ?: def
                }
                "edit" -> editorProxy
                "contains" -> map.containsKey(args!![0] as String)
                else -> null
            }
        }

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            prefsHandler
        ) as SharedPreferences
    }

    @Test
    fun testSaveAndLoadPasswordCredentials() {
        val prefs = createMockPrefs()
        val store = SftpCredentialStore(prefs = prefs)

        val creds = SftpCredentials(
            authType = SftpAuthType.PASSWORD,
            password = "SecretPassword123!"
        )

        store.save("conn-1", creds)

        // Raw pref should be encrypted (not plaintext)
        val encrypted = prefs.getString("cred_conn-1", null)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.isNotEmpty())
        assertTrue(!encrypted.contains("SecretPassword123!"))

        // Decrypted load
        val loaded = store.load("conn-1")
        assertNotNull(loaded)
        assertEquals(SftpAuthType.PASSWORD, loaded?.authType)
        assertEquals("SecretPassword123!", loaded?.password)
        assertNull(loaded?.privateKey)
        assertNull(loaded?.certificate)
        assertNull(loaded?.passphrase)
    }

    @Test
    fun testSaveAndLoadKeyAndCertCredentials() {
        val prefs = createMockPrefs()
        val store = SftpCredentialStore(prefs = prefs)

        val testKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA...\n-----END OPENSSH PRIVATE KEY-----"
        val testCert = "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAx..."
        val testPassphrase = "KeyPassphrase456"

        val creds = SftpCredentials(
            authType = SftpAuthType.KEY_CERT,
            privateKey = testKey,
            certificate = testCert,
            passphrase = testPassphrase
        )

        store.save("conn-2", creds)

        val loaded = store.load("conn-2")
        assertNotNull(loaded)
        assertEquals(SftpAuthType.KEY_CERT, loaded?.authType)
        assertEquals(testKey, loaded?.privateKey)
        assertEquals(testCert, loaded?.certificate)
        assertEquals(testPassphrase, loaded?.passphrase)
        assertNull(loaded?.password)
    }

    @Test
    fun testRemoveCredentials() {
        val prefs = createMockPrefs()
        val store = SftpCredentialStore(prefs = prefs)

        val creds = SftpCredentials(authType = SftpAuthType.PASSWORD, password = "pwd")
        store.save("conn-3", creds)
        assertNotNull(store.load("conn-3"))

        store.remove("conn-3")
        assertNull(store.load("conn-3"))
    }
}
