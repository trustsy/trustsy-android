package com.trustsy.verify

import com.trustsy.verify.internal.dipchip.Ccid
import com.trustsy.verify.internal.dipchip.CcidDescriptor
import com.trustsy.verify.internal.dipchip.DipChipException
import com.trustsy.verify.internal.dipchip.hex
import com.trustsy.verify.internal.dipchip.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** CCID framing against a scripted reader (`dip-chip.md` "Android (USB CCID)"). */
class CcidTest {

    /** Records what the host wrote; answers with queued packets. */
    private class Pipe(private val packetSize: Int = 64) : Ccid.BulkPipe {
        val written = mutableListOf<ByteArray>()
        private val packets = ArrayDeque<ByteArray>()

        /** Queue a whole message, split into USB packets. */
        fun answer(type: Int, seq: Int, status: Int = 0, error: Int = 0, data: ByteArray = ByteArray(0)) {
            val m = ByteArray(10 + data.size)
            m[0] = type.toByte()
            Ccid.putLe32(m, 1, data.size)
            m[6] = seq.toByte()
            m[7] = status.toByte()
            m[8] = error.toByte()
            data.copyInto(m, 10)
            m.toList().chunked(packetSize).forEach { packets.addLast(it.toByteArray()) }
        }

        override fun write(bytes: ByteArray) {
            written += bytes
        }

        override fun read(timeoutMs: Int): ByteArray = packets.removeFirstOrNull() ?: ByteArray(0)
    }

    @Test
    fun `power on sends 62 and returns the ATR`() {
        val pipe = Pipe()
        val atr = hex("3b6800000073c84012009000")
        pipe.answer(0x80, seq = 0, data = atr)
        assertArrayEquals(atr, Ccid(pipe).powerOn())
        assertEquals("62000000000000000000", pipe.written.single().toHex())
    }

    @Test
    fun `a transfer frames the apdu and reassembles a multi-packet answer`() {
        val pipe = Pipe(packetSize = 16)
        val ccid = Ccid(pipe)
        val answer = ByteArray(257) { it.toByte() } // 255 data + SW, over 17 packets
        pipe.answer(0x80, seq = 0, data = answer)
        val apdu = hex("80b0000402000d")
        assertArrayEquals(answer, ccid.transmit(apdu))
        assertEquals("6f070000000000000000" + apdu.toHex(), pipe.written.single().toHex())
    }

    @Test
    fun `sequence numbers advance, and a time extension is waited out`() {
        val pipe = Pipe()
        val ccid = Ccid(pipe)
        pipe.answer(0x80, seq = 0, data = hex("9000"))
        ccid.transmit(hex("00a4040008a000000054480001"))
        pipe.answer(0x80, seq = 1, status = 0x80) // time extension
        pipe.answer(0x80, seq = 1, data = hex("610d"))
        assertArrayEquals(hex("610d"), ccid.transmit(hex("80b0000402000d")))
        assertEquals(1, pipe.written[1][6].toInt())
    }

    @Test
    fun `a stale answer to an earlier command is skipped`() {
        val pipe = Pipe()
        val ccid = Ccid(pipe)
        pipe.answer(0x80, seq = 7, data = hex("dead"))
        pipe.answer(0x80, seq = 0, data = hex("9000"))
        assertArrayEquals(hex("9000"), ccid.transmit(hex("00")))
    }

    @Test
    fun `slot status reads present, inactive and absent`() {
        val pipe = Pipe()
        val ccid = Ccid(pipe)
        pipe.answer(0x81, seq = 0, status = 0)
        pipe.answer(0x81, seq = 1, status = 1)
        pipe.answer(0x81, seq = 2, status = 2)
        // A reader that reports an empty slot as a FAILED command.
        pipe.answer(0x81, seq = 3, status = 0x42, error = 0xFE)
        assertEquals(Ccid.IccStatus.ACTIVE, ccid.status())
        assertEquals(Ccid.IccStatus.INACTIVE, ccid.status())
        assertEquals(Ccid.IccStatus.ABSENT, ccid.status())
        assertEquals(Ccid.IccStatus.ABSENT, ccid.status())
        assertEquals("65", pipe.written[0].copyOfRange(0, 1).toHex())
    }

    @Test
    fun `a mute card is no_card and other failures are reader_io`() {
        val pipe = Pipe()
        val ccid = Ccid(pipe)
        pipe.answer(0x80, seq = 0, status = 0x41, error = 0xFE)
        pipe.answer(0x80, seq = 1, status = 0x40, error = 0x05)
        try {
            ccid.powerOn(); fail()
        } catch (e: DipChipException) {
            assertEquals(DipChipException.NO_CARD, e.code)
        }
        try {
            ccid.transmit(hex("00")); fail()
        } catch (e: DipChipException) {
            assertEquals(DipChipException.READER_IO, e.code)
        }
    }

    @Test
    fun `a reader that never answers is reader_io, not a hang`() {
        try {
            Ccid(Pipe()).transmit(hex("00")); fail()
        } catch (e: DipChipException) {
            assertEquals(DipChipException.READER_IO, e.code)
        }
    }

    /** config(9) + interface(9, class 0x0B) + CCID(0x36) with the given dwFeatures. */
    private fun descriptors(features: Int, interfaceClass: Int = 0x0B): ByteArray {
        val config = hex("0902" + "5d00" + "0101008032")
        val iface = byteArrayOf(9, 4, 0, 0, 3, interfaceClass.toByte(), 0, 0, 0)
        val ccid = ByteArray(0x36).also { it[0] = 0x36; it[1] = 0x21; Ccid.putLe32(it, 40, features) }
        return config + iface + ccid
    }

    @Test
    fun `exchange level comes from dwFeatures`() {
        assertEquals(true, CcidDescriptor.exchangesFrames(descriptors(0x000404BE), 0)) // short APDU (ACR39U-like)
        assertEquals(true, CcidDescriptor.exchangesFrames(descriptors(0x000100BA), 0)) // TPDU
        assertEquals(false, CcidDescriptor.exchangesFrames(descriptors(0x000000BA), 0)) // character level
        assertNull(CcidDescriptor.exchangesFrames(descriptors(0x00040000, interfaceClass = 0x03), 0))
        assertNull(CcidDescriptor.exchangesFrames(descriptors(0x00040000), 1))
        assertNull(CcidDescriptor.exchangesFrames(byteArrayOf(9, 2, 0), 0)) // truncated
    }
}
