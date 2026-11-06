package com.trustsy.verify.internal.dipchip

import kotlinx.serialization.Serializable
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Base64

/** A present card: exchange APDUs, read the ATR. [UsbCcidReader] is the device one. */
internal interface CardTransport {
    val atr: ByteArray

    /** One APDU → the raw answer (data ‖ SW1 SW2). */
    fun transmit(command: ByteArray): ByteArray
}

/** Why a dip-chip step could not complete. [code] is what the screen switches on. */
internal class DipChipException(val code: String, message: String) : Exception(message) {
    companion object {
        const val READ_FAILED = "read_failed"
        const val NO_CARD = "no_card"
        const val READER_UNSUPPORTED = "reader_unsupported"
        const val READER_IO = "reader_io"
    }
}

/** The sealed payload's fields (`chip_crypto::ChipFields`); null = absent. */
@Serializable
internal data class ChipFields(
    val idNumber: String? = null,
    val thaiNationalId: String? = null,
    val thaiName: String? = null,
    val englishName: String? = null,
    val dateOfBirth: String? = null,
    val dateOfIssue: String? = null,
    val dateOfExpiry: String? = null,
    val gender: String? = null,
    val address: String? = null,
)

/** The plaintext that is sealed (`chip_crypto::SealedChipPayload`). */
@Serializable
internal data class ChipPayload(val fields: ChipFields, val photoBase64: String? = null)

/**
 * The Thai national ID card read — a port of `sdk/desktop/trustsy-dipchip`
 * (`thai_smartcard_core::read_personal` + `fields_from`), pinned to it by
 * `sdk/contract/dip-chip-vectors.json`. Pure: no Android types.
 */
internal object ThaiIdCard {
    private val SELECT_PERSONAL_APPLET = hex("00a4040008a000000054480001")
    private val READ_CID = hex("80b0000402000d")
    private val READ_NAME_TH = hex("80b00011020064")
    private val READ_NAME_EN = hex("80b00075020064")
    private val READ_DOB = hex("80b000d9020008")
    private val READ_GENDER = hex("80b000e1020001")
    private val READ_CARD_ISSUER = hex("80b000f6020064")
    private val READ_ISSUE_DATE = hex("80b00167020008")
    private val READ_EXPIRE_DATE = hex("80b0016f020008")
    private val READ_ADDRESS = hex("80b01579020064")

    /** `80 B0 hi lo 02 00 FF`: hi 01…14, lo 7B down to 68. */
    private val PORTRAIT_COMMANDS: List<ByteArray> = (0 until 20).map { i ->
        byteArrayOf(0x80.toByte(), 0xB0.toByte(), (i + 1).toByte(), (0x7B - i).toByte(), 0x02, 0x00, 0xFF.toByte())
    }

    private const val SW1_READY = 0x61

