package com.trustsy.verify.internal.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trustsy.verify.TrustsyResult
import com.trustsy.verify.internal.dipchip.ChipKey
import com.trustsy.verify.internal.flow.DocumentKind
import com.trustsy.verify.internal.flow.Flow
import com.trustsy.verify.internal.flow.Screen
import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.net.ApiClient
import com.trustsy.verify.internal.net.ApiException
import com.trustsy.verify.internal.net.SealedTransport
import com.trustsy.verify.internal.net.SealedTransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** [TrustsyVerify.Terminal][com.trustsy.verify.TrustsyVerify.Terminal], its key checked. */
internal class DipChipTerminal(val terminalId: String, val key: ChipKey)

/** What the flow shows at the top level. */
internal sealed interface FlowUi {
    data object Loading : FlowUi

    /** The first load failed for a reason other than the token. */
    data class LoadFailed(val message: String) : FlowUi

    /** `location_not_allowed` / `vpn_not_allowed`. */
    data class AccessRefused(val error: ApiException) : FlowUi

    data class Step(val snapshot: Snapshot, val screen: Screen) : FlowUi
}

/**
 * The snapshot → screen loop (contract §2). Owns the current snapshot, the
 * applicant's in-flow choices (document, country, guides seen), and how the
 * run ends. The SERVER decides every step; this only renders it.
 *
 * @param terminal set when the host declared this device a partner terminal;
 *   a dip-chip step without one ends the run as unsupported.
 */
internal class FlowController(
    val api: ApiClient,
    private val scope: CoroutineScope,
    val terminal: DipChipTerminal? = null,
    private val onResult: (TrustsyResult) -> Unit,
) {
    var ui: FlowUi by mutableStateOf(FlowUi.Loading)
        private set

    /** True while a call that will replace the snapshot is in flight. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** A failed act on the current screen; the screen stays. */
    var actionError: String? by mutableStateOf(null)
        private set

    /** The link's language, from `GET /collection-flow` (event answers omit it). */
    var linkLanguage: String? by mutableStateOf(null)
        private set

    // The applicant's choices, held here so going back from a guide keeps them.
    var documentKind: DocumentKind? by mutableStateOf(null)
    var documentCountry: String by mutableStateOf("TH")
    /** Guides already shown (`document`, `selfie`); observable so the screen moves on. */
    val guided = androidx.compose.runtime.mutableStateListOf<String>()

    private var loaded = false
    private var finished = false
    private var pushPairing: kotlinx.coroutines.Job? = null

    fun load() {
        ui = FlowUi.Loading
        scope.launch {
            try {
                apply(api.snapshot())
                if (!loaded) pairPush()
                loaded = true
            } catch (e: SealedTransportException) {
                fail(e)
            } catch (e: ApiException) {
                when {
                    e.isAccessRefusal -> ui = FlowUi.AccessRefused(e)
                    !loaded && (e.status == 401 || e.status == 404) ->
                        finish(TrustsyResult.Error(TrustsyResult.UNAUTHORIZED, e.message ?: "HTTP ${e.status}"))
                    else -> ui = FlowUi.LoadFailed(e.message ?: "HTTP ${e.status}")
                }
            } catch (e: IOException) {
                ui = FlowUi.LoadFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Polling on wait screens: errors are ignored, the next tick retries. */
    suspend fun refresh() {
        try {
            val next = api.snapshot()
            if (!busy) apply(next)
        } catch (e: SealedTransportException) {
            fail(e)
        } catch (_: IOException) {
        }
    }

    /** Fire an event; the answer is the next snapshot. */
    fun event(type: String, payload: JsonElement? = null) = advance { api.event(type, payload) }

    /**
     * Run a call that returns the next snapshot. Access refusals get their own
     * screen; anything else stays on this screen as [actionError].
     */
    fun advance(call: suspend () -> Snapshot) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
            try {
                submit(call)
            } finally {
                busy = false
            }
        }
    }

    /**
     * [advance]'s body, for a screen that must know the outcome before it
     * moves on (the dip-chip screen waits for the card to come out after a
     * failure). True when the snapshot was applied.
     */
    suspend fun submit(call: suspend () -> Snapshot): Boolean {
        try {
            apply(call())
            return true
        } catch (e: SealedTransportException) {
            fail(e)
        } catch (e: ApiException) {
            if (e.isAccessRefusal) ui = FlowUi.AccessRefused(e) else actionError = e.code ?: e.message
        } catch (e: IOException) {
            actionError = e.message ?: "network"
        }
        return false
    }

    /**
     * The sealed channel itself failed (a rotated key, a wrong clock, an answer
     * that did not open). Retrying cannot fix it: the run ends with the error.
     */
    fun fail(e: SealedTransportException) {
        finish(TrustsyResult.Error(SealedTransport.resultCodeOf(e), e.message ?: e.resultCode))
    }

    fun clearError() {
        actionError = null
    }

    fun apply(snapshot: Snapshot) {
        snapshot.language?.let { linkLanguage = it }
        val screen = Flow.screenFor(snapshot)
        if (!Flow.isSupported(screen) || (screen == Screen.DipChip && terminal == null)) {
            finish(TrustsyResult.UnsupportedStep(screen.kind, snapshot.state))
            return
        }
        // A new step resets per-step choices that belong to the old one.
        val previous = (ui as? FlowUi.Step)?.screen
        if (previous is Screen.Document && screen !is Screen.Document && screen !is Screen.DocumentBack) {
            documentKind = null
        }
        ui = FlowUi.Step(snapshot, screen)
    }

    fun finish(result: TrustsyResult) {
        if (finished) return
        finished = true
        pushPairing?.cancel()
        onResult(result)
    }

    /**
     * While the session is active, keep it paired with the app's push token: now,
     * and again each time the app hands the SDK a new one. Best-effort — a failed
     * pairing costs a notification, never the verification.
     */
    private fun pairPush() {
        pushPairing?.cancel()
        pushPairing = scope.launch {
            com.trustsy.verify.internal.net.PushPairing.token.collect { token ->
                if (finished || token == null) return@collect
                try {
                    api.registerPushToken(token)
                } catch (_: ApiException) {
                } catch (_: SealedTransportException) {
                } catch (_: IOException) {
                }
            }
        }
    }

    /** Back / close: what leaving NOW means for this screen. */
    fun leave() {
        val step = ui as? FlowUi.Step
        val result = when (val screen = step?.screen) {
            is Screen.Terminal -> TrustsyResult.Completed(screen.outcome)
            // Handed off: the case is with a reviewer either way.
            Screen.Review, Screen.Blocked -> TrustsyResult.Completed(TrustsyResult.Outcome.REVIEW)
            else -> TrustsyResult.Cancelled
        }
        finish(result)
    }
}
