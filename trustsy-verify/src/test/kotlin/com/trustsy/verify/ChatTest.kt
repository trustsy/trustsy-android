package com.trustsy.verify

import com.trustsy.verify.internal.chat.ChatThread
import com.trustsy.verify.internal.chat.PusherFrames
import com.trustsy.verify.internal.chat.PusherSocket
import com.trustsy.verify.internal.chat.RealtimeDescriptor
import com.trustsy.verify.internal.flow.WireJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The session chat's pure parts: the Pusher frames the socket speaks and the thread it reads. */
class ChatTest {
    @Test
    fun `the socket id is read out of the stringified data Pusher sends`() {
        val frame = PusherFrames.parse(
            """{"event":"pusher:connection_established","data":"{\"socket_id\":\"123.456\",\"activity_timeout\":120}"}""",
        )!!
        assertEquals("123.456", PusherFrames.socketId(frame))
    }

    @Test
    fun `a chat signal names its channel and carries no text`() {
        val frame = PusherFrames.parse(
            """{"event":"chat-message","channel":"private-flow-c1","data":"{\"id\":\"msg_1\"}"}""",
        )!!
        assertEquals(PusherSocket.CHAT_EVENT, frame.event)
        assertEquals("private-flow-c1", frame.channel)
        assertNull(PusherFrames.parse("not json"))
    }

    @Test
    fun `the subscribe frame carries the channel and the server's signature`() {
        val o = Json.parseToJsonElement(PusherFrames.subscribe("private-flow-c1", "key:sig")).jsonObject
        assertEquals("pusher:subscribe", o["event"]!!.jsonPrimitive.content)
        val data = o["data"]!!.jsonObject
        assertEquals("private-flow-c1", data["channel"]!!.jsonPrimitive.content)
        assertEquals("key:sig", data["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the socket url follows the descriptor and is off without a key`() {
        val d = RealtimeDescriptor(channel = "private-flow-c1", key = "app-key", host = "rt.example.com", port = 443, useTls = true)
        assertEquals("wss://rt.example.com:443/app/app-key?protocol=7&client=trustsy-android&version=1.0", PusherFrames.url(d, "api.example.com"))
        assertEquals(
            "ws://api.example.com:6001/app/k?protocol=7&client=trustsy-android&version=1.0",
            PusherFrames.url(RealtimeDescriptor("c", key = "k", port = 6001, useTls = false), "api.example.com"),
        )
        assertNull(PusherFrames.url(RealtimeDescriptor("c", key = null), "api.example.com"))
    }

    @Test
    fun `unseen counts team messages the applicant has not opened`() {
        val thread = WireJson.decodeFromString(
            ChatThread.serializer(),
            """{"can_reply":true,"messages":[
              {"id":"1","sender":"operator","body":"a","created_at":"t","read_at":null},
              {"id":"2","sender":"operator","body":"b","created_at":"t","read_at":"t"},
              {"id":"3","sender":"applicant","body":"c","created_at":"t"}
            ]}""",
        )
        assertEquals(1, thread.unseen)
        assertEquals(true, thread.canReply)
    }
}
