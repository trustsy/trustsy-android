package com.trustsy.verify.internal.net

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The sealed transport's cryptography (`sdk/contract/sealed-transport.md`):
 * Oblivious HTTP's construction (RFC 9458 §4.3–4.4) over a JSON envelope.
 * HPKE base mode, HKDF-SHA256 / AES-256-GCM, with one of two KEMs: X-Wing
 * (ML-KEM-768 + X25519, post-quantum) or DHKEM(X25519, HKDF-SHA256).
 * Pure: no Android types, so the contract's vectors run on the JVM.
 */
internal object Sealed {
    const val CONTENT_TYPE = "application/trustsy-sealed"
    const val KDF_ID = 0x0001
    const val AEAD_ID = 0x0002
    const val HEADER_LEN = 7
    const val RESPONSE_NONCE_LEN = 32
    private val REQUEST_LABEL = "trustsy sealed request v1".toByteArray(Charsets.US_ASCII)
    private val RESPONSE_LABEL = "trustsy sealed response v1".toByteArray(Charsets.US_ASCII)

    /** The KEM half of a suite, as the header names it. */
    enum class Kem(val id: Int, val bcId: Short, val encLen: Int, val publicLen: Int) {
        X25519(0x0020, HPKE.kem_X25519_SHA256, 32, 32),
        XWING(0x647a, HPKE.kem_X_WING, 1120, 1216),
        ;

        companion object {
            fun of(id: Int): Kem? = entries.firstOrNull { it.id == id }
        }
    }

    private fun hpke(kem: Kem) = HPKE(HPKE.mode_base, kem.bcId, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

    /** A pinned server key: `key_id ‖ kem ‖ kdf ‖ aead ‖ public key`. */
    class KeyConfig private constructor(val keyId: Int, val kem: Kem, val publicKey: ByteArray) {
        /** `key_id ‖ kem_id ‖ 0x0001 ‖ 0x0002`. */
        val header: ByteArray = byteArrayOf(
            keyId.toByte(), (kem.id shr 8).toByte(), kem.id.toByte(), 0, KDF_ID.toByte(), 0, AEAD_ID.toByte(),
        )

        /** `"trustsy sealed request v1" ‖ 0x00 ‖ header`. */
        val info: ByteArray = REQUEST_LABEL + byteArrayOf(0) + header

        companion object {
            /** One base64url (no padding) key config of a suite this SDK speaks; null otherwise. */
            fun parse(config: String): KeyConfig? {
                val raw = Base64Url.decode(config.trim()) ?: return null
                if (raw.size < HEADER_LEN) return null
                fun u16(i: Int) = ((raw[i].toInt() and 0xff) shl 8) or (raw[i + 1].toInt() and 0xff)
                val kem = Kem.of(u16(1)) ?: return null
                if (u16(3) != KDF_ID || u16(5) != AEAD_ID) return null
                if (raw.size != HEADER_LEN + kem.publicLen) return null
                return KeyConfig(raw[0].toInt() and 0xff, kem, raw.copyOfRange(HEADER_LEN, raw.size))
            }

            /**
             * The key to seal to, from what the host app pinned: one key config,
             * or several separated by `,` (base64url never contains one) — an
             * X-Wing key and an X25519 key, so the same build works for iOS < 26
             * devices too. Null = nothing usable; the flow refuses to start.
             *
             * An entry naming a suite this SDK does not know is SKIPPED (a newer
             * suite pinned into an app on an older SDK). Any other entry that does
             * not parse refuses the WHOLE pin: a truncated X-Wing key must fail
             * loudly, not leave the app on X25519 with nobody noticing. X-Wing is
             * preferred; within a suite the first entry wins (list the newest key
             * first while rotating).
             */
            fun select(pinned: String): KeyConfig? {
                val entries = pinned.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val usable = mutableListOf<KeyConfig>()
                for (entry in entries) {
                    val raw = Base64Url.decode(entry) ?: return null
                    if (raw.size < HEADER_LEN) return null
                    val kemId = ((raw[1].toInt() and 0xff) shl 8) or (raw[2].toInt() and 0xff)
                    if (Kem.of(kemId) == null) continue
                    usable += parse(entry) ?: return null
                }
                return usable.firstOrNull { it.kem == Kem.XWING } ?: usable.firstOrNull()
            }
        }
    }

    /** One sealed request, and what is needed to open its answer. Never reused. */
    class Request(val body: ByteArray, val enc: ByteArray, val secret: ByteArray)

    class InvalidResponse(message: String) : Exception(message)

    /** Seal [plaintext] to [key] with a fresh ephemeral key (a new `enc` every call). */
    fun sealRequest(key: KeyConfig, plaintext: ByteArray): Request {
        val hpke = hpke(key.kem)
        val context = hpke.setupBaseS(hpke.deserializePublicKey(key.publicKey), key.info)
        val enc = context.encapsulation
        val ct = context.seal(ByteArray(0), plaintext)
        val secret = context.export(RESPONSE_LABEL, 32)
        return Request(key.header + enc + ct, enc, secret)
    }

    /** `response_nonce ‖ ct` → the inner answer's bytes; [InvalidResponse] if it does not open. */
    fun openResponse(secret: ByteArray, enc: ByteArray, body: ByteArray): ByteArray {
        if (body.size < RESPONSE_NONCE_LEN + 16) throw InvalidResponse("sealed response too short")
        val nonce = body.copyOfRange(0, RESPONSE_NONCE_LEN)
        val ct = body.copyOfRange(RESPONSE_NONCE_LEN, body.size)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val prk = hkdf.extractPRK(enc + nonce, secret)
        fun expand(info: String, length: Int): ByteArray {
            val g = HKDFBytesGenerator(SHA256Digest())
            g.init(HKDFParameters.skipExtractParameters(prk, info.toByteArray(Charsets.US_ASCII)))
            return ByteArray(length).also { g.generateBytes(it, 0, length) }
        }
        val key = expand("key", 32)
        val iv = expand("nonce", 12)
        val gcm = GCMBlockCipher.newInstance(AESEngine.newInstance())
        gcm.init(false, AEADParameters(KeyParameter(key), 128, iv, ByteArray(0)))
        val out = ByteArray(gcm.getOutputSize(ct.size))
        return try {
            val n = gcm.processBytes(ct, 0, ct.size, out, 0)
            val total = n + gcm.doFinal(out, n)
            out.copyOf(total)
        } catch (e: Exception) {
            throw InvalidResponse("sealed response did not open")
        }
    }
}

/** RFC 4648 §5 base64url without padding (the key config), and standard base64. */
internal object Base64Url {
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decode(text: String): ByteArray? {
        if (text.isEmpty() || text.any { it !in URL }) return null
        if (text.length % 4 == 1) return null
        var bits = 0
        var count = 0
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        for (c in text) {
            bits = (bits shl 6) or URL.indexOf(c)
            count += 6
            if (count >= 8) {
                count -= 8
                out.write((bits shr count) and 0xff)
                bits = bits and ((1 shl count) - 1)
            }
        }
        // Spare bits must be zero, or the text has two spellings.
        if (bits != 0) return null
        return out.toByteArray()
    }
}
