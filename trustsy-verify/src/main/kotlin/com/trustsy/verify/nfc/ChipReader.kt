package com.trustsy.verify.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG15File
import java.io.ByteArrayInputStream
import java.security.Security

/**
 * Reads an ICAO 9303 chip with JMRTD and returns its files untouched.
 *
 * Deliberately verifies NOTHING: the server re-checks every byte (Passive
 * Authentication, Active Authentication against its own challenge), and a
 * verdict computed on the applicant's phone would be one the applicant can
 * edit. This class only gets the bytes out of the chip faithfully.
 *
 * Runs on a background thread (it blocks on the radio).
 */
internal class ChipReader {

    class ReadFailure(val code: ChipError, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    fun read(tag: Tag, request: ReadRequest): ChipFiles {
        ensureProvider()
        val isoDep = IsoDep.get(tag) ?: throw ReadFailure(ChipError.READ_FAILED, "tag is not ISO-DEP")
        // DG2 is tens of KiB; the default 5s can expire mid-read on slow chips.
        isoDep.timeout = 10_000
        val cardService = CardService.getInstance(isoDep)
        try {
            cardService.open()
            val service = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false,
            )
            service.open()
            val key = BACKey(request.documentNumber, request.dateOfBirth, request.dateOfExpiry)

            // PACE first (mandatory on newer chips, preferred by Doc 9303),
            // BAC when the chip offers no PACE or PACE fails.
            val pace = tryPace(service, key)
            service.sendSelectApplet(pace)
            if (!pace) {
                try {
                    service.doBAC(key)
                } catch (e: CardServiceException) {
                    throw ReadFailure(ChipError.ACCESS_DENIED, "BAC refused the access key", e)
                }
            }

            val sod = readFile(service, PassportService.EF_SOD)
                ?: throw ReadFailure(ChipError.READ_FAILED, "EF.SOD unreadable")
            val dg1 = readFile(service, PassportService.EF_DG1)
                ?: throw ReadFailure(ChipError.READ_FAILED, "DG1 unreadable")
            val dg2 = readFile(service, PassportService.EF_DG2)
            val dg14 = readFile(service, PassportService.EF_DG14)
            val dg15 = readFile(service, PassportService.EF_DG15)

            // Active Authentication over the SERVER's challenge. The response is
            // returned raw; the server verifies it against DG15.
            val aa = dg15?.let { bytes ->
                try {
                    val publicKey = DG15File(ByteArrayInputStream(bytes)).publicKey
                    val (digest, signature) = aaAlgorithms(publicKey.algorithm)
                    service.doAA(publicKey, digest, signature, request.challenge).response
                } catch (e: CardServiceException) {
                    // A chip that refuses AA is reported as "not performed" by
                    // the server (no signature) — a review, never a pass.
                    null
                }
            }

            return ChipFiles(sod, dg1, dg2, dg14, dg15, aa, if (pace) "PACE" else "BAC")
        } catch (e: ReadFailure) {
            throw e
        } catch (e: TagLostException) {
            throw ReadFailure(ChipError.CONNECTION_LOST, "chip left the field", e)
        } catch (e: CardServiceException) {
            if (e.cause is TagLostException) {
                throw ReadFailure(ChipError.CONNECTION_LOST, "chip left the field", e)
            }
            throw ReadFailure(ChipError.READ_FAILED, e.message ?: "card service error", e)
        } finally {
            try {
                cardService.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun tryPace(service: PassportService, key: BACKey): Boolean {
        val cardAccess = try {
            CardAccessFile(
                service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE),
            )
        } catch (_: Exception) {
            return false
        }
        val info = cardAccess.securityInfos.filterIsInstance<PACEInfo>().firstOrNull() ?: return false
        return try {
            service.doPACE(
                key,
                info.objectIdentifier,
                PACEInfo.toParameterSpec(info.parameterId),
                null,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** The whole EF, tag included; `null` when the chip has no such file. */
    private fun readFile(service: PassportService, fid: Short): ByteArray? = try {
        service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.readBytes() }
    } catch (e: CardServiceException) {
        if (e.cause is TagLostException) throw e
        null
    }

    /**
     * JMRTD needs names for the AA call. For RSA chips this is ISO 9796-2 with
     * SHA-1; for EC chips the chip simply signs and the algorithm names are not
     * sent to it. The server decides what the answer proves either way.
     */
    private fun aaAlgorithms(keyAlgorithm: String): Pair<String, String> =
        if (keyAlgorithm.equals("RSA", ignoreCase = true)) "SHA-1" to "SHA1WithRSA/ISO9796-2"
        else "SHA-256" to "SHA256withECDSA"

    private fun ensureProvider() {
        // Android ships a stripped "BC"; JMRTD's PACE needs the full one.
        if (Security.getProvider(PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private companion object {
        const val PROVIDER_NAME = "BC"
    }
}
