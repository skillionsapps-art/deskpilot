package com.skillions.deskpilot.net

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AuthException(msg: String) : Exception(msg)
class RemoteException(msg: String) : Exception(msg)

data class Hello(val id: String, val name: String, val os: String, val mac: String, val protected: Boolean)

/**
 * One authenticated, encrypted session with a server.
 * request() = round-trip with an id; fire() = no response (mouse moves, key taps).
 */
class Connection(private val transport: Transport, private val onClosed: (Throwable?) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    private val input = DataInputStream(transport.input.buffered())
    private val output = DataOutputStream(transport.output.buffered())
    private val writeLock = Mutex()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: Crypto.Session
    lateinit var hello: Hello; private set
    @Volatile var closed = false; private set

    /** Performs the handshake and returns the server's remote summaries. */
    suspend fun open(password: String, deviceName: String): JsonObject = withContext(Dispatchers.IO) {
        withTimeout(20_000) {
            val (flag, body) = Frames.read(input)
            check(flag == Frames.PLAIN) { "expected hello" }
            val h = json.parseToJsonElement(body.decodeToString()).jsonObject
            hello = Hello(
                id = h.str("id"), name = h.str("name"), os = h.str("os"), mac = h.str("mac"),
                protected = h["protected"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            val salt = Base64.decode(h.str("salt"), Base64.DEFAULT)
            session = Crypto.Session(KeyCache.key(password, salt))
            val auth = buildJsonObject {
                put("type", "auth"); put("nonce", h.str("nonce")); put("device", deviceName)
            }
            Frames.write(output, Frames.SEALED, session.seal(auth.toString().encodeToByteArray()))
            val (f2, b2) = Frames.read(input)
            if (f2 == Frames.PLAIN) {
                val err = json.parseToJsonElement(b2.decodeToString()).jsonObject
                throw AuthException(err["message"]?.jsonPrimitive?.content ?: "Authentication failed")
            }
            val welcome = json.parseToJsonElement(session.open(b2).decodeToString()).jsonObject
            scope.launch { readLoop() }
            welcome
        }
    }

    private fun readLoop() {
        var error: Throwable? = null
        try {
            while (true) {
                val (flag, body) = Frames.read(input)
                if (flag != Frames.SEALED) continue
                val msg = json.parseToJsonElement(session.open(body).decodeToString()).jsonObject
                val id = msg["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                pending.remove(id)?.complete(msg)
            }
        } catch (t: Throwable) {
            error = t
        } finally {
            close()
            pending.values.forEach { it.completeExceptionally(error ?: RemoteException("closed")) }
            pending.clear()
            onClosed(error)
        }
    }

    private suspend fun send(obj: JsonObject) = withContext(Dispatchers.IO) {
        writeLock.withLock { Frames.write(output, Frames.SEALED, session.seal(obj.toString().encodeToByteArray())) }
    }

    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonElement {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        send(JsonObject(fields + mapOf("type" to JsonPrimitive(type), "id" to JsonPrimitive(id))))
        val res = try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
        if (res["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw RemoteException(res["error"]?.jsonPrimitive?.content ?: "Request failed")
        }
        return res["data"] ?: JsonNull
    }

    /** Fire-and-forget; failures surface through the read loop closing. */
    fun fire(type: String, fields: JsonObject) {
        if (closed) return
        scope.launch {
            runCatching { send(JsonObject(fields + ("type" to JsonPrimitive(type)))) }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { transport.close() }
        scope.cancel()
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content ?: ""
}

/** PBKDF2 at 100k rounds is slow on phones; servers keep one salt per run, so cache. */
private object KeyCache {
    private val cache = ConcurrentHashMap<String, javax.crypto.spec.SecretKeySpec>()
    fun key(password: String, salt: ByteArray) =
        cache.getOrPut(password.hashCode().toString() + ":" + salt.contentHashCode()) { Crypto.deriveKey(password, salt) }
}
