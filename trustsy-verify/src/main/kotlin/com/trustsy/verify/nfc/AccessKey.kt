package com.trustsy.verify.nfc

/**
 * The MRZ access key (BAC / PACE-MRZ) plus the server's Active Authentication
 * challenge. Validated here so a malformed key is refused before the reader
 * ever opens.
 */
internal data class ReadRequest(
    val documentNumber: String,
    /** YYMMDD. */
    val dateOfBirth: String,
    /** YYMMDD. */
    val dateOfExpiry: String,
    /** 8 bytes, chosen by the server. */
    val challenge: ByteArray,
) {
    companion object {
        private val DOCUMENT_NUMBER = Regex("^[A-Z0-9]{1,20}$")
        private val YYMMDD = Regex("^\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$")

        fun of(documentNumber: String, dateOfBirth: String, dateOfExpiry: String, challenge: ByteArray): ReadRequest? {
            val number = documentNumber.uppercase()
            if (!DOCUMENT_NUMBER.matches(number) || !YYMMDD.matches(dateOfBirth) || !YYMMDD.matches(dateOfExpiry)) {
                return null
            }
            // Doc 9303 part 11: INTERNAL AUTHENTICATE takes exactly 8 bytes.
            if (challenge.size != 8) return null
            return ReadRequest(number, dateOfBirth, dateOfExpiry, challenge)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ReadRequest && documentNumber == other.documentNumber &&
            dateOfBirth == other.dateOfBirth && dateOfExpiry == other.dateOfExpiry &&
            challenge.contentEquals(other.challenge)

    override fun hashCode(): Int =
        listOf(documentNumber, dateOfBirth, dateOfExpiry).hashCode() * 31 + challenge.contentHashCode()
}

/** The web app's `documentChip.ts` rules for what the applicant types. */
internal object Mrz {
    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** `2026-10-12` → `261012`, the MRZ date form the access key uses. */
    fun date(iso: String): String? {
        val m = ISO_DATE.matchEntire(iso.trim()) ?: return null
        val (year, month, day) = m.destructured
        return "${year.substring(2)}$month$day"
    }

    /** Upper case, no spaces or dashes: a stray space would make the chip refuse the key. */
    fun documentNumber(raw: String): String = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")

    fun complete(number: String, birth: String, expiry: String): Boolean =
        documentNumber(number).length >= 5 && date(birth) != null && date(expiry) != null
}

/** Files exactly as the chip returned them, plus the AA answer. */
internal class ChipFiles(
    val sod: ByteArray,
    val dg1: ByteArray,
    val dg2: ByteArray?,
    val dg14: ByteArray?,
    val dg15: ByteArray?,
    val aaSignature: ByteArray?,
    /** `PACE` | `BAC`. */
    val accessControl: String,
)

/** Why a read ended without files. The step maps each to copy. */
internal enum class ChipError {
    NFC_UNAVAILABLE,
    NFC_DISABLED,
    CANCELLED,
    ACCESS_DENIED,
    CONNECTION_LOST,
    READ_FAILED,
}
