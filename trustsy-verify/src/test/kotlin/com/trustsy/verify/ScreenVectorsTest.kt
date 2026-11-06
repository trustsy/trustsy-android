package com.trustsy.verify

import com.trustsy.verify.internal.flow.Flow
import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.flow.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays `sdk/contract/screen-vectors.json` — generated from the web app's
 * live `stepFor` + `screenFor` — against the Kotlin port. Read in place, never
 * copied: a regenerated file is what this test sees.
 */
class ScreenVectorsTest {

    private val doc: JsonObject = run {
        val dir = System.getProperty("trustsy.contractDir") ?: "../contract"
        WireJson.parseToJsonElement(File(dir, "screen-vectors.json").readText()).jsonObject
    }

    @Test
    fun `every vector matches step and screen`() {
        assertEquals(1, doc["version"]!!.jsonPrimitive.int)
        val vectors = doc["vectors"]!!.jsonArray
        // The file is regenerated from the web app; it held 78 when this port was written.
        assertTrue("expected at least 78 vectors, got ${vectors.size}", vectors.size >= 78)
        val failures = mutableListOf<String>()
        for (v in vectors) {
            val vector = v.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            val snapshot = WireJson.decodeFromJsonElement(Snapshot.serializer(), vector["snapshot"]!!)
            val step = Flow.stepFor(snapshot)
            val expectedStep = vector["step"]!!.jsonObject
            val actualStep = buildMap {
                put("kind", JsonPrimitive(step.kind.wire))
                put("actions", JsonArray(step.actions.map(::JsonPrimitive)))
                step.outcome?.let { put("outcome", JsonPrimitive(it.wire)) }
            }
            if (JsonObject(actualStep) != expectedStep) failures += "$name: step $actualStep != $expectedStep"
            val screen = Flow.screenFor(step).toJson()
            val expectedScreen = vector["screen"]!!.jsonObject
            if (screen != expectedScreen) failures += "$name: screen $screen != $expectedScreen"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `vectors cover every screen kind the contract lists`() {
        val kinds = doc["vectors"]!!.jsonArray
            .map { it.jsonObject["screen"]!!.jsonObject["kind"]!!.jsonPrimitive.content }
            .toSet()
        for (kind in listOf(
            "terminal", "blocked", "checking", "review", "consent", "document", "documentBack",
            "businessDocument", "company", "keyPeople", "awaitingUsers", "documentGroups", "phone",
            "documentChip", "dipChip", "signature", "liveness", "details", "actions",
        )) {
            assertTrue(kind, kind in kinds)
        }
    }

    /**
     * `screen.ts`'s order where no vector combines the two events (found by a
     * mutation probe: swapping phone and chip survived every vector).
     */
    @Test
    fun `screenFor keeps screen_ts precedence for pairs the vectors do not combine`() {
        fun screen(vararg events: String) = Flow.screenFor(
            Snapshot(id = "c", state = "x", status = "active", tags = listOf("collection_flow"), availableEvents = events.toList()),
        ).kind
        assertEquals("phone", screen("DOCUMENT_CHIP_READ", "PHONE_VERIFIED"))
        assertEquals("documentChip", screen("SIGNATURE_SUBMITTED", "DOCUMENT_CHIP_READ"))
        assertEquals("businessDocument", screen("PHONE_VERIFIED", "BUSINESS_DOC_CAPTURED"))
        assertEquals("details", screen("DETAILS_SUBMITTED", "SUBMIT"))
        assertEquals("documentBack", screen("ID_BACK_CAPTURED", "COMPANY_SUBMITTED"))
    }

    @Test
    fun `v1 native support matches the contract table`() {
        val unsupported = setOf("signature", "company", "keyPeople", "awaitingUsers", "documentGroups", "businessDocument")
        for (v in doc["vectors"]!!.jsonArray) {
            val snapshot = WireJson.decodeFromJsonElement(Snapshot.serializer(), v.jsonObject["snapshot"]!!)
            val screen = Flow.screenFor(snapshot)
            assertEquals(screen.kind, screen.kind !in unsupported, Flow.isSupported(screen))
        }
    }
}
