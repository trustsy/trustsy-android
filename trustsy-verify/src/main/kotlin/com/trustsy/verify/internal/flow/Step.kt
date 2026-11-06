package com.trustsy.verify.internal.flow

import com.trustsy.verify.TrustsyResult.Outcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Exact ports of the web app's `step.ts::stepFor` and `screen.ts::screenFor`.
 * Pure. `sdk/contract/screen-vectors.json` pins both; `ScreenVectorsTest`
 * replays every vector against this file. Change the order of a rule here and
 * the flow shows a different screen than the browser would.
 */
internal enum class StepKind(val wire: String) {
    COLLECT("collect"),
    REVIEW("review"),
    BLOCKED("blocked"),
    TERMINAL("terminal"),
}

internal data class StepView(
    val kind: StepKind,
    val state: String?,
    val status: String,
    val tags: List<String>,
    /** Applicant-fireable events; empty except on `collect`. */
    val actions: List<String>,
    /** Set only on `terminal`; never [Outcome.REVIEW]. */
    val outcome: Outcome? = null,
)

internal sealed class Screen(val kind: String) {
    data class Terminal(val outcome: Outcome) : Screen("terminal")
    data object Blocked : Screen("blocked")
    /** An automated check: seconds long. */
    data object Checking : Screen("checking")
    data object Review : Screen("review")
    data object Consent : Screen("consent")
    data class Document(val offered: List<DocumentKind>) : Screen("document")
    data class DocumentBack(val document: DocumentKind) : Screen("documentBack")
    data object BusinessDocument : Screen("businessDocument")
    data object Company : Screen("company")
    data object KeyPeople : Screen("keyPeople")
    data object AwaitingUsers : Screen("awaitingUsers")
    data object DocumentGroups : Screen("documentGroups")
    data object Phone : Screen("phone")
    data object DocumentChip : Screen("documentChip")
    /** A Thai ID card in a USB contact reader (`sdk/contract/dip-chip.md`). */
    data object DipChip : Screen("dipChip")
    data object Signature : Screen("signature")
    data object Liveness : Screen("liveness")
    data object Details : Screen("details")
    /** No dedicated screen: one button per applicant event. */
    data class Actions(val actions: List<String>) : Screen("actions")

    /** The web `Screen` object, field for field — what the vectors compare. */
    fun toJson(): JsonObject {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("kind" to JsonPrimitive(kind))
        when (this) {
            is Terminal -> fields["outcome"] = JsonPrimitive(outcome.wire)
            is Document -> fields["offered"] = JsonArray(offered.map { JsonPrimitive(it.wire) })
            is DocumentBack -> fields["document"] = JsonPrimitive(document.wire)
            is Actions -> fields["actions"] = JsonArray(actions.map(::JsonPrimitive))
            else -> Unit
        }
        return JsonObject(fields)
    }
}

internal object Flow {
    private const val APPROVED = "approved"
    private const val REJECTED = "rejected"
    private const val MANUAL_REVIEW = "manual_review"
    private const val BLOCKED_OPERATOR = "blocked_operator"
    private const val AUTOMATED_CHECK_TAG = "pending_process"

    const val CONSENT_EVENT = "CONSENT_GIVEN"
    const val LIVENESS_EVENT = "LIVENESS_DONE"
    const val DETAILS_EVENT = "DETAILS_SUBMITTED"
    const val PHONE_VERIFIED_EVENT = "PHONE_VERIFIED"
    const val DOCUMENT_CHIP_READ_EVENT = "DOCUMENT_CHIP_READ"
    const val DOCUMENT_CHIP_UNAVAILABLE_EVENT = "DOCUMENT_CHIP_UNAVAILABLE"
    /** Fired by the SERVER when a terminal's sealed card read opens. */
    const val CHIP_READ_EVENT = "CHIP_READ"
    private const val SIGNATURE_SUBMITTED_EVENT = "SIGNATURE_SUBMITTED"
    private const val COMPANY_EVENT = "COMPANY_SUBMITTED"
    private const val KEY_PEOPLE_EVENT = "KEY_PEOPLE_SUBMITTED"
    private const val KEY_PEOPLE_DONE_EVENT = "KEY_PEOPLE_DONE"
    private const val DOCUMENTS_EVENT = "DOCUMENTS_SUBMITTED"
    private const val BUSINESS_DOC_EVENT = "BUSINESS_DOC_CAPTURED"

