package com.trustsy.verify.internal.chat

import com.trustsy.verify.internal.flow.WireJson
import com.trustsy.verify.internal.net.ApiClient
import com.trustsy.verify.internal.net.SealedTransport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** One message of the session chat, as the applicant sees it — never an operator's name. */
@Serializable
internal data class ChatMessage(
    val id: String,
    /** `operator` | `applicant`. */
    val sender: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    /** When the other side opened the thread after it; null = not yet. */
    @SerialName("read_at") val readAt: String? = null,
) {
    val fromTeam: Boolean get() = sender == "operator"
}

@Serializable
internal data class ChatThread(
    val messages: List<ChatMessage> = emptyList(),
    /** True once the reviewing team has written: the applicant can reply. */
    @SerialName("can_reply") val canReply: Boolean = false,
) {
    /** Team messages the applicant has not opened the chat to see (server-stamped). */
    val unseen: Int get() = messages.count { it.fromTeam && it.readAt == null }
}

/** Where to dial Soketi for this case (`GET /collection-flow/realtime`). */
@Serializable
internal data class RealtimeDescriptor(
    val channel: String,
    val key: String? = null,
    val host: String? = null,
    val port: Int? = null,
    @SerialName("use_tls") val useTls: Boolean? = null,
)

@Serializable
private data class Auth(val auth: String)

/**
 * The session chat over the SEALED transport — the same channel as every other
 * SDK call, so a proxy the phone trusts reads none of it. The websocket
 * ([PusherSocket]) only says "something is new"; the text is always read here.
 */
internal class ChatClient(private val transport: SealedTransport) {

    /** `markRead` only while the chat is open: a closed launcher must not mark it seen. */
    suspend fun thread(markRead: Boolean): ChatThread =
        call("GET", if (markRead) "$PATH?mark_read=true" else PATH, null, ChatThread.serializer())

    suspend fun send(body: String): ChatThread =
        call("POST", PATH, JsonObject(mapOf("body" to JsonPrimitive(body))), ChatThread.serializer())

    suspend fun realtime(): RealtimeDescriptor =
        call("GET", "/api/v1/collection-flow/realtime", null, RealtimeDescriptor.serializer())

    /** Sign a private-channel subscription; the server signs only this case's channel. */
    suspend fun authorize(socketId: String, channel: String): String =
        call(
            "POST",
            "/api/v1/collection-flow/pusher/auth",
            JsonObject(mapOf("socket_id" to JsonPrimitive(socketId), "channel_name" to JsonPrimitive(channel))),
            Auth.serializer(),
        ).auth

    private suspend fun <T> call(method: String, path: String, body: JsonElement?, decoder: KSerializer<T>): T {
        val inner = transport.send(method, path, body)
        if (inner.status !in 200..299) throw ApiClient.toError(inner)
        val json = inner.body ?: throw IOException("empty answer from $path")
        return WireJson.decodeFromJsonElement(decoder, json)
    }

    companion object {
        const val PATH = "/api/v1/collection-flow/messages"
        const val RATE_LIMITED = "chat_rate_limited"
    }
}
