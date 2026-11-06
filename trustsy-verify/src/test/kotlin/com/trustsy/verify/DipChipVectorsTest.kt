package com.trustsy.verify

import com.trustsy.verify.internal.dipchip.CardTransport
import com.trustsy.verify.internal.dipchip.ChipFields
import com.trustsy.verify.internal.dipchip.ChipKey
import com.trustsy.verify.internal.dipchip.ChipPayload
import com.trustsy.verify.internal.dipchip.ChipSeal
import com.trustsy.verify.internal.dipchip.DipChipException
import com.trustsy.verify.internal.dipchip.ThaiIdCard
import com.trustsy.verify.internal.dipchip.Tis620
import com.trustsy.verify.internal.dipchip.hex
import com.trustsy.verify.internal.dipchip.toHex
import com.trustsy.verify.internal.flow.WireJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Replays `sdk/contract/dip-chip-vectors.json` — generated from the Rust
 * desktop SDK — against the Kotlin read and seal. Read in place, never copied.
 */
class DipChipVectorsTest {

    private val doc: JsonObject = run {
        val dir = System.getProperty("trustsy.contractDir") ?: "../contract"
        WireJson.parseToJsonElement(File(dir, "dip-chip-vectors.json").readText()).jsonObject
    }

    /** Answers each command with the transcript's next response, and fails on any other command. */
    private class Replay(override val atr: ByteArray, private val script: List<Pair<String, String>>) : CardTransport {
        var next = 0
        override fun transmit(command: ByteArray): ByteArray {
            if (next >= script.size) fail("command ${command.toHex()} past the end of the transcript")
            val (expected, response) = script[next++]
            assertEquals("command #$next", expected, command.toHex())
            return hex(response)
        }
    }

    @Test
    fun `tis620 matches the windows-874 table byte for byte`() {
        val table = doc["tis620"]!!.jsonArray
        assertEquals(256, table.size)
        for (b in 0..255) {
            val expected = table[b].let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("byte 0x%02x".format(b), expected, Tis620.char(b)?.toString())
        }
    }

    @Test
    fun `buddhist-era dates parse like the rust reader, century leap years included`() {
        val dates = doc["dates"]!!.jsonArray
        assertTrue(dates.size >= 10)
        for (d in dates) {
            val raw = d.jsonObject["raw"]!!.jsonPrimitive.content
            val iso = d.jsonObject["iso"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("'$raw'", iso, ThaiIdCard.thaiDate(raw))
        }
    }

    @Test
    fun `every card reads to the same fields with the same exchanges`() {
        val cards = doc["cards"]!!.jsonArray
        assertTrue("expected cards", cards.size >= 4)
        for (c in cards) {
            val card = c.jsonObject
            val name = card["name"]!!.jsonPrimitive.content
            val script = card["transcript"]!!.jsonArray.map {
                it.jsonObject["command"]!!.jsonPrimitive.content to it.jsonObject["response"]!!.jsonPrimitive.content
            }
            val replay = Replay(hex(card["atr"]!!.jsonPrimitive.content), script)
            val expected = card["expected"]!!.jsonObject
            val error = expected["error"]?.jsonPrimitive?.content
            if (error != null) {
                try {
                    ThaiIdCard.read(replay)
                    fail("$name: read succeeded")
                } catch (e: DipChipException) {
                    assertEquals(name, error, e.code)
                }
            } else {
                val payload = ThaiIdCard.read(replay)
                val fields = WireJson.decodeFromJsonElement(ChipFields.serializer(), expected["fields"]!!)
                assertEquals(name, fields, payload.fields)
                assertEquals(name, expected["photoBase64"]!!.jsonPrimitive.content, payload.photoBase64)
            }
            assertEquals("$name: every scripted exchange was made", script.size, replay.next)
        }
    }

    private val seal: JsonObject get() = doc["seal"]!!.jsonObject

    @Test
    fun `the aad and info match the rust seal`() {
        assertEquals(seal["info"]!!.jsonPrimitive.content, String(ChipSeal.INFO, Charsets.US_ASCII))
        val aad = ChipSeal.aad(
            seal["terminalId"]!!.jsonPrimitive.content,
            seal["eventTime"]!!.jsonPrimitive.content,
            seal["keyId"]!!.jsonPrimitive.int,
        )
        assertEquals(seal["aad"]!!.jsonPrimitive.content, aad.toHex())
    }

    /** Opens what Rust sealed: proves suite, info and AAD agree. */
    @Test
    fun `a rust envelope opens here`() {
        val envelope = seal["envelope"]!!.jsonObject
        assertEquals(2, envelope["v"]!!.jsonPrimitive.int)
        val plaintext = open(
            envelope["enc"]!!.jsonPrimitive.content,
            envelope["ct"]!!.jsonPrimitive.content,
            ChipSeal.aad(
                seal["terminalId"]!!.jsonPrimitive.content,
                seal["eventTime"]!!.jsonPrimitive.content,
                envelope["key_id"]!!.jsonPrimitive.int,
            ),
        )
        val opened = WireJson.decodeFromString(ChipPayload.serializer(), String(plaintext, Charsets.UTF_8))
        val expected = WireJson.decodeFromJsonElement(ChipPayload.serializer(), seal["payload"]!!)
        assertEquals(expected, opened)
    }

    /** And what Kotlin seals opens with the same secret — and only under the same AAD. */
    @Test
    fun `a kotlin envelope round-trips and is bound to its terminal`() {
        val key = ChipKey.parse(seal["recipientPublicKey"]!!.jsonPrimitive.content, 9)!!
        val payload = ChipPayload(ChipFields(idNumber = "1101700230705", thaiNationalId = "1101700230705"), "/9j/AA==")
        val envelope = ChipSeal.seal(key, "T-1", "2026-10-13T03:00:00Z", payload)
        assertEquals(9, envelope.keyId)
        val wire = WireJson.encodeToString(com.trustsy.verify.internal.dipchip.ChipEnvelope.serializer(), envelope)
        assertTrue("CID in clear: $wire", "1101700230705" !in wire)
        assertTrue(""""key_id":9""" in wire)
        val opened = open(envelope.enc, envelope.ct, ChipSeal.aad("T-1", "2026-10-13T03:00:00Z", 9))
        assertEquals(payload, WireJson.decodeFromString(ChipPayload.serializer(), String(opened, Charsets.UTF_8)))
        try {
            open(envelope.enc, envelope.ct, ChipSeal.aad("T-2", "2026-10-13T03:00:00Z", 9))
            fail("opened under another terminal")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `a chip key must be 32 bytes and an id 0-255`() {
        val good = seal["recipientPublicKey"]!!.jsonPrimitive.content
        assertTrue(ChipKey.parse(good, 0) != null && ChipKey.parse(good, 255) != null)
        assertNull(ChipKey.parse(good, 256))
        assertNull(ChipKey.parse(good, -1))
        assertNull(ChipKey.parse("aGVsbG8=", 1))
        assertNull(ChipKey.parse("not base64!", 1))
    }

    private fun open(enc: String, ct: String, aad: ByteArray): ByteArray {
        val b64 = Base64.getDecoder()
        val hpke = ChipSeal.hpke()
        val pair = hpke.deserializePrivateKey(
            b64.decode(seal["recipientSecretKey"]!!.jsonPrimitive.content),
            b64.decode(seal["recipientPublicKey"]!!.jsonPrimitive.content),
        )
        val context = hpke.setupBaseR(b64.decode(enc), pair, ChipSeal.INFO)
        return context.open(aad, b64.decode(ct))
    }
}