    /** Events an applicant can fire themselves (`step.ts::APPLICANT_EVENTS`). */
    val APPLICANT_EVENTS: Set<String> = setOf(
        CONSENT_EVENT,
        "ID_FRONT_CAPTURED",
        "ID_BACK_CAPTURED",
        "DRIVING_LICENSE_FRONT_CAPTURED",
        "DRIVING_LICENSE_BACK_CAPTURED",
        "PASSPORT_CAPTURED",
        BUSINESS_DOC_EVENT,
        LIVENESS_EVENT,
        DETAILS_EVENT,
        PHONE_VERIFIED_EVENT,
        SIGNATURE_SUBMITTED_EVENT,
        "SIGNATURE_DECLINED",
        DOCUMENT_CHIP_READ_EVENT,
        DOCUMENT_CHIP_UNAVAILABLE_EVENT,
        CHIP_READ_EVENT,
        COMPANY_EVENT,
        KEY_PEOPLE_EVENT,
        KEY_PEOPLE_DONE_EVENT,
        DOCUMENTS_EVENT,
        "SUBMIT",
    )

    fun stepFor(snapshot: Snapshot): StepView {
        val tags = snapshot.tags
        val terminal = snapshot.done ||
            snapshot.status == "completed" ||
            snapshot.status == "failed" ||
            APPROVED in tags ||
            REJECTED in tags
        if (terminal) {
            return StepView(StepKind.TERMINAL, snapshot.state, snapshot.status, tags, emptyList(), outcomeOf(snapshot))
        }
        // Before manual_review: a state can carry both; blocked is more specific.
        if (BLOCKED_OPERATOR in tags) {
            return StepView(StepKind.BLOCKED, snapshot.state, snapshot.status, tags, emptyList())
        }
        if (MANUAL_REVIEW in tags) {
            return StepView(StepKind.REVIEW, snapshot.state, snapshot.status, tags, emptyList())
        }
        val actions = snapshot.availableEvents.filter { it in APPLICANT_EVENTS }
        if (actions.isEmpty()) {
            return StepView(StepKind.REVIEW, snapshot.state, snapshot.status, tags, emptyList())
        }
        return StepView(StepKind.COLLECT, snapshot.state, snapshot.status, tags, actions)
    }

    private fun outcomeOf(snapshot: Snapshot): Outcome = when {
        APPROVED in snapshot.tags -> Outcome.APPROVED
        REJECTED in snapshot.tags -> Outcome.REJECTED
        snapshot.status == "failed" -> Outcome.FAILED
        else -> Outcome.COMPLETED
    }

    fun screenFor(step: StepView): Screen {
        when (step.kind) {
            StepKind.TERMINAL -> return Screen.Terminal(step.outcome ?: Outcome.COMPLETED)
            StepKind.BLOCKED -> return Screen.Blocked
            StepKind.REVIEW -> return if (AUTOMATED_CHECK_TAG in step.tags) Screen.Checking else Screen.Review
            StepKind.COLLECT -> Unit
        }
        val actions = step.actions
        if (CONSENT_EVENT in actions) return Screen.Consent
        val offered = Documents.offered(actions)
        if (offered.isNotEmpty()) return Screen.Document(offered)
        Documents.awaitingBack(actions)?.let { return Screen.DocumentBack(it) }
        if (COMPANY_EVENT in actions) return Screen.Company
        if (KEY_PEOPLE_EVENT in actions) return Screen.KeyPeople
        if (KEY_PEOPLE_DONE_EVENT in actions) return Screen.AwaitingUsers
        if (DOCUMENTS_EVENT in actions) return Screen.DocumentGroups
        if (BUSINESS_DOC_EVENT in actions) return Screen.BusinessDocument
        if (PHONE_VERIFIED_EVENT in actions) return Screen.Phone
        if (DOCUMENT_CHIP_READ_EVENT in actions) return Screen.DocumentChip
        if (CHIP_READ_EVENT in actions) return Screen.DipChip
        if (SIGNATURE_SUBMITTED_EVENT in actions) return Screen.Signature
        if (LIVENESS_EVENT in actions) return Screen.Liveness
        if (DETAILS_EVENT in actions) return Screen.Details
        return Screen.Actions(actions)
    }

    fun screenFor(snapshot: Snapshot): Screen = screenFor(stepFor(snapshot))

    /** Screens v1 renders natively (contract §3); everything else ends the run. */
    fun isSupported(screen: Screen): Boolean = when (screen) {
        is Screen.Signature, is Screen.Company, is Screen.KeyPeople, is Screen.AwaitingUsers,
        is Screen.DocumentGroups, is Screen.BusinessDocument -> false
        else -> true
    }
}
