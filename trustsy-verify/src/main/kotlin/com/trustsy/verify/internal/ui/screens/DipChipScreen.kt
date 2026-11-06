package com.trustsy.verify.internal.ui.screens

import android.hardware.usb.UsbDevice
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.trustsy.verify.R
import com.trustsy.verify.TrustsyResult
import com.trustsy.verify.internal.dipchip.Ccid
import com.trustsy.verify.internal.dipchip.ChipPayload
import com.trustsy.verify.internal.dipchip.ChipSeal
import com.trustsy.verify.internal.dipchip.CcidReaderSession
import com.trustsy.verify.internal.dipchip.DipChipException
import com.trustsy.verify.internal.dipchip.DipChipSubmission
import com.trustsy.verify.internal.dipchip.ThaiIdCard
import com.trustsy.verify.internal.dipchip.UsbCcidReader
import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.net.ApiException
import com.trustsy.verify.internal.ui.DipChipTerminal
import com.trustsy.verify.internal.ui.ErrorText
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.StepScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

private enum class Phase { NO_USB_HOST, CONNECT, PERMISSION, INSERT, READING, REMOVE }

/**
 * The dip chip (`sdk/contract/dip-chip.md`): a Thai ID card in a USB contact
 * reader on a partner terminal. The card is read here, sealed to the chip key
 * and submitted; the SERVER opens it and fires `CHIP_READ` itself. Reached only
 * with a [DipChipTerminal] — [FlowController.apply] ends the run otherwise.
 *
 * After any failed read the applicant must take the card OUT before the next
 * attempt, so a card left in the reader is never re-read in a loop.
 */
@Composable
internal fun DipChipScreen(controller: FlowController, snapshot: Snapshot) {
    val terminal = controller.terminal ?: return
    val host = LocalHostContext.current
    val reader = remember(host) { UsbCcidReader(host.applicationContext) }
    var phase by remember { mutableStateOf(if (reader.hostSupported) Phase.CONNECT else Phase.NO_USB_HOST) }
    var problem by remember { mutableStateOf<Int?>(null) }
    // Bumped by "Allow" to ask for the reader again after a refusal.
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        if (!reader.hostSupported) return@LaunchedEffect
        while (isActive) {
            val device = reader.find()
            if (device == null) {
                phase = Phase.CONNECT
                delay(POLL_MS)
                continue
            }
            if (!reader.hasPermission(device)) {
                phase = Phase.PERMISSION
                if (!reader.requestPermission(device)) return@LaunchedEffect
            }
            val session = try {
                withContext(Dispatchers.IO) { reader.open(device) }
            } catch (e: DipChipException) {
                problem = problemFor(e.code)
                if (e.code == DipChipException.READER_UNSUPPORTED) awaitDetached(reader, device)
                else delay(POLL_MS)
                continue
            }
            try {
                if (readAndSubmit(controller, terminal, snapshot, session, onPhase = { phase = it }, onProblem = { problem = it })) {
                    return@LaunchedEffect
                }
                phase = Phase.REMOVE
                while (isActive && withContext(Dispatchers.IO) { session.status() } != Ccid.IccStatus.ABSENT) delay(POLL_MS / 2)
            } catch (e: DipChipException) {
                // Mostly the reader unplugged or the card pulled mid-read.
                problem = problemFor(e.code)
            } finally {
                withContext(Dispatchers.IO) { session.close() }
            }
        }
    }

    val body = when (phase) {
        Phase.NO_USB_HOST -> stringResource(R.string.trustsy_dipchip_no_usb_host)
        Phase.CONNECT -> stringResource(R.string.trustsy_dipchip_connect_reader)
        Phase.PERMISSION -> stringResource(R.string.trustsy_dipchip_permission)
        Phase.INSERT -> stringResource(R.string.trustsy_dipchip_insert)
        Phase.READING -> stringResource(R.string.trustsy_dipchip_reading)
        Phase.REMOVE -> stringResource(R.string.trustsy_dipchip_remove)
    }
    StepScaffold(
        title = stringResource(R.string.trustsy_dipchip_title),
        body = body,
        actions = {
            problem?.let { ErrorText(stringResource(it)) }
            if (phase == Phase.PERMISSION) PrimaryButton(stringResource(R.string.trustsy_dipchip_allow), onClick = { attempt++ })
        },
    ) {
        if (phase == Phase.READING) CircularProgressIndicator()
    }
}

/** Wait for a card, read it, seal it, submit it. True once the flow has moved on. */
private suspend fun readAndSubmit(
    controller: FlowController,
    terminal: DipChipTerminal,
    snapshot: Snapshot,
    session: CcidReaderSession,
    onPhase: (Phase) -> Unit,
    onProblem: (Int?) -> Unit,
): Boolean {
    onPhase(Phase.INSERT)
    while (withContext(Dispatchers.IO) { session.status() } == Ccid.IccStatus.ABSENT) delay(POLL_MS / 2)
    onPhase(Phase.READING)
    onProblem(null)
    val payload: ChipPayload = try {
        withContext(Dispatchers.IO) { ThaiIdCard.read(session.powerOn()) }
    } catch (e: DipChipException) {
        onProblem(problemFor(e.code))
        return false
    }
    // The server's replay window is ±10 min around this; RFC 3339, seconds always present.
    val eventTime = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val envelope = withContext(Dispatchers.Default) {
        ChipSeal.seal(terminal.key, terminal.terminalId, eventTime, payload)
    }
    val moved = controller.submit {
        try {
            controller.api.submitDipChip(snapshot.id, DipChipSubmission(terminal.terminalId, eventTime, envelope))
        } catch (e: ApiException) {
            // Not the applicant's problem and not fixable by re-inserting: the
            // token was not minted for this terminal (dip-chip.md "Trust model").
            if (e.status == 403 && !e.isAccessRefusal) {
                controller.finish(TrustsyResult.Error(TrustsyResult.TERMINAL_NOT_AUTHORIZED, e.message ?: "403"))
            }
            throw e
        }
    }
    if (!moved) {
        onProblem(
            when (controller.actionError) {
                "invalid_cid", "missing_cid" -> R.string.trustsy_dipchip_error_read
                else -> R.string.trustsy_error
            },
        )
    }
    return moved
}

private suspend fun awaitDetached(reader: UsbCcidReader, device: UsbDevice) {
    while (reader.find()?.deviceId == device.deviceId) delay(POLL_MS)
}

private fun problemFor(code: String): Int = when (code) {
    DipChipException.READ_FAILED -> R.string.trustsy_dipchip_error_read
    DipChipException.READER_UNSUPPORTED -> R.string.trustsy_dipchip_error_unsupported
    else -> R.string.trustsy_dipchip_error_reader
}

private const val POLL_MS = 800L
