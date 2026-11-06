package com.trustsy.verify.internal.dipchip

/**
 * USB CCID (smart-card class 0x0B) messages over a bulk pipe — what a PC/SC
 * service does for the desktop SDK, and Android has none. Pure: [BulkPipe] is
 * the only seam, so the framing runs on the JVM against a scripted reader
 * (`sdk/contract/dip-chip.md` "Android (USB CCID)").
 */
internal class Ccid(private val pipe: BulkPipe, private val slot: Int = 0) {

    /** One direction each; [read] returns one USB packet (empty on timeout). */
    interface BulkPipe {
        fun write(bytes: ByteArray)
        fun read(timeoutMs: Int): ByteArray
    }

    enum class IccStatus { ACTIVE, INACTIVE, ABSENT }

    private var seq = 0

    /** Power the card and return its ATR. */
    fun powerOn(): ByteArray = exchange(PC_TO_RDR_ICC_POWER_ON, ByteArray(0), RDR_TO_PC_DATA_BLOCK).data

    /** One APDU (or TPDU — see the contract) → the card's answer. */
    fun transmit(command: ByteArray): ByteArray = exchange(PC_TO_RDR_XFR_BLOCK, command, RDR_TO_PC_DATA_BLOCK).data

    /** Some readers answer an empty slot as a FAILED command with ICC absent; both are [IccStatus.ABSENT]. */
    fun status(): IccStatus = try {
        iccStatus(exchange(PC_TO_RDR_GET_SLOT_STATUS, ByteArray(0), RDR_TO_PC_SLOT_STATUS).status)
    } catch (e: DipChipException) {
        if (e.code == DipChipException.NO_CARD) IccStatus.ABSENT else throw e
    }

    fun powerOff() {
        exchange(PC_TO_RDR_ICC_POWER_OFF, ByteArray(0), RDR_TO_PC_SLOT_STATUS)
    }

    private class Response(val status: Int, val error: Int, val data: ByteArray)

    private fun exchange(type: Int, data: ByteArray, expect: Int): Response {
        val mySeq = seq
        seq = (seq + 1) and 0xff
        val header = ByteArray(HEADER_LEN)
        header[0] = type.toByte()
        putLe32(header, 1, data.size)
        header[5] = slot.toByte()
        header[6] = mySeq.toByte()
        // [7..9]: bPowerSelect 0 = automatic voltage (power on); bBWI 0 and
        // wLevelParameter 0 (transfer); RFU otherwise.
        pipe.write(header + data)
        while (true) {
            val response = readMessage()
            if ((response[6].toInt() and 0xff) != mySeq) continue // a stale answer to an abandoned command
            if ((response[0].toInt() and 0xff) != expect) {
                throw DipChipException(DipChipException.READER_IO, "reader answered message type ${response[0]}")
            }
            val status = response[7].toInt() and 0xff
            val error = response[8].toInt() and 0xff
            when (status ushr 6) {
                COMMAND_OK -> return Response(status, error, response.copyOfRange(HEADER_LEN, response.size))
                COMMAND_TIME_EXTENSION -> continue
                else -> throw failure(type, status, error)
            }
        }
    }

    /** One whole message: header first, then `dwLength` more bytes across packets. */
    private fun readMessage(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var expected = -1
        while (expected < 0 || out.size() < expected) {
            val packet = pipe.read(READ_TIMEOUT_MS)
            if (packet.isEmpty()) throw DipChipException(DipChipException.READER_IO, "the reader did not answer")
            out.write(packet)
            if (expected < 0 && out.size() >= HEADER_LEN) {
                val bytes = out.toByteArray()
                val length = getLe32(bytes, 1)
                if (length < 0 || length > MAX_MESSAGE) throw DipChipException(DipChipException.READER_IO, "bad CCID length")
                expected = HEADER_LEN + length
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.size == expected) bytes else bytes.copyOf(expected)
    }

    private fun failure(type: Int, status: Int, error: Int): DipChipException = when {
        iccStatus(status) == IccStatus.ABSENT || error == ERROR_ICC_MUTE ->
            DipChipException(DipChipException.NO_CARD, "no card in the reader")
        else -> DipChipException(
            DipChipException.READER_IO,
            "reader refused command 0x%02x (bError 0x%02x)".format(type, error),
        )
    }

    private fun iccStatus(status: Int): IccStatus = when (status and 0x03) {
        0 -> IccStatus.ACTIVE
        1 -> IccStatus.INACTIVE
        else -> IccStatus.ABSENT
    }

    companion object {
        const val HEADER_LEN = 10
        const val PC_TO_RDR_ICC_POWER_ON = 0x62
        const val PC_TO_RDR_ICC_POWER_OFF = 0x63
        const val PC_TO_RDR_GET_SLOT_STATUS = 0x65
        const val PC_TO_RDR_XFR_BLOCK = 0x6F
        const val RDR_TO_PC_DATA_BLOCK = 0x80
        const val RDR_TO_PC_SLOT_STATUS = 0x81
        private const val COMMAND_OK = 0
        private const val COMMAND_TIME_EXTENSION = 2
        private const val ERROR_ICC_MUTE = 0xFE
        private const val READ_TIMEOUT_MS = 5_000
        /** Larger than any answer here (255 data + SW) and any CCID descriptor allows. */
        private const val MAX_MESSAGE = 65_544

        fun putLe32(b: ByteArray, at: Int, v: Int) {
            b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte(); b[at + 2] = (v ushr 16).toByte(); b[at + 3] = (v ushr 24).toByte()
        }

        fun getLe32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
                ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
    }
}

/**
 * The CCID class descriptor's exchange level (`dwFeatures`, offset 40 of the
 * 0x21 descriptor that follows a class-0x0B interface).
 */
internal object CcidDescriptor {
    private const val DESCRIPTOR_INTERFACE = 0x04
    private const val DESCRIPTOR_CCID = 0x21
    private const val CLASS_SMART_CARD = 0x0B
    private const val FEATURES_OFFSET = 40
    private const val EXCHANGE_LEVELS = 0x00070000 // TPDU | short APDU | short+extended APDU

    /**
     * True when the smart-card interface [interfaceNumber] exchanges TPDUs or
     * APDUs; false for a character-level reader; null when [raw] (the device's
     * full descriptor set) carries no CCID descriptor for it.
     */
    fun exchangesFrames(raw: ByteArray, interfaceNumber: Int): Boolean? {
        var i = 0
        var inSmartCard = false
        while (i + 1 < raw.size) {
            val length = raw[i].toInt() and 0xff
            if (length < 2 || i + length > raw.size) return null
            when (raw[i + 1].toInt() and 0xff) {
                DESCRIPTOR_INTERFACE -> inSmartCard = length >= 9 &&
                    (raw[i + 2].toInt() and 0xff) == interfaceNumber &&
                    (raw[i + 5].toInt() and 0xff) == CLASS_SMART_CARD
                DESCRIPTOR_CCID -> if (inSmartCard && length >= FEATURES_OFFSET + 4) {
                    return Ccid.getLe32(raw, i + FEATURES_OFFSET) and EXCHANGE_LEVELS != 0
                }
            }
            i += length
        }
        return null
    }
}
