package com.infinifolder.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Application-owned connection definitions; credential material remains in backend vaults. */
class ConnectionPreferences(context: Context) : ProviderConnectionRepository {
    private val preferences = context.applicationContext.getSharedPreferences("browser", Context.MODE_PRIVATE)
    override fun list(): List<ProviderConnection> = runCatching {
        val json = JSONArray(preferences.getString("saved_connections", "[]"))
        (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = item.optString("type").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = item.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val options = item.optJSONObject("options")
            ProviderConnection(id, type, name, item.optString("endpoint").takeIf(String::isNotBlank),
                options?.keys()?.asSequence()?.associateWith { options.optString(it) }.orEmpty(),
                item.optString("credentialHandle").takeIf(String::isNotBlank))
        }
    }.getOrDefault(emptyList())
    @Synchronized override fun save(connection: ProviderConnection) = write(list().filterNot { it.id == connection.id } + connection)
    @Synchronized override fun remove(connectionId: String) = write(list().filterNot { it.id == connectionId })
    private fun write(connections: List<ProviderConnection>) {
        val json = JSONArray()
        connections.forEach { item ->
            json.put(JSONObject().put("id", item.id).put("type", item.sourceTypeId).put("name", item.displayName)
                .put("endpoint", item.endpoint.orEmpty()).put("credentialHandle", item.credentialHandle.orEmpty())
                .put("options", JSONObject(item.options)))
        }
        preferences.edit().putString("saved_connections", json.toString()).apply()
    }
}
