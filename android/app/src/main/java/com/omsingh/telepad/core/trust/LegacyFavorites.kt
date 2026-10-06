package com.omsingh.telepad.core.trust

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val Context.legacyFavoritesStore by preferencesDataStore(name = "favorite_servers")

/**
 * Reads the list of recent servers that earlier versions kept, so [LegacyTrust] can
 * give migrated PCs their names. After the migration the file is deleted.
 */
object LegacyFavorites {

    @Serializable
    private data class Entry(val name: String = "", val host: String, val port: Int = 5000, val lastConnected: Long = 0L)

    @Serializable
    private data class Favorites(val v: Int = 1, val entries: List<Entry> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("favorites_json_v1")

    suspend fun read(context: Context): List<LegacyTrust.Favorite> {
        val raw = runCatching { context.legacyFavoritesStore.data.first()[key] }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString(Favorites.serializer(), raw).entries.map {
                LegacyTrust.Favorite(it.name, it.host, it.port, it.lastConnected)
            }
        }.getOrDefault(emptyList())
    }

    /** Removes the old file once it has been migrated. */
    fun delete(context: Context) {
        File(context.filesDir, "datastore/favorite_servers.preferences_pb").delete()
    }
}
