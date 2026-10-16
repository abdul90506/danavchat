package com.danav.chat.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class Conn { OFFLINE, CONNECTING, ONLINE }

data class Presence(val online: Boolean, val lastSeen: Long)

/** Single WebSocket to the user's Durable Object hub, with auto-reconnect. */
object Realtime {
    private const val TAG = "Realtime"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(Conn.OFFLINE)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    val presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    /** peerId -> (state, timestamp) state = "typing" | "recording" */
    val typing = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var attempts = 0
    private val holds = mutableSetOf<String>()

    /** Keep the socket open while any hold is active (foreground, call…) */
    @Synchronized fun hold(reason: String) {
        holds.add(reason)
        if (!wanted) { wanted = true; connectNow() }
        else if (ws == null && _state.value == Conn.OFFLINE) connectNow()
    }

    @Synchronized fun release(reason: String) {
        holds.remove(reason)
        if (holds.isEmpty()) {
            scope.launch {
                delay(8000)
                synchronized(this@Realtime) { if (holds.isEmpty()) disconnect() }
            }
        }
    }

    @Synchronized private fun disconnect() {
        wanted = false
        reconnectJob?.cancel(); pingJob?.cancel()
        ws?.close(1000, "bye"); ws = null
        _state.value = Conn.OFFLINE
    }

    @Synchronized fun forceReconnect() {
        if (!wanted) return
        ws?.cancel(); ws = null
        connectNow()
    }

    @Synchronized private fun connectNow() {
        if (Api.prefs.token == null) return
        if (ws != null) return
        reconnectJob?.cancel()
        _state.value = Conn.CONNECTING
        val req = Request.Builder().url(Api.wsUrl()).build()
        ws = Api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                _state.value = Conn.ONLINE
                pingJob?.cancel()
                pingJob = scope.launch { while (true) { delay(25_000); webSocket.send("ping") } }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (j.optString("t")) {
                    "presence" -> presence.value = presence.value + (j.getString("u") to Presence(j.optBoolean("online"), j.optLong("lastSeen")))
                    "typing" -> {
                        val u = j.getString("u"); val s = j.optString("s")
                        typing.value = if (s.isEmpty()) typing.value - u else typing.value + (u to (s to System.currentTimeMillis()))
                        if (s.isNotEmpty()) scope.launch {
                            delay(6000)
                            val cur = typing.value[u]
                            if (cur != null && System.currentTimeMillis() - cur.second >= 5900) typing.value = typing.value - u
                        }
                    }
                    "msg" -> typing.value = typing.value - j.getJSONObject("m").getString("sender")
                }
                _events.tryEmit(j)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDrop(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message} ${response?.code}")
                if (response?.code == 401) { wanted = false }
                onDrop(webSocket)
            }
        })
    }

    @Synchronized private fun onDrop(socket: WebSocket) {
        if (ws !== socket) return
        ws = null
        pingJob?.cancel()
        _state.value = Conn.OFFLINE
        presence.value = emptyMap()
        if (wanted) {
            val wait = (1000L shl attempts.coerceAtMost(4)).coerceAtMost(15_000)
            attempts++
            reconnectJob = scope.launch { delay(wait); connectNow() }
        }
    }

    fun send(j: JSONObject): Boolean {
        val s = ws ?: return false
        if (_state.value != Conn.ONLINE) return false
        return s.send(j.toString())
    }

    suspend fun awaitOnline(timeoutMs: Long = 10_000): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it == Conn.ONLINE }; true } ?: false
}
