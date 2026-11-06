package com.trustsy.verify.internal.ui.screens

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import android.util.Base64
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.trustsy.verify.R
import com.trustsy.verify.TrustsyVerify
import com.trustsy.verify.internal.flow.Flow
import com.trustsy.verify.internal.net.ActiveAuthentication
import com.trustsy.verify.internal.net.ApiException
import com.trustsy.verify.internal.net.ChipReaderInfo
import com.trustsy.verify.internal.net.ChipSubmission
import com.trustsy.verify.internal.net.SealedTransportException
import com.trustsy.verify.internal.ui.ErrorText
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.SecondaryButton
import com.trustsy.verify.internal.ui.StepScaffold
import com.trustsy.verify.internal.ui.findActivity
import com.trustsy.verify.nfc.ChipError
import com.trustsy.verify.nfc.ChipFiles
import com.trustsy.verify.nfc.ChipReader
import com.trustsy.verify.nfc.Mrz
import com.trustsy.verify.nfc.ReadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.coroutines.resume

/**
 * An ePassport / eID chip (ICAO 9303; web `DocumentChipStep.tsx`). The
 * applicant types the MRZ access key, the phone reads the chip's files and
 * answers the SERVER's Active Authentication challenge, and the raw files go
 * to the server, which verifies them and fires `DOCUMENT_CHIP_READ` itself.
 */
@Composable
internal fun ChipScreen(controller: FlowController) {
    val host = LocalHostContext.current
    val activity = remember(host) { host.findActivity() }
    val adapter = remember(host) { NfcAdapter.getDefaultAdapter(host) }
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf<Job?>(null) }
    var tagSeen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }

    fun skip(reason: String) = controller.event(
        Flow.DOCUMENT_CHIP_UNAVAILABLE_EVENT,
        buildJsonObject { put("reason", reason) },
    )

    if (adapter == null || activity == null) {
        StepScaffold(
            title = stringResource(R.string.trustsy_chip_title),
            body = stringResource(R.string.trustsy_chip_no_nfc),
            actions = {
                controller.actionError?.let { ErrorText(stringResource(R.string.trustsy_error)) }
                PrimaryButton(stringResource(R.string.trustsy_chip_continue_without), onClick = { skip("no_nfc") }, busy = controller.busy)
            },
        )
        return
    }

    fun scan() {
        problem = null
        if (!adapter.isEnabled) {
            problem = R.string.trustsy_chip_error_nfc_disabled
            return
        }
        reading = scope.launch {
            try {
                val challenge = controller.api.chipChallenge()
                val bytes = Base64.decode(challenge.challenge, Base64.DEFAULT)
                val request = ReadRequest.of(Mrz.documentNumber(number), Mrz.date(birth)!!, Mrz.date(expiry)!!, bytes)
                    ?: run { problem = R.string.trustsy_chip_error_access_denied; return@launch }
                tagSeen = false
                val tag = awaitTag(activity, adapter)
                tagSeen = true
                val files = withContext(Dispatchers.IO) { ChipReader().read(tag, request) }
                controller.advance { controller.api.submitChip(files.toSubmission(challenge.challengeId)) }
            } catch (e: ChipReader.ReadFailure) {
                problem = messageFor(e.code)
            } catch (e: SealedTransportException) {
                controller.fail(e)
            } catch (e: ApiException) {
                problem = R.string.trustsy_chip_error_generic
            } catch (e: IOException) {
                problem = R.string.trustsy_chip_error_generic
            } finally {
                adapter.disableReaderMode(activity)
                reading = null
            }
        }
    }

    StepScaffold(
        title = stringResource(R.string.trustsy_chip_title),
        body = stringResource(R.string.trustsy_chip_intro),
        actions = {
            problem?.let { ErrorText(stringResource(it)) }
            if (problem == R.string.trustsy_chip_error_nfc_disabled) {
                SecondaryButton(stringResource(R.string.trustsy_open_settings), onClick = {
                    runCatching { host.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
            controller.actionError?.let { ErrorText(stringResource(R.string.trustsy_error)) }
            PrimaryButton(
                if (reading != null) stringResource(R.string.trustsy_chip_scanning) else stringResource(R.string.trustsy_chip_scan),
                onClick = ::scan,
                enabled = Mrz.complete(number, birth, expiry) && reading == null,
                busy = controller.busy,
            )
            SecondaryButton(
                stringResource(R.string.trustsy_chip_continue_without),
                onClick = { skip("declined") },
                enabled = reading == null && !controller.busy,
            )
        },
    ) {
        TextField(stringResource(R.string.trustsy_chip_document_number), number, { number = it.uppercase().take(20) }, placeholder = "AA1234567")
        DateField(stringResource(R.string.trustsy_chip_date_of_birth), birth, "") { birth = it }
        DateField(stringResource(R.string.trustsy_chip_date_of_expiry), expiry, "") { expiry = it }
    }

    if (reading != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.trustsy_chip_title)) },
            text = { Text(stringResource(if (tagSeen) R.string.trustsy_nfc_reading else R.string.trustsy_nfc_hold)) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { reading?.cancel() }) { Text(stringResource(R.string.trustsy_cancel)) }
            },
        )
    }
}

/** Reader mode until one ISO-DEP tag arrives; cancelling turns it off. */
private suspend fun awaitTag(activity: Activity, adapter: NfcAdapter): Tag = suspendCancellableCoroutine { cont ->
    adapter.enableReaderMode(
        activity,
        { tag -> if (cont.isActive) cont.resume(tag) },
        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        null,
    )
    cont.invokeOnCancellation { activity.runOnUiThread { adapter.disableReaderMode(activity) } }
}

private fun ChipFiles.toSubmission(challengeId: String): ChipSubmission {
    fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return ChipSubmission(
        sod = b64(sod),
        dg1 = b64(dg1),
        dg2 = dg2?.let(::b64),
        dg14 = dg14?.let(::b64),
        dg15 = dg15?.let(::b64),
        activeAuthentication = aaSignature?.let { ActiveAuthentication(challengeId, b64(it)) },
        reader = ChipReaderInfo(
            platform = "android",
            sdk = "trustsy-android",
            sdkVersion = TrustsyVerify.SDK_VERSION,
            accessControl = accessControl,
            // Its proof is a session only this device sees; the server cannot check it.
            chipAuthentication = "not_performed",
        ),
    )
}

private fun messageFor(code: ChipError): Int? = when (code) {
    ChipError.CANCELLED -> null
    ChipError.ACCESS_DENIED -> R.string.trustsy_chip_error_access_denied
    ChipError.NFC_DISABLED -> R.string.trustsy_chip_error_nfc_disabled
    ChipError.NFC_UNAVAILABLE -> R.string.trustsy_chip_no_nfc
    ChipError.CONNECTION_LOST -> R.string.trustsy_chip_error_lost
    ChipError.READ_FAILED -> R.string.trustsy_chip_error_generic
}
