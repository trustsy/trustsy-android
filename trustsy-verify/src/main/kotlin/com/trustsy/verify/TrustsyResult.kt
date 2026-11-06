package com.trustsy.verify

import android.content.Intent

/**
 * How a verification run ended (`sdk/contract/native-flow.md` §5). Exactly one
 * per run.
 *
 * For your UI only: the decision of record reaches your backend by webhook.
 * Never grant anything on this value alone.
 */
sealed interface TrustsyResult {

    /** A terminal screen, or the "submitted" screen the applicant closed. */
    data class Completed(val outcome: Outcome) : TrustsyResult

    /** The applicant left before handing off. */
    data object Cancelled : TrustsyResult

    /**
     * The flow reached a step this SDK version has no native screen for
     * (`signature`, `company`, …). Send the applicant to the web link instead.
     *
     * @param screen the screen kind, e.g. `signature`.
     * @param state the server's state key, when it has one.
     */
    data class UnsupportedStep(val screen: String, val state: String?) : TrustsyResult

    /**
     * @param code [INVALID_LINK], [INVALID_SEALING_KEY], [SEALING_KEY_REJECTED]
     *   (the pinned key was rotated out: ship the new one), [SEALED_RESPONSE_INVALID]
     *   (an answer that did not come from the server), [CLOCK_SKEW] (the device
     *   clock is more than 5 minutes off), [UNAUTHORIZED] (401/404 on the first
     *   load: an expired or unknown token) or [NETWORK].
     */
    data class Error(val code: String, val message: String) : TrustsyResult

    enum class Outcome(val wire: String) {
        APPROVED("approved"),
        REJECTED("rejected"),
        COMPLETED("completed"),
        FAILED("failed"),
        /** Submitted; a reviewer decides. */
        REVIEW("review");

        companion object {
            fun fromWire(value: String?): Outcome? = entries.firstOrNull { it.wire == value }
        }
    }

    companion object {
        const val INVALID_LINK = "invalid_link"
        const val INVALID_SEALING_KEY = "invalid_sealing_key"
        const val SEALING_KEY_REJECTED = "sealing_key_rejected"
        const val SEALED_RESPONSE_INVALID = "sealed_response_invalid"
        const val CLOCK_SKEW = "clock_skew"
        const val UNAUTHORIZED = "unauthorized"
        const val NETWORK = "network"
        /** [TrustsyVerify.Terminal.chipKey] is not 32 base64 bytes, or its id is not 0–255. */
        const val INVALID_CHIP_KEY = "invalid_chip_key"
        /** The server refused this terminal's card read: the token was not minted for its terminal id. */
        const val TERMINAL_NOT_AUTHORIZED = "terminal_not_authorized"

        private const val EXTRA_KIND = "com.trustsy.verify.RESULT_KIND"
        private const val EXTRA_A = "com.trustsy.verify.RESULT_A"
        private const val EXTRA_B = "com.trustsy.verify.RESULT_B"

        internal fun toIntent(result: TrustsyResult): Intent {
            val intent = Intent()
            when (result) {
                is Completed -> intent.putExtra(EXTRA_KIND, "completed").putExtra(EXTRA_A, result.outcome.wire)
                Cancelled -> intent.putExtra(EXTRA_KIND, "cancelled")
                is UnsupportedStep ->
                    intent.putExtra(EXTRA_KIND, "unsupported").putExtra(EXTRA_A, result.screen)
                        .putExtra(EXTRA_B, result.state)
                is Error -> intent.putExtra(EXTRA_KIND, "error").putExtra(EXTRA_A, result.code)
                    .putExtra(EXTRA_B, result.message)
            }
            return intent
        }

        /** Missing or unreadable data reads as [Cancelled]: the applicant got no outcome. */
        internal fun fromIntent(intent: Intent?): TrustsyResult {
            val a = intent?.getStringExtra(EXTRA_A)
            val b = intent?.getStringExtra(EXTRA_B)
            return when (intent?.getStringExtra(EXTRA_KIND)) {
                "completed" -> Outcome.fromWire(a)?.let(::Completed) ?: Cancelled
                "unsupported" -> UnsupportedStep(a ?: "", b)
                "error" -> Error(a ?: NETWORK, b ?: "")
                else -> Cancelled
            }
        }
    }
}
