package com.skillions.deskpilot.data

import android.content.Context
import android.os.Build
import com.skillions.deskpilot.net.BluetoothTransport
import com.skillions.deskpilot.net.Connection
import com.skillions.deskpilot.net.Hello
import com.skillions.deskpilot.net.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class RemoteSummary(
    val id: String, val name: String, val category: String, val icon: String,
    val tier: String, val supported: Boolean, val description: String,
)

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val server: SavedServer) : ConnState
    data class Connected(val server: SavedServer, val hello: Hello, val remotes: List<RemoteSummary>) : ConnState
    data class Failed(val server: SavedServer, val message: String) : ConnState
}

/** Owns the single live connection. Used by UI, widget, tile, floating remote, NFC, voice and Wear relay. */
class RemoteManager(private val context: Context, private val settings: SettingsStore, private val servers: JsonListStore<SavedServer>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var conn: Connection? = null
    private var wantConnected: SavedServer? = null
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    val connected: ConnState.Connected? get() = _state.value as? ConnState.Connected

    suspend fun connect(server: SavedServer): Result<Unit> = lock.withLock {
        conn?.close(); conn = null
        wantConnected = server
        _state.value = ConnState.Connecting(server)
        runCatching {
            withContext(Dispatchers.IO) {
                val transport = if (server.transport == "bluetooth")
                    BluetoothTransport(context, server.btAddress, server.btChannel)
                else TcpTransport(server.host, server.port)
                val c = Connection(transport) { err -> onClosed(server, err) }
                val welcome = c.open(server.password, "${Build.MANUFACTURER} ${Build.MODEL}")
                conn = c
                val list = welcome["remotes"]?.jsonArray?.map { parseSummary(it.jsonObject) } ?: emptyList()
                // remember MAC/OS for Wake On LAN and voice commands
                val updated = server.copy(mac = c.hello.mac.ifBlank { server.mac }, os = c.hello.os)
                servers.update { all -> all.map { if (it.id == server.id) updated else it } }
                settings.update { it.copy(lastServerId = server.id) }
                _state.value = ConnState.Connected(updated, c.hello, list)
            }
        }.onFailure { _state.value = ConnState.Failed(server, it.message ?: it.javaClass.simpleName) }
    }

    private fun onClosed(server: SavedServer, err: Throwable?) {
        val cur = _state.value
        if (cur is ConnState.Connected && cur.server.id == server.id && wantConnected?.id == server.id) {
            _state.value = ConnState.Failed(server, "Connection lost")
            scope.launch {            // simple auto-reconnect with back-off
                for (wait in longArrayOf(1000, 2000, 4000, 8000)) {
                    delay(wait)
                    if (wantConnected?.id != server.id) return@launch
                    if (connect(server).isSuccess) return@launch
                }
            }
        }
    }

    fun disconnect() {
        wantConnected = null
        conn?.close(); conn = null
        _state.value = ConnState.Idle
    }

    /** For widgets / tiles / NFC / Wear: connect to the last used server if needed. */
    suspend fun ensureConnected(): Boolean {
        if (conn?.closed == false && connected != null) return true
        val id = settings.current().lastServerId
        val server = servers.items.value.firstOrNull { it.id == id } ?: servers.items.value.firstOrNull() ?: return false
        return connect(server).isSuccess
    }

    private fun live() = conn?.takeIf { !it.closed } ?: throw IllegalStateException("Not connected")

    suspend fun getRemote(id: String): JsonObject =
        live().request("get_remote", buildJsonObject { put("remote", id) }).jsonObject

    suspend fun action(remote: String, action: String, args: JsonObject = JsonObject(emptyMap())): JsonElement =
        live().request("action", buildJsonObject {
            put("remote", remote); put("action", action); put("args", args)
        })

    suspend fun exec(steps: JsonArray, args: JsonObject = JsonObject(emptyMap())): JsonElement =
        live().request("exec", buildJsonObject { put("steps", steps); put("args", args) })

    suspend fun reloadRemotes() {
        val list = live().request("reload").jsonArray.map { parseSummary(it.jsonObject) }
        connected?.let { _state.value = it.copy(remotes = list) }
    }

    // ---- fast paths (fire-and-forget)
    fun mouseMove(dx: Int, dy: Int) = conn?.fire("mouse", buildJsonObject { put("op", "move"); put("dx", dx); put("dy", dy) })
    fun mouseScroll(dx: Int, dy: Int) = conn?.fire("mouse", buildJsonObject { put("op", "scroll"); put("dx", dx); put("dy", dy) })
    fun mouseClick(button: String = "left", count: Int = 1) =
        conn?.fire("mouse", buildJsonObject { put("op", "click"); put("button", button); put("count", count) })
    fun mouseDown(button: String = "left") = conn?.fire("mouse", buildJsonObject { put("op", "down"); put("button", button) })
    fun mouseUp(button: String = "left") = conn?.fire("mouse", buildJsonObject { put("op", "up"); put("button", button) })
    fun typeText(text: String) = conn?.fire("key", buildJsonObject { put("text", text) })
    fun tapKey(key: String) = conn?.fire("key", buildJsonObject { put("key", key) })
    fun combo(keys: List<String>) = conn?.fire("key", buildJsonObject { put("keys", JsonArray(keys.map(::JsonPrimitive))) })

    fun launch(block: suspend RemoteManager.() -> Unit) = scope.launch { runCatching { block() } }

    companion object {
        fun parseSummary(o: JsonObject) = RemoteSummary(
            id = o.s("id"), name = o.s("name"), category = o.s("category"), icon = o.s("icon"),
            tier = o.s("tier"), supported = o["supported"]?.toString() != "false", description = o.s("description"),
        )

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""

        /** Convert a phone-side custom button into server steps. */
        fun stepsFor(b: CustomButton): JsonArray = buildJsonArray {
            when (b.kind) {
                "keys" -> {
                    val keys = b.value.split('+').map { it.trim() }.filter { it.isNotEmpty() }
                    add(buildJsonObject {
                        if (keys.size == 1) put("key", keys[0]) else put("keys", JsonArray(keys.map(::JsonPrimitive)))
                    })
                }
                "text" -> add(buildJsonObject { put("type", b.value) })
                "run" -> add(buildJsonObject { put("run", b.value) })
                "open" -> add(buildJsonObject { put("open", b.value) })
                "media" -> add(buildJsonObject { put("key", b.value) })
                "mouse" -> add(buildJsonObject { put("mouse", b.value) })
            }
        }

        fun mediaStep(key: String): JsonArray = buildJsonArray { add(buildJsonObject { put("key", key) }) }
    }
}
