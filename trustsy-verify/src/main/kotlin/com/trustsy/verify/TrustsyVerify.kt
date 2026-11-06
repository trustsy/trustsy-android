package com.trustsy.verify

import com.trustsy.verify.internal.flow.FlowLink
import com.trustsy.verify.internal.net.Sealed
import java.net.URI

/**
 * trustsy Verify for Android: runs a verification flow — the same flow the web
 * verify app runs, built once in the flow builder — with native Compose screens,
 * and reads ePassport / eID chips over NFC.
 *
 * ```kotlin
 * private val verify = registerForActivityResult(TrustsyVerifyContract()) { result -> … }
 * // link: what your backend got from POST /api/v1/cases/{id}/collection-flow-url
 * TrustsyVerify.Session.fromLink(link, sealingKey = BuildConfig.TRUSTSY_SEALING_KEY)?.let(verify::launch)
 * ```
 *
 * Or embed the flow in your own UI with [TrustsyVerifyFlow].
 *
 * The result is for your UI only; the decision of record reaches your backend by
 * webhook.
 */
object TrustsyVerify {
    const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    /**
     * Hand the SDK your app's Firebase Cloud Messaging registration token, so a
     * message from the reviewing team can notify the applicant while your app is in
     * the background. Call it from `FirebaseMessagingService.onNewToken` (and with
     * `FirebaseMessaging.getInstance().token` at start) — before or during a
     * verification; the SDK pairs it with the session while one is active and again
     * whenever it changes. `null` stops pairing new sessions.
     *
     * The token is an address, not a secret: the notification carries no message
     * text (that stays inside the sealed transport), only that a message is waiting.
     */
    @JvmStatic
    fun setPushToken(token: String?) {
        com.trustsy.verify.internal.net.PushPairing.token.value = token?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Where the flow's API lives, the applicant's bearer token, and the server
     * key every call is sealed to.
     *
     * @param apiBaseUrl the origin; every call is sealed to `{apiBaseUrl}/api/v1/sealed`.
     * @param token the applicant token (a UUID) from `POST /api/v1/cases/{id}/tokens`.
     *   It travels only inside the sealed envelope, never in a header or URL.
     * @param sealingKey the server's key configs (base64url, `sealed-transport.md`):
     *   the X-Wing one and the X25519 one, separated by `,`. Android seals with
     *   X-Wing; the X25519 entry is for the iOS builds of the same app. Pin it in
     *   your app's build config; NEVER fetch it at runtime — a key fetched over
     *   an intercepted channel is the interceptor's key.
     * @param language `en` or `th`; null = the link's language, else the device's.
     * @param allowInsecure accept an `http://` [apiBaseUrl]. Local development only.
     * @param terminal set when this device is a partner terminal with a USB card
     *   reader; without it a dip-chip step ends the run with `unsupportedStep`.
     */
    data class Session(
        val apiBaseUrl: String,
        val token: String,
        val sealingKey: String,
        val language: String? = null,
        val allowInsecure: Boolean = false,
        val terminal: Terminal? = null,
    ) {
        /** [apiBaseUrl] without a trailing slash. */
        internal val origin: String get() = apiBaseUrl.trimEnd('/')

        internal val keyConfig: Sealed.KeyConfig? get() = Sealed.KeyConfig.select(sealingKey)

        /** False for anything the flow refuses to call (`invalid_link`). */
        internal val isUsable: Boolean
            get() {
                val scheme = runCatching { URI(origin).scheme?.lowercase() }.getOrNull()
                val schemeOk = scheme == "https" || (allowInsecure && scheme == "http")
                return schemeOk && FlowLink.isToken(token)
            }

        override fun toString(): String =
            "Session(apiBaseUrl=$apiBaseUrl, language=$language, terminal=${terminal?.terminalId})"

        companion object {
            /**
             * Read the applicant link the console shows (`https://…/u/<code>`) or
             * the older `https://…/verify?token=<uuid>`. The API base is the
             * link's origin. Null for anything else, including `/resume/…`
             * handoff links, which the SDK does not accept. The sealing key is
             * checked when the flow starts (`invalid_sealing_key`), not here.
             */
            @JvmStatic
            @JvmOverloads
            fun fromLink(
                link: String,
                sealingKey: String,
                language: String? = null,
                allowInsecure: Boolean = false,
                terminal: Terminal? = null,
            ): Session? {
                val parsed = FlowLink.parse(link) ?: return null
                val session = Session(parsed.origin, parsed.token, sealingKey, language, allowInsecure, terminal)
                return session.takeIf { it.isUsable }
            }
        }
    }

    /**
     * This device is a partner terminal: a branch tablet or a counter phone with
     * a USB smart card reader, reading the applicant's Thai ID card for the
     * dip-chip step (`sdk/contract/dip-chip.md`).
     *
     * @param terminalId this terminal's id, on your API key's `allowed_terminals`.
     *   Your backend must mint the applicant token WITH it
     *   (`POST /api/v1/cases/{id}/tokens {"terminal_id": …}`); the server refuses
     *   a read whose terminal is not the token's.
     * @param chipKey the server's chip key: base64 of 32 raw X25519 bytes
     *   (`config.chip`; the kiosk's `KIOSK_CHIP_PUBLICKEY`). Pin it in your build
     *   config, like [Session.sealingKey]; it is a different key.
     * @param chipKeyId its rotation epoch (`KIOSK_CHIP_KEYID`), 0–255.
     */
    data class Terminal(
        val terminalId: String,
        val chipKey: String,
        val chipKeyId: Int,
    )
}
