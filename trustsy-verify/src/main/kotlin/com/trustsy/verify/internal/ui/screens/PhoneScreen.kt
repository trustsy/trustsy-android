package com.trustsy.verify.internal.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.trustsy.verify.R
import com.trustsy.verify.internal.flow.Flow
import com.trustsy.verify.internal.flow.Phone
import com.trustsy.verify.internal.net.ApiException
import com.trustsy.verify.internal.net.PhoneSent
import com.trustsy.verify.internal.net.SealedTransportException
import com.trustsy.verify.internal.ui.ErrorText
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.LinkButton
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.StepScaffold
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Proving control of a mobile number (web `PhoneStep.tsx`). A WRONG code is a
 * 200 with `verified: false`; only after `verified: true` does
 * `PHONE_VERIFIED` fire.
 */
@Composable
internal fun PhoneScreen(controller: FlowController) {
    val context = LocalHostContext.current
    val scope = rememberCoroutineScope()
    val countries = remember(context) { Countries.all(context) }
    var country by remember { mutableStateOf(countries.firstOrNull { it.code == Phone.DEFAULT_COUNTRY } ?: countries.first()) }
    var national by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf<PhoneSent?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun attempt(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: SealedTransportException) {
                controller.fail(e)
            } catch (e: ApiException) {
                // The server already holds a verified number: the step is done.
                if (e.code == "already_verified") controller.event(Flow.PHONE_VERIFIED_EVENT) else error = messageFor(e)
            } catch (_: IOException) {
                error = R.string.trustsy_phone_error_generic
            } finally {
                busy = false
            }
        }
    }

    val current = sent
    if (current == null) {
        val e164 = Phone.toE164(country.dial, national)
        StepScaffold(
            title = stringResource(R.string.trustsy_phone_title),
            body = stringResource(R.string.trustsy_phone_intro),
            actions = {
                error?.let { ErrorText(stringResource(it)) }
                PrimaryButton(
                    if (busy) stringResource(R.string.trustsy_phone_sending) else stringResource(R.string.trustsy_phone_send),
                    onClick = {
                        if (!Phone.looksLikeE164(e164)) {
                            error = R.string.trustsy_phone_error_bad_number
                        } else {
                            attempt { sent = controller.api.sendOtp(e164); code = "" }
                        }
                    },
                    enabled = e164.isNotEmpty(),
                    busy = busy || controller.busy,
                )
            },
        ) {
            PhoneField(
                label = stringResource(R.string.trustsy_phone_number_label),
                country = country,
                national = national,
                placeholder = stringResource(R.string.trustsy_details_placeholder_phone),
                onCountry = { country = it },
                onNational = { national = it.take(32) },
                enabled = !busy,
            )
        }
        return
    }

    StepScaffold(
        title = stringResource(R.string.trustsy_phone_code_title),
        body = stringResource(R.string.trustsy_phone_sent_to, current.phoneMasked),
        actions = {
            error?.let { ErrorText(stringResource(it)) }
            PrimaryButton(
                if (busy) stringResource(R.string.trustsy_phone_checking) else stringResource(R.string.trustsy_phone_confirm),
                onClick = {
                    attempt {
                        val verdict = controller.api.verifyOtp(code.trim())
                        if (verdict.verified) {
                            controller.event(Flow.PHONE_VERIFIED_EVENT)
                        } else {
                            error = verdictMessage(verdict.status)
                            // The session is gone, not merely unsatisfied: ask for a new code.
                            if (verdict.status in Phone.TERMINAL_VERDICTS) sent = null
                        }
                    }
                },
                enabled = code.isNotBlank(),
                busy = busy || controller.busy,
            )
            LinkButton(stringResource(R.string.trustsy_phone_resend)) {
                attempt { sent = controller.api.resendOtp(); code = "" }
            }
            LinkButton(stringResource(R.string.trustsy_phone_change_number)) {
                sent = null
                error = null
            }
        },
    ) {
        if (current.refCode.isNotEmpty()) {
            Text(stringResource(R.string.trustsy_phone_ref, current.refCode), style = MaterialTheme.typography.bodyMedium)
        }
        TextField(stringResource(R.string.trustsy_phone_code_label), code, { code = it.filter(Char::isLetterOrDigit).take(16) }, keyboard = KeyboardType.Number, enabled = !busy)
    }
}

/** What the applicant can DO about it (web `phoneMessages.ts::messageKeyFor`). */
private fun messageFor(e: ApiException): Int = when {
    e.code == "invalid_phone" -> R.string.trustsy_phone_error_bad_number
    e.code == "already_verified" -> R.string.trustsy_phone_error_already_done
    e.code == "no_pending_verification" -> R.string.trustsy_phone_error_no_pending
    e.status == 429 -> R.string.trustsy_phone_error_too_many
    e.status >= 500 -> R.string.trustsy_phone_error_unavailable
    else -> R.string.trustsy_phone_error_generic
}

private fun verdictMessage(status: String): Int = when (status) {
    "expired" -> R.string.trustsy_phone_error_expired
    "locked" -> R.string.trustsy_phone_error_locked
    "superseded", "not_found" -> R.string.trustsy_phone_error_no_pending
    else -> R.string.trustsy_phone_error_wrong_code
}
