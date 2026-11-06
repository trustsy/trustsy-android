package com.trustsy.verify.internal.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import com.trustsy.verify.R
import com.trustsy.verify.TrustsyResult
import com.trustsy.verify.internal.flow.DocumentSide
import com.trustsy.verify.internal.flow.Flow
import com.trustsy.verify.internal.flow.Screen
import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.net.ApiException
import com.trustsy.verify.internal.ui.ErrorText
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.StatusScreen
import com.trustsy.verify.internal.ui.StepScaffold
import kotlinx.serialization.json.jsonPrimitive

/** One screen per [Screen] kind (contract §3). Unsupported kinds never reach here. */
@Composable
internal fun StepScreen(controller: FlowController, snapshot: Snapshot, screen: Screen) {
    // Keyed by state: a new step never inherits the previous step's local state.
    key(snapshot.state, screen.kind) {
        when (screen) {
            is Screen.Terminal -> StatusScreen(
                heading = stringResource(terminalTitle(screen.outcome)),
                body = null,
                action = stringResource(R.string.trustsy_close),
                onAction = { controller.finish(TrustsyResult.Completed(screen.outcome)) },
            )
            Screen.Review -> StatusScreen(
                heading = stringResource(R.string.trustsy_review_heading),
                body = stringResource(R.string.trustsy_review_body),
                action = stringResource(R.string.trustsy_close),
                onAction = { controller.finish(TrustsyResult.Completed(TrustsyResult.Outcome.REVIEW)) },
            )
            Screen.Blocked -> StatusScreen(
                heading = stringResource(R.string.trustsy_blocked_heading),
                body = stringResource(R.string.trustsy_blocked_body),
                action = stringResource(R.string.trustsy_close),
                onAction = { controller.finish(TrustsyResult.Completed(TrustsyResult.Outcome.REVIEW)) },
            )
            Screen.Checking -> StatusScreen(
                heading = stringResource(R.string.trustsy_checking_heading),
                body = stringResource(R.string.trustsy_checking_body),
                action = null,
                onAction = {},
                spinner = true,
            )
            Screen.Consent -> ConsentScreen(controller, snapshot)
            Screen.Details -> DetailsScreen(controller)
            is Screen.Document -> DocumentScreen(controller, screen.offered)
            is Screen.DocumentBack -> DocumentCaptureFlow(
                controller,
                kind = screen.document,
                side = DocumentSide.BACK,
                // The country rides the back only when the applicant chose it on the front.
                country = controller.documentCountry.takeIf { controller.documentKind == screen.document },
                showGuide = false,
                onChangeDocument = null,
            )
            Screen.Phone -> PhoneScreen(controller)
            Screen.DocumentChip -> ChipScreen(controller)
            Screen.DipChip -> DipChipScreen(controller, snapshot)
            Screen.Liveness -> LivenessScreen(controller, snapshot)
            is Screen.Actions -> ActionsScreen(controller, screen.actions)
            else -> Unit
        }
    }
}

private fun terminalTitle(outcome: TrustsyResult.Outcome): Int = when (outcome) {
    TrustsyResult.Outcome.APPROVED -> R.string.trustsy_terminal_approved
    TrustsyResult.Outcome.REJECTED -> R.string.trustsy_terminal_rejected
    TrustsyResult.Outcome.FAILED -> R.string.trustsy_terminal_failed
    else -> R.string.trustsy_terminal_completed
}

/** No dedicated screen: one button per applicant event, like the web app. */
@Composable
private fun ActionsScreen(controller: FlowController, actions: List<String>) {
    StepScaffold(
        title = null,
        actions = {
            controller.actionError?.let { ErrorText(stringResource(R.string.trustsy_error)) }
            for (event in actions) {
                PrimaryButton(eventLabel(event), onClick = { controller.event(event) }, busy = controller.busy)
            }
        },
    )
}

@Composable
private fun eventLabel(event: String): String = when (event) {
    "SUBMIT" -> stringResource(R.string.trustsy_event_submit)
    Flow.DOCUMENT_CHIP_UNAVAILABLE_EVENT -> stringResource(R.string.trustsy_chip_continue_without)
    else -> stringResource(R.string.trustsy_continue)
}

/** `location_not_allowed` / `vpn_not_allowed` (web `AccessRefused`). */
@Composable
internal fun AccessRefusedScreen(error: ApiException, onRetry: () -> Unit) {
    if (error.code == ApiException.VPN_NOT_ALLOWED) {
        StatusScreen(
            heading = stringResource(R.string.trustsy_access_vpn_heading),
            body = stringResource(R.string.trustsy_access_vpn_body),
            action = stringResource(R.string.trustsy_retry),
            onAction = onRetry,
        )
        return
    }
    fun detail(name: String) = runCatching { error.details?.get(name)?.jsonPrimitive?.content }.getOrNull()
        ?.takeIf { it.isNotBlank() }
    // "Region, Country" in the UI language; the region is the database's English name.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val country = detail("country_code")?.let { runCatching { java.util.Locale.Builder().setRegion(it).build() }.getOrElse { java.util.Locale.ROOT }.getDisplayCountry(locale).takeIf(String::isNotBlank) }
        ?: detail("country")
    val place = listOfNotNull(detail("region"), country).joinToString(", ").takeIf { it.isNotEmpty() }
    StatusScreen(
        heading = stringResource(R.string.trustsy_access_location_heading),
        body = if (place != null) stringResource(R.string.trustsy_access_location_body, place)
        else stringResource(R.string.trustsy_access_location_body_unknown),
        action = stringResource(R.string.trustsy_retry),
        onAction = onRetry,
    )
}
