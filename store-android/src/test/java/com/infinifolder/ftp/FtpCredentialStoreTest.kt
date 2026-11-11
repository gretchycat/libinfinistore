package com.infinifolder.ftp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FtpCredentialStoreTest {

    private class MockSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = EditorImpl(map)
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class EditorImpl(private val map: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { if (key != null) removes.add(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removes.forEach { map.remove(it) }
                pending.forEach { (k, v) -> map[k] = v }
            }
        }
    }

    @Test
    fun testSaveAndLoadCredentials() {
        val mockPrefs = MockSharedPreferences()
        val store = FtpCredentialStore(prefs = mockPrefs)

        val creds = FtpCredentials(password = "superSecretFtpPass")
        store.save("ftp_conn_1", creds)

        val loaded = store.load("ftp_conn_1")
        assertNotNull(loaded)
        assertEquals("superSecretFtpPass", loaded?.password)

        store.remove("ftp_conn_1")
        assertNull(store.load("ftp_conn_1"))
    }
}