    fun read(card: CardTransport): ChipPayload {
        val select = card.transmit(SELECT_PERSONAL_APPLET)
        val selected = select.size >= 2 && (
            (select[select.size - 2] == 0x90.toByte() && select[select.size - 1] == 0x00.toByte()) ||
                (select[select.size - 2].toInt() and 0xff) == SW1_READY
            )
        if (!selected) throw DipChipException(DipChipException.READ_FAILED, "not a Thai national ID card")
        val atr = card.atr
        val base = if (atr.size >= 2 && atr[0] == 0x3B.toByte() && atr[1] == 0x67.toByte()) {
            byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x01)
        } else {
            byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00)
        }
        fun text(command: ByteArray, thai: Boolean): String {
            val data = field(card, command, base)
            val decoded = if (thai) Tis620.decode(data) else String(data, Charsets.UTF_8)
            return decoded.trim()
        }
        val cid = text(READ_CID, thai = false)
        val nameTh = formatName(text(READ_NAME_TH, thai = true))
        val nameEn = formatName(text(READ_NAME_EN, thai = false))
        val dob = thaiDate(text(READ_DOB, thai = false))
        val gender = text(READ_GENDER, thai = false)
        text(READ_CARD_ISSUER, thai = true) // read, like every host, but not sent
        val issued = thaiDate(text(READ_ISSUE_DATE, thai = false))
        val expires = thaiDate(text(READ_EXPIRE_DATE, thai = false))
        val address = formatAddress(text(READ_ADDRESS, thai = true))
        val portrait = java.io.ByteArrayOutputStream(5100)
        for (command in PORTRAIT_COMMANDS) portrait.write(field(card, command, base))

        fun present(s: String) = s.takeIf { it.isNotBlank() }
        return ChipPayload(
            fields = ChipFields(
                idNumber = present(cid),
                thaiNationalId = present(cid),
                thaiName = present(nameTh),
                englishName = present(nameEn),
                dateOfBirth = dob,
                dateOfIssue = issued,
                dateOfExpiry = expires,
                gender = when (gender) {
                    "" -> null
                    "1" -> "M"
                    "2" -> "F"
                    else -> gender
                },
                address = present(address),
            ),
            photoBase64 = Base64.getEncoder().encodeToString(portrait.toByteArray()),
        )
    }

    /** The two-exchange field read: `61 xx`, then GET RESPONSE `base ‖ Le`, SW stripped. */
    private fun field(card: CardTransport, command: ByteArray, base: ByteArray): ByteArray {
        val ready = card.transmit(command)
        if (ready.isEmpty() || (ready[0].toInt() and 0xff) != SW1_READY) {
            throw DipChipException(DipChipException.READ_FAILED, "expected 61 XX, got ${ready.toHex()}")
        }
        val answer = card.transmit(base + command.last())
        if (answer.size < 2) throw DipChipException(DipChipException.READ_FAILED, "answer shorter than its status word")
        return answer.copyOfRange(0, answer.size - 2)
    }

    /** `prefix#first#middle#last` → `prefix+first [middle] last`. */
    fun formatName(raw: String): String {
        val parts = raw.split('#')
        fun part(i: Int) = parts.getOrElse(i) { "" }
        val middle = part(2)
        return if (middle.isEmpty()) "${part(0)}${part(1)} ${part(3)}" else "${part(0)}${part(1)} $middle ${part(3)}"
    }

    /** Part 0 (even when empty), then `" " + part` for every later non-empty part. */
    fun formatAddress(raw: String): String {
        val parts = raw.split('#')
        val out = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (part.isEmpty()) return@forEachIndexed
            if (i == 0) out.append(part) else out.append(' ').append(part)
        }
        return out.toString()
    }

    /** `YYYYMMDD` Buddhist era → `YYYY-MM-DD`, or null unless a real date. */
    fun thaiDate(raw: String): String? {
        val s = raw.trim()
        if (s.length != 8 || s.any { it !in '0'..'9' }) return null
        return try {
            LocalDate.of(s.substring(0, 4).toInt() - 543, s.substring(4, 6).toInt(), s.substring(6, 8).toInt()).toString()
        } catch (_: DateTimeException) {
            null
        }
    }
}

/**
 * TIS-620 as WHATWG windows-874 decodes it (what the Rust reader uses), for
 * the bytes a card can hold. `dip-chip-vectors.json` carries the full table.
 */
internal object Tis620 {
    /** 0x80–0x9F as windows-874: the few punctuation marks, else the C1 control. */
    private val HIGH: Map<Int, Char> = mapOf(
        0x80 to '€', 0x85 to '…', 0x91 to '‘', 0x92 to '’',
        0x93 to '“', 0x94 to '”', 0x95 to '•', 0x96 to '–', 0x97 to '—',
    )

    /** One byte → its character, or null when windows-874 has none. */
    fun char(byte: Int): Char? = when (byte) {
        in 0x00..0x7F -> byte.toChar()
        in 0x80..0x9F -> HIGH[byte] ?: byte.toChar()
        0xA0 -> '\u00A0'
        in 0xDB..0xDE, in 0xFC..0xFF -> null
        else -> (0x0E00 + (byte - 0xA0)).toChar()
    }

    fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            out.append(
                char(b.toInt() and 0xff)
                    ?: throw DipChipException(DipChipException.READ_FAILED, "unmappable TIS-620 byte"),
            )
        }
        return out.toString()
    }
}

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
