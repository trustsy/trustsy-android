package com.trustsy.verify.internal.dipchip

import com.trustsy.verify.internal.flow.WireJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.hpke.HPKE
import java.nio.ByteBuffer
import java.util.Base64

/** `chip_crypto::Envelope`, as the chip route reads it. */
@Serializable
internal data class ChipEnvelope(
    val v: Int,
    @SerialName("key_id") val keyId: Int,
    val enc: String,
    val ct: String,
)

/** `POST /api/v1/flow/{flow_id}/collection/chip`. */
@Serializable
internal data class DipChipSubmission(
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("event_time") val eventTime: String,
    val enc: ChipEnvelope,
)

/**
 * The server's chip key: 32 raw X25519 bytes + its rotation epoch. Not the
 * sealed-transport key — the server opens the two with different secrets.
 */
internal class ChipKey private constructor(val publicKey: ByteArray, val keyId: Int) {
    companion object {
        /** base64 (standard) of exactly 32 bytes, and a key id 0–255; null otherwise. */
        fun parse(publicKey: String, keyId: Int): ChipKey? {
            if (keyId !in 0..255) return null
            val raw = runCatching { Base64.getDecoder().decode(publicKey.trim()) }.getOrNull() ?: return null
            return if (raw.size == 32) ChipKey(raw, keyId) else null
        }
    }
}

/**
 * The chip seal (`sdk/contract/dip-chip.md` "The seal"; Rust `chip_crypto`).
 * HPKE base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM,
 * single shot.
 */
internal object ChipSeal {
    const val WIRE_VERSION = 2
    val INFO: ByteArray = "trustsy/chip-verify/hpke/v2".toByteArray(Charsets.US_ASCII)

    fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

    /** `key_id ‖ u32be len ‖ terminal ‖ u32be len ‖ event_time`. */
    fun aad(terminalId: String, eventTime: String, keyId: Int): ByteArray {
        val terminal = terminalId.toByteArray(Charsets.UTF_8)
        val time = eventTime.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 4 + terminal.size + 4 + time.size)
            .put(keyId.toByte())
            .putInt(terminal.size).put(terminal)
            .putInt(time.size).put(time)
            .array()
    }

    fun seal(key: ChipKey, terminalId: String, eventTime: String, payload: ChipPayload): ChipEnvelope {
        val plaintext = WireJson.encodeToString(ChipPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        val hpke = hpke()
        val context = hpke.setupBaseS(hpke.deserializePublicKey(key.publicKey), INFO)
        val ct = context.seal(aad(terminalId, eventTime, key.keyId), plaintext)
        val b64 = Base64.getEncoder()
        return ChipEnvelope(WIRE_VERSION, key.keyId, b64.encodeToString(context.encapsulation), b64.encodeToString(ct))
    }
}
