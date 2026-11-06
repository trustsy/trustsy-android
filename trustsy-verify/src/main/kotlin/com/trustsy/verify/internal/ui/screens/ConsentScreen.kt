package com.trustsy.verify.internal.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.trustsy.verify.R
import com.trustsy.verify.TrustsyResult
import com.trustsy.verify.internal.flow.Flow
import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.ui.ErrorText
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.LinkButton
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.SecondaryButton
import com.trustsy.verify.internal.ui.StepScaffold
import com.trustsy.verify.internal.ui.openAppSettings
import com.trustsy.verify.internal.ui.openUrl
import kotlinx.coroutines.launch

/** trustsy's own notice (the web's `VITE_TRUSTSY_PRIVACY_URL` default). */
private const val TRUSTSY_PRIVACY_URL = "https://trustsy.io/privacy"

/**
 * The consent gate (web `ConsentStep.tsx`). Agreeing REQUIRES a fresh position:
 * the server refuses `CONSENT_GIVEN` without one, so the event is never sent
 * without it.
 */
@Composable
internal fun ConsentScreen(controller: FlowController, snapshot: Snapshot) {
    val host = LocalHostContext.current
    val scope = rememberCoroutineScope()
    var declined by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<LocationProblem?>(null) }
    val branding = snapshot.branding
    val partner = branding.partnerName ?: stringResource(R.string.trustsy_consent_partner_fallback)

    fun locate() {
        locating = true
        problem = null
        scope.launch {
            when (val outcome = Locator.current(host)) {
                is LocationOutcome.Fix -> controller.event(Flow.CONSENT_EVENT, Locator.consentPayload(outcome.location))
                is LocationOutcome.Failed -> problem = outcome.problem
            }
            locating = false
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else problem = LocationProblem.PERMISSION_DENIED
    }

    fun agree() {
        val fine = ContextCompat.checkSelfPermission(host, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(host, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) locate()
        else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    if (declined) {
        StepScaffold(
            title = stringResource(R.string.trustsy_consent_declined_title),
            body = stringResource(R.string.trustsy_consent_declined),
            actions = {
                PrimaryButton(stringResource(R.string.trustsy_back), onClick = { declined = false })
                SecondaryButton(stringResource(R.string.trustsy_close), onClick = { controller.finish(TrustsyResult.Cancelled) })
            },
        )
        return
    }

    StepScaffold(
        title = stringResource(R.string.trustsy_consent_heading, partner),
        actions = {
            problem?.let { p ->
                ErrorText(stringResource(problemText(p)))
                if (p == LocationProblem.PERMISSION_DENIED) {
                    SecondaryButton(stringResource(R.string.trustsy_open_settings), onClick = { host.openAppSettings() })
                }
            }
            controller.actionError?.let { ErrorText(stringResource(R.string.trustsy_error)) }
            PrimaryButton(
                text = when {
                    locating -> stringResource(R.string.trustsy_consent_locating)
                    problem != null -> stringResource(R.string.trustsy_retry)
                    else -> stringResource(R.string.trustsy_consent_agree)
                },
                onClick = ::agree,
                enabled = !locating,
                busy = controller.busy,
            )
            SecondaryButton(stringResource(R.string.trustsy_consent_decline), onClick = { declined = true }, enabled = !locating && !controller.busy)
        },
    ) {
        Point(stringResource(R.string.trustsy_consent_point_photo))
        Point(stringResource(R.string.trustsy_consent_point_access, partner))
        Point(stringResource(R.string.trustsy_consent_point_biometric))
        Point(stringResource(R.string.trustsy_consent_point_location))
        // Two parties are consented to, so both policies are linked; an unset
        // partner link is omitted rather than rendered dead.
        Column {
            LinkButton(stringResource(R.string.trustsy_consent_privacy)) { host.openUrl(TRUSTSY_PRIVACY_URL) }
            webUrl(branding.privacyUrl)?.let { url ->
                LinkButton(stringResource(R.string.trustsy_consent_partner_privacy, partner)) { host.openUrl(url) }
            }
            webUrl(branding.termsUrl)?.let { url ->
                LinkButton(stringResource(R.string.trustsy_consent_partner_terms, partner)) { host.openUrl(url) }
            }
        }
    }
}

@Composable
private fun Point(text: String) {
    Row(horizontalArrangement = Arrangement.Start) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Only http(s) links leave the app; anything else from the server is ignored. */
private fun webUrl(url: String?): String? =
    url?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }

private fun problemText(p: LocationProblem): Int = when (p) {
    LocationProblem.PERMISSION_DENIED -> R.string.trustsy_location_denied
    LocationProblem.LOCATION_OFF -> R.string.trustsy_location_off
    LocationProblem.UNAVAILABLE -> R.string.trustsy_location_unavailable
    LocationProblem.TIMEOUT -> R.string.trustsy_location_timeout
}
