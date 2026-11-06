package com.trustsy.verify.internal.net

import com.trustsy.verify.TrustsyResult
import com.trustsy.verify.internal.flow.WireJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * A failure of the sealed channel itself, not of the call inside it. The flow
 * ends with `error(resultCode)` (contract §5): retrying cannot fix a rotated
 * key, a wrong clock, or an answer that did not come from the server.
 */
internal class SealedTransportException(val resultCode: String, message: String) : IOException(message)

/** The inner route's answer, exactly as the gateway sealed it. */
@Serializable
internal data class InnerResponse(
    val status: Int,
    @SerialName("content_type") val contentType: String? = null,
    val body: JsonElement? = null,
    @SerialName("body_base64") val bodyBase64: String? = null,
)

@Serializable
private data class Envelope(
    val v: Int,
    val ts: Long,
    val method: String,
    val path: String,
    val token: String,
    val body: JsonElement? = null,
)

/**
 * THE transport. Every applicant call — every screen, every upload — is sealed
 * here and sent to `POST {origin}/api/v1/sealed`. There is no plaintext path:
 * the token, the path and every byte of the body are inside the envelope.
 */
internal class SealedTransport(
    private val origin: String,
    private val token: String,
    private val key: Sealed.KeyConfig,
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    /** [userAgent]; null leaves OkHttp's own `okhttp/<version>`, which the server reads as a script. */
    private val userAgent: String? = null,
) {
    /**
     * @param method `GET` or `POST`.
     * @param path an applicant path, e.g. `/api/v1/collection-flow/events`.
     * @param body the JSON body; must be null for GET.
     * @param client the HTTP client (uploads pass one with longer timeouts).
     */
    suspend fun send(method: String, path: String, body: JsonElement?, client: OkHttpClient = http): InnerResponse {
        val envelope = Envelope(1, clock(), method, path, token, if (method == "GET") null else body)
        // Sealed fresh on every call: the server refuses a repeated `enc`.
        val sealed = Sealed.sealRequest(key, WireJson.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8))
        val request = Request.Builder()
            .url("$origin/api/v1/sealed")
            .post(sealed.body.toRequestBody(SEALED))
            .apply { userAgent?.let { header("User-Agent", it) } }
            .build()
        client.newCall(request).await().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (response.code != 200) throw outerError(response.code, bytes)
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
            if (contentType != Sealed.CONTENT_TYPE) {
                throw SealedTransportException(SEALED_RESPONSE_INVALID, "unsealed 200 ($contentType)")
            }
            val plain = try {
                Sealed.openResponse(sealed.secret, sealed.enc, bytes)
            } catch (e: Sealed.InvalidResponse) {
                throw SealedTransportException(SEALED_RESPONSE_INVALID, e.message ?: "did not open")
            }
            val inner = try {
                WireJson.decodeFromString(InnerResponse.serializer(), plain.toString(Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                throw SealedTransportException(SEALED_RESPONSE_INVALID, "inner answer unreadable")
            }
            innerRefusal(inner)?.let { throw it }
            return inner
        }
    }

    companion object {
        private val SEALED = Sealed.CONTENT_TYPE.toMediaType()

        /**
         * `TrustsyVerify/<sdk> (<model>; Android <version>)` — the shape the server reads
         * (`request_context::agent::SDK_MARK`). `(`, `)` and `;` are dropped from each part,
         * since `Build.MODEL` is the maker's free text and one of them would end the model early.
         */
        fun userAgent(model: String, osVersion: String, sdkVersion: String): String {
            fun clean(s: String) = s.filterNot { it == '(' || it == ')' || it == ';' || it.isISOControl() }.trim()
            return "TrustsyVerify/${clean(sdkVersion)} (${clean(model)}; Android ${clean(osVersion)})"
        }
        const val SEALED_RESPONSE_INVALID = "sealed_response_invalid"
        const val SEALING_KEY_REJECTED = "sealing_key_rejected"
        const val CLOCK_SKEW = "clock_skew"

        /** A plain answer from the gateway: the request never opened. */
        fun outerError(status: Int, bytes: ByteArray): IOException {
            val code = runCatching {
                WireJson.decodeFromString(ErrorBody.serializer(), bytes.toString(Charsets.UTF_8)).code
            }.getOrNull()
            return when {
                status == 422 && code == "sealed_unknown_key" ->
                    SealedTransportException(SEALING_KEY_REJECTED, "the pinned sealing key was rotated out")
                code != null && code.startsWith("sealed_") ->
                    SealedTransportException(code, "sealed gateway refused the request ($status $code)")
                // Anything else in front of the gateway (a proxy, a CDN error page)
                // carries no data and is a network failure.
                else -> IOException("sealed gateway HTTP $status")
            }
        }

        /** A refusal by the gateway AFTER the request opened, carried sealed. */
        fun innerRefusal(inner: InnerResponse): SealedTransportException? {
            val code = (inner.body as? kotlinx.serialization.json.JsonObject)
                ?.get("code")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: return null
            if (!code.startsWith("sealed_")) return null
            return if (code == "sealed_stale") {
                SealedTransportException(CLOCK_SKEW, "the device clock is more than 5 minutes off")
            } else {
                SealedTransportException(code, "sealed gateway refused the request (${inner.status} $code)")
            }
        }

        /** Result codes the flow may end with for a transport failure (contract §5). */
        fun resultCodeOf(e: SealedTransportException): String = when (e.resultCode) {
            SEALING_KEY_REJECTED, SEALED_RESPONSE_INVALID, CLOCK_SKEW -> e.resultCode
            else -> TrustsyResult.NETWORK
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
}
