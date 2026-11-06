package com.trustsy.verify.internal.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The Pusher protocol frames this SDK speaks — pure, so they are unit-tested
 * without a socket. Soketi implements Pusher protocol 7.
 */
internal object PusherFrames {
    data class Frame(val event: String, val channel: String?, val data: String?)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Frame? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        Frame(
            event = o["event"]?.jsonPrimitive?.contentOrNull ?: return null,
            channel = o["channel"]?.jsonPrimitive?.contentOrNull,
            // `data` is a JSON STRING holding JSON on Pusher's wire, or an object on some servers.
            data = o["data"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() },
        )
    }.getOrNull()

    /** The socket id from `pusher:connection_established`. */
    fun socketId(frame: Frame): String? = frame.data?.let {
        runCatching { json.parseToJsonElement(it).jsonObject["socket_id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    fun subscribe(channel: String, auth: String): String = buildJsonObject {
        put("event", "pusher:subscribe")
        put("data", buildJsonObject {
            put("channel", channel)
            put("auth", auth)
        })
    }.toString()

    const val PONG = """{"event":"pusher:pong","data":{}}"""

    fun url(descriptor: RealtimeDescriptor, fallbackHost: String): String? {
        val key = descriptor.key?.takeIf { it.isNotBlank() } ?: return null
        val tls = descriptor.useTls ?: true
        val host = descriptor.host?.takeIf { it.isNotBlank() } ?: fallbackHost
        val port = descriptor.port ?: if (tls) 443 else 80
        return "${if (tls) "wss" else "ws"}://$host:$port/app/$key?protocol=7&client=trustsy-android&version=1.0"
    }
}

/**
 * A minimal Pusher-protocol client over OkHttp's WebSocket: connect, sign the
 * case's private channel through [authorize] (sealed REST), and call [onChat]
 * for every `chat-message`. Reconnects with backoff until [close]. Nothing it
 * receives is trusted as content — it is a doorbell.
 */
internal class PusherSocket(
    private val scope: CoroutineScope,
    private val url: String,
    private val channel: String,
    private val authorize: suspend (socketId: String) -> String,
    private val onChat: () -> Unit,
    private val http: OkHttpClient = defaultHttp,
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var reconnect: Job? = null
    private var attempt = 0

    fun open() {
        if (closed) return
        socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun close() {
        closed = true
        reconnect?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = PusherFrames.parse(text) ?: return
            when (frame.event) {
                "pusher:connection_established" -> {
                    attempt = 0
                    val id = PusherFrames.socketId(frame) ?: return
                    scope.launch {
                        runCatching { authorize(id) }.onSuccess { auth ->
                            webSocket.send(PusherFrames.subscribe(channel, auth))
                        }
                    }
                }
                "pusher:ping" -> webSocket.send(PusherFrames.PONG)
                CHAT_EVENT -> if (frame.channel == channel) onChat()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry()
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
    }

    private fun retry() {
        if (closed) return
        val wait = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        attempt++
        reconnect = scope.launch {
            delay(wait)
            open()
        }
    }

    companion object {
        const val CHAT_EVENT = "chat-message"
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
