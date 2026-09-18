package com.omsingh.telepad.core.wifi

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private val Context.favoriteServersStore: DataStore<Preferences>
    by preferencesDataStore(name = "favorite_servers")

/**
 * Persists previously-connected Wi-Fi servers and quickly probes them on
 * startup so the user can reconnect to "my desk PC" with one tap.
 *
 * Storage is a single JSON blob in [androidx.datastore.preferences.Preferences],
 * versioned by [SCHEMA_VERSION]. List is capped at 20 entries (most-recent
 * first) to keep the parse + write cycle O(1) for the user-visible case.
 *
 * **Why JSON in a single key rather than a typed Room database?** The total
 * data set is ~5 KB for typical users. A migration framework, schema files,
 * and DAOs would dwarf the value. If favourites ever grow rich metadata
 * (last latency, last fingerprint, ping success rate, notes, etc.), revisit.
 */
class FavoriteServersRepository(private val context: Context) {

    @Serializable
    private data class FavoriteEntry(
        val name: String,
        val host: String,
        val port: Int,
        val lastConnected: Long = 0L,
    )

    @Serializable
    private data class FavoritesV1(
        val v: Int = SCHEMA_VERSION,
        val entries: List<FavoriteEntry> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _onlineFavorites = MutableStateFlow<List<ServerInfo>>(emptyList())
    /** Subset of [allFavorites] confirmed reachable by a recent probe. */
    val onlineFavorites: StateFlow<List<ServerInfo>> = _onlineFavorites.asStateFlow()

    /** All saved favourites, regardless of online status. Emits on every edit. */
    val allFavorites: Flow<List<ServerInfo>> =
        context.favoriteServersStore.data.map { prefs ->
            val raw = prefs[KEY_FAVORITES] ?: return@map emptyList()
            try {
                json.decodeFromString<FavoritesV1>(raw).entries.map {
                    ServerInfo(name = it.name, host = it.host, port = it.port)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse favorites; treating as empty", e)
                emptyList()
            }
        }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Save (or update) a server in the favourites list. Idempotent. */
    suspend fun saveFavorite(server: ServerInfo) {
        context.favoriteServersStore.edit { prefs ->
            val current = readFavoriteEntries(prefs).toMutableList()
            current.removeAll { it.host.equals(server.host, ignoreCase = true) }
            current += FavoriteEntry(
                name = server.name,
                host = server.host,
                port = server.port,
                lastConnected = System.currentTimeMillis()
            )
            val capped = current.sortedByDescending { it.lastConnected }.take(MAX_ENTRIES)
            prefs[KEY_FAVORITES] = json.encodeToString(FavoritesV1(entries = capped))
        }
    }

    suspend fun removeFavorite(host: String) {
        context.favoriteServersStore.edit { prefs ->
            val remaining = readFavoriteEntries(prefs)
                .filterNot { it.host.equals(host, ignoreCase = true) }
            prefs[KEY_FAVORITES] = json.encodeToString(FavoritesV1(entries = remaining))
        }
    }

    suspend fun forgetAll() {
        context.favoriteServersStore.edit { prefs ->
            prefs[KEY_FAVORITES] = json.encodeToString(FavoritesV1(entries = emptyList()))
        }
    }

    /**
     * Probe every favourite in parallel. Update [onlineFavorites] with those
     * that respond within the timeout. Fire-and-forget — returns immediately.
     */
    fun probeAllFavorites() {
        ioScope.launch {
            val favorites = allFavorites.first()
            if (favorites.isEmpty()) {
                _onlineFavorites.value = emptyList()
                return@launch
            }
            val online = favorites.filter { probeHost(it.host, it.port) }
            _onlineFavorites.value = online
        }
    }

    private suspend fun probeHost(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = PROBE_TIMEOUT_MS
            val msg = byteArrayOf(
                0xC3.toByte(),
                0x54, 0xE7.toByte(), 0x9A.toByte(), 0x03,
                0x21, 0xC8.toByte(), 0xBE.toByte(), 0xFE.toByte()
            )
            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), port))
            val rxBuf = ByteArray(256)
            val rp = DatagramPacket(rxBuf, rxBuf.size)
            socket.receive(rp)
            rp.length > 0
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun readFavoriteEntries(prefs: Preferences): List<FavoriteEntry> {
        val raw = prefs[KEY_FAVORITES] ?: return emptyList()
        return try {
            json.decodeFromString<FavoritesV1>(raw).entries
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val TAG = "FavoritesRepo"
        const val SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 20
        const val PROBE_TIMEOUT_MS = 300
        val KEY_FAVORITES = stringPreferencesKey("favorites_json_v1")
    }
}
