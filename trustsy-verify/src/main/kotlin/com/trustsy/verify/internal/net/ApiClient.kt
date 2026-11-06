package com.trustsy.verify.internal.net

import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.flow.WireJson
import com.trustsy.verify.internal.liveness.LivenessApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A non-2xx answer from the INNER route. [code] is the server's own `{code}`:
 * a 403 can be "not your token" or "not from this country", and only the
 * second has a screen of its own.
 */
internal class ApiException(
    val status: Int,
    val code: String?,
    message: String,
    val details: JsonObject? = null,
) : IOException(message) {
    /** `location_not_allowed` / `vpn_not_allowed`: a refusal with its own screen. */
    val isAccessRefusal: Boolean
        get() = status == 403 && (code == LOCATION_NOT_ALLOWED || code == VPN_NOT_ALLOWED)

    companion object {
        const val LOCATION_NOT_ALLOWED = "location_not_allowed"
        const val VPN_NOT_ALLOWED = "vpn_not_allowed"
    }
}

/**
 * The applicant API (`sdk/contract/native-flow.md` §4), every call through
 * [SealedTransport.send]. Paths and bodies here are the envelope's contents,
 * never what appears on the wire.
 *
 * @param telemetry device info sent beside events and the liveness submit.
 */
internal class ApiClient(
    private val transport: SealedTransport,
    private val telemetry: () -> JsonObject?,
    private val uploads: OkHttpClient = uploadHttp,
) : LivenessApi {

    suspend fun snapshot(): Snapshot = call("GET", "/api/v1/collection-flow", null, Snapshot.serializer())

    suspend fun event(type: String, payload: JsonElement? = null): Snapshot =
        post("/api/v1/collection-flow/events", EventBody(type, payload, safeTelemetry()))

    suspend fun upload(body: FileUpload): FileRef =
        call("POST", "/api/v1/collection-flow/files", encode(body), FileRef.serializer(), uploads)

    suspend fun sendOtp(phone: String): PhoneSent =
        post("/api/v1/collection-flow/phone/send", JsonObject(mapOf("phone" to JsonPrimitive(phone))))

    suspend fun verifyOtp(code: String): PhoneVerdict =
        post("/api/v1/collection-flow/phone/verify", JsonObject(mapOf("code" to JsonPrimitive(code))))

    /**
     * Pair this session with the phone's FCM registration token
     * (`POST /collection-flow/push-token`); 204, no body. Re-sending refreshes it.
     */
    suspend fun registerPushToken(token: String) {
        val body = JsonObject(
            mapOf("provider" to JsonPrimitive("fcm"), "platform" to JsonPrimitive("android"), "token" to JsonPrimitive(token)),
        )
        val inner = transport.send("POST", "/api/v1/collection-flow/push-token", body)
        if (inner.status !in 200..299) throw toError(inner)
    }

    suspend fun resendOtp(): PhoneSent = post("/api/v1/collection-flow/phone/resend", JsonObject(emptyMap()))

    suspend fun chipChallenge(): ChipChallenge =
        post("/api/v1/collection-flow/document-chip/challenge", JsonObject(emptyMap()))

    suspend fun submitChip(body: ChipSubmission): Snapshot = post("/api/v1/collection-flow/document-chip", body)

    /** The dip-chip read, sealed to the chip key (`dip-chip.md`); [caseId] is the snapshot's id. */
    suspend fun submitDipChip(caseId: String, body: com.trustsy.verify.internal.dipchip.DipChipSubmission): Snapshot =
        call("POST", "/api/v1/flow/$caseId/collection/chip", encode(body), Snapshot.serializer())

    /**
     * ALWAYS a body: the server parses a declared JSON body, so an empty one
     * would be a 400. `{}` means "no options"; `{flash:false}` declines the flash.
     */
    override suspend fun startLiveness(flash: Boolean): LivenessSession =
        post("/api/v1/collection-flow/liveness-session", StartLiveness(if (flash) null else false))

    override suspend fun submitLivenessStep(body: StepSubmission): LivenessSession =
        post("/api/v1/collection-flow/liveness-session/step", body)

    suspend fun submitLiveness(caseId: String, body: LivenessSubmission): Snapshot =
        call(
            "POST",
            "/api/v1/flow/$caseId/collection/liveness",
            encode(body.copy(telemetry = safeTelemetry())),
            Snapshot.serializer(),
        )

    // ---- plumbing ----------------------------------------------------------

    private fun safeTelemetry(): JsonObject? = runCatching { telemetry() }.getOrNull()

    private inline fun <reified B> encode(body: B): JsonElement = WireJson.encodeToJsonElement(serializer<B>(), body)

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        call("POST", path, encode(body), serializer<T>())

    private suspend fun <T> call(
        method: String,
        path: String,
        body: JsonElement?,
        decoder: KSerializer<T>,
        client: OkHttpClient? = null,
    ): T {
        val inner = if (client == null) transport.send(method, path, body) else transport.send(method, path, body, client)
        if (inner.status !in 200..299) throw toError(inner)
        val json = inner.body ?: throw IOException("empty answer from $path")
        return try {
            WireJson.decodeFromJsonElement(decoder, json)
        } catch (e: IllegalArgumentException) {
            throw IOException("unreadable answer from $path", e)
        }
    }

    companion object {
        /** Base clients. Every call is sealed; these only carry the bytes. */
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Evidence uploads are up to ~22 MB sealed. */
        val uploadHttp: OkHttpClient = defaultHttp.newBuilder()
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun toError(inner: InnerResponse): ApiException {
            val body = (inner.body as? JsonObject)?.let {
                runCatching { WireJson.decodeFromJsonElement(ErrorBody.serializer(), it) }.getOrNull()
            }
            return ApiException(inner.status, body?.code, body?.message ?: "HTTP ${inner.status}", body?.details)
        }
    }
}
