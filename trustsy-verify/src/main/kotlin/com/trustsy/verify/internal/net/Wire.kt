package com.trustsy.verify.internal.net

import com.trustsy.verify.internal.liveness.ReportedFrame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Body of `POST /collection-flow/events`. `payload` and `telemetry` travel apart. */
@Serializable
internal data class EventBody(
    val type: String,
    val payload: JsonElement? = null,
    val telemetry: JsonObject? = null,
)

/** A non-2xx body: `{code, message, details?}`. */
@Serializable
internal data class ErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: JsonObject? = null,
)

@Serializable
internal data class FileUpload(
    @SerialName("content_base64") val contentBase64: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("file_name") val fileName: String,
)

@Serializable
internal data class FileRef(@SerialName("file_id") val fileId: String)

@Serializable
internal data class PhoneSent(
    @SerialName("ref_code") val refCode: String = "",
    @SerialName("phone_masked") val phoneMasked: String = "",
    val status: String = "",
)

@Serializable
internal data class PhoneVerdict(val verified: Boolean = false, val status: String = "")

@Serializable
internal data class ChipChallenge(
    @SerialName("challenge_id") val challengeId: String,
    /** Base64, 8 bytes. */
    val challenge: String,
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
internal data class ChipSubmission(
    val sod: String,
    val dg1: String,
    val dg2: String? = null,
    val dg14: String? = null,
    val dg15: String? = null,
    @SerialName("active_authentication") val activeAuthentication: ActiveAuthentication? = null,
    val reader: ChipReaderInfo? = null,
)

@Serializable
internal data class ActiveAuthentication(
    @SerialName("challenge_id") val challengeId: String,
    val signature: String,
)

@Serializable
internal data class ChipReaderInfo(
    val platform: String,
    val sdk: String,
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("access_control") val accessControl: String,
    @SerialName("chip_authentication") val chipAuthentication: String,
)

/** `POST …/liveness-session` body. `{}` = no options. */
@Serializable
internal data class StartLiveness(val flash: Boolean? = null)

/** Backend `LivenessSessionDto` (camelCase on the wire). */
@Serializable
internal data class LivenessSession(
    val sessionId: String,
    /** `active` | `passed` | `failed` | `expired`. */
    val state: String,
    val stepsTotal: Int = 0,
    val stepsDone: Int = 0,
    val policyVersion: String = "",
    val challenge: IssuedChallenge? = null,
    val rejectReason: String? = null,
)

@Serializable
internal data class IssuedChallenge(
    val challengeId: String,
    val step: Int = 0,
    val action: String,
    val expiresAt: String = "",
    /** On a `flash` step only. */
    val sequence: List<FlashSegment>? = null,
)

@Serializable
internal data class FlashSegment(
    /** `red` | `green` | `blue`. */
    val color: String,
    val ms: Int,
)

@Serializable
internal data class StepSubmission(
    val sessionId: String,
    val challengeId: String,
    val frames: List<ReportedFrame>,
)

@Serializable
internal data class ChallengeReport(
    val challenges: List<String>,
    val frames: List<ReportedFrame>,
)

/** Body of `POST /flow/{id}/collection/liveness`. */
@Serializable
internal data class LivenessSubmission(
    @SerialName("photo_file_id") val photoFileId: String,
    @SerialName("video_file_id") val videoFileId: String? = null,
    @SerialName("flash_video_file_id") val flashVideoFileId: String? = null,
    @SerialName("challenge_report") val challengeReport: ChallengeReport? = null,
    val telemetry: JsonObject? = null,
)
