package com.trustsy.verify

import com.trustsy.verify.internal.flow.DocumentKind
import com.trustsy.verify.internal.flow.DocumentSide
import com.trustsy.verify.internal.flow.Documents
import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.flow.WireJson
import com.trustsy.verify.internal.liveness.ReportedFrame
import com.trustsy.verify.internal.net.ApiClient
import com.trustsy.verify.internal.net.ChallengeReport
import com.trustsy.verify.internal.net.EventBody
import com.trustsy.verify.internal.net.InnerResponse
import com.trustsy.verify.internal.net.LivenessSession
import com.trustsy.verify.internal.net.LivenessSubmission
import com.trustsy.verify.internal.net.StartLiveness
import com.trustsy.verify.internal.net.StepSubmission
import com.trustsy.verify.nfc.Mrz
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCodecsTest {

    @Test
    fun `a snapshot decodes with branding and unknown fields`() {
        val json = """
            {"id":"case-1","state":"data_collection.awaiting_consent","status":"active",
             "tags":["collection_flow"],"context":{"entity":{"x":1}},
             "available_events":["CONSENT_GIVEN","APPROVE"],"done":false,
             "branding":{"display_name":"  Acme Bank ","logo_url":null,"website_url":null,
               "privacy_url":"https://acme.example/privacy","terms_url":null,
               "style":{"colors":{"primary_button":"#112233"},"radius":8},"texts":null},
             "language":"th","step":null,"future_field":42}
        """.trimIndent()
        val s = WireJson.decodeFromString(Snapshot.serializer(), json)
        assertEquals("case-1", s.id)
        assertEquals(listOf("CONSENT_GIVEN", "APPROVE"), s.availableEvents)
        assertEquals("Acme Bank", s.branding.partnerName)
        assertEquals("#112233", s.branding.style?.colors?.get("primary_button"))
        assertEquals("th", s.language)
    }

    @Test
    fun `a blank partner name is no name`() {
        val s = WireJson.decodeFromString(Snapshot.serializer(), """{"id":"x","status":"active","branding":{"display_name":"  "}}""")
        assertNull(s.branding.partnerName)
    }

    @Test
    fun `a liveness session decodes with and without a challenge`() {
        val live = WireJson.decodeFromString(
            LivenessSession.serializer(),
            """{"sessionId":"s","state":"active","stepsTotal":4,"stepsDone":1,"policyVersion":"p",
               "challenge":{"challengeId":"c","step":2,"action":"flash","expiresAt":"2026-10-12T00:00:00Z",
                 "sequence":[{"color":"red","ms":500},{"color":"blue","ms":650}]}}""",
        )
        assertEquals("flash", live.challenge?.action)
        assertEquals(650, live.challenge?.sequence?.get(1)?.ms)
        val done = WireJson.decodeFromString(LivenessSession.serializer(), """{"sessionId":"s","state":"passed","challenge":null}""")
        assertNull(done.challenge)
    }

    @Test
    fun `frames omit what was not measured`() {
        val frame = ReportedFrame(1.5, -2.0, 0.25, 300.0, 120, eyeOpen = 0.9)
        val encoded = WireJson.encodeToString(ReportedFrame.serializer(), frame)
        assertEquals("""{"yaw":1.5,"pitch":-2.0,"roll":0.25,"face_size":300.0,"t_ms":120,"eye_open":0.9}""", encoded)
    }

    @Test
    fun `the step body is camelCase like the server's`() {
        val body = StepSubmission("s", "c", emptyList())
        assertEquals("""{"sessionId":"s","challengeId":"c","frames":[]}""", WireJson.encodeToString(StepSubmission.serializer(), body))
    }

    @Test
    fun `starting a session always sends a body`() {
        assertEquals("{}", WireJson.encodeToString(StartLiveness.serializer(), StartLiveness()))
        assertEquals("""{"flash":false}""", WireJson.encodeToString(StartLiveness.serializer(), StartLiveness(false)))
    }

    @Test
    fun `events carry payload and telemetry apart, omitting what is absent`() {
        assertEquals("""{"type":"PHONE_VERIFIED"}""", WireJson.encodeToString(EventBody.serializer(), EventBody("PHONE_VERIFIED")))
        val withPayload = EventBody(
            "DOCUMENT_CHIP_UNAVAILABLE",
            buildJsonObject { put("reason", "no_nfc") },
            buildJsonObject { put("sdk", "trustsy-android/0.2.0") },
        )
        assertEquals(
            """{"type":"DOCUMENT_CHIP_UNAVAILABLE","payload":{"reason":"no_nfc"},"telemetry":{"sdk":"trustsy-android/0.2.0"}}""",
            WireJson.encodeToString(EventBody.serializer(), withPayload),
        )
    }

    @Test
    fun `the liveness submit uses the server's snake_case keys`() {
        val body = LivenessSubmission("p", videoFileId = "v", challengeReport = ChallengeReport(listOf("blink"), emptyList()))
        assertEquals(
            """{"photo_file_id":"p","video_file_id":"v","challenge_report":{"challenges":["blink"],"frames":[]}}""",
            WireJson.encodeToString(LivenessSubmission.serializer(), body),
        )
    }

    @Test
    fun `error bodies keep the server's code and details`() {
        fun inner(status: Int, body: String?) =
            InnerResponse(status, "application/json", body?.let { WireJson.parseToJsonElement(it) })
        val e = ApiClient.toError(inner(403, """{"code":"location_not_allowed","message":"no","details":{"country":"Laos"}}"""))
        assertTrue(e.isAccessRefusal)
        assertEquals(JsonPrimitive("Laos"), e.details?.get("country"))
        val other = ApiClient.toError(inner(403, """{"code":"forbidden"}"""))
        assertFalse(other.isAccessRefusal)
        val bare = ApiClient.toError(InnerResponse(502, "text/html", bodyBase64 = "PGh0bWw+"))
        assertNull(bare.code)
        assertEquals(502, bare.status)
    }

    // ---- documents ---------------------------------------------------------------

    @Test
    fun `file names keep the id- prefix the review lists`() {
        assertEquals("id-front.jpg", Documents.fileNameFor(DocumentKind.THAI_ID, DocumentSide.FRONT))
        assertEquals("id-back.jpg", Documents.fileNameFor(DocumentKind.THAI_ID, DocumentSide.BACK))
        assertEquals("id-driving-license-front.jpg", Documents.fileNameFor(DocumentKind.DRIVING_LICENSE, DocumentSide.FRONT))
        assertEquals("id-driving-license-back.jpg", Documents.fileNameFor(DocumentKind.DRIVING_LICENSE, DocumentSide.BACK))
        assertEquals("id-passport.jpg", Documents.fileNameFor(DocumentKind.PASSPORT, DocumentSide.FRONT))
    }

    @Test
    fun `events follow the document and side`() {
        assertEquals("ID_BACK_CAPTURED", Documents.eventFor(DocumentKind.THAI_ID, DocumentSide.BACK))
        assertEquals("PASSPORT_CAPTURED", Documents.eventFor(DocumentKind.PASSPORT, DocumentSide.BACK))
    }

    @Test
    fun `thailand offers the thai documents, everyone else a passport`() {
        assertEquals(listOf(DocumentKind.THAI_ID, DocumentKind.DRIVING_LICENSE), Documents.documentsFor("TH"))
        assertEquals(listOf(DocumentKind.PASSPORT), Documents.documentsFor("LA"))
    }

    // ---- chip access key -----------------------------------------------------------

    @Test
    fun `mrz rules match the web app`() {
        assertEquals("261012", Mrz.date("2026-10-12"))
        assertNull(Mrz.date("12/10/2026"))
        assertEquals("AA1234567", Mrz.documentNumber(" aa-123 4567 "))
        assertTrue(Mrz.complete("AA123", "1990-01-01", "2030-01-01"))
        assertFalse(Mrz.complete("AA12", "1990-01-01", "2030-01-01"))
        assertFalse(Mrz.complete("AA12345", "1990-01-01", ""))
    }
}
