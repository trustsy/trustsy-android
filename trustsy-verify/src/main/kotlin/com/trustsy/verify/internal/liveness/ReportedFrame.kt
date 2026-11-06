package com.trustsy.verify.internal.liveness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.hypot

/**
 * One numeric sample of the liveness run (`liveness_challenge::ReportedFrame`).
 * Numbers only: no landmarks, no pixels. A field this device cannot measure is
 * null and omitted from the wire — never a constant.
 */
@Serializable
internal data class ReportedFrame(
    /** Degrees; positive = the applicant turned to THEIR OWN LEFT. */
    val yaw: Double,
    val pitch: Double,
    val roll: Double,
    /** Face box diagonal in pixels. */
    @SerialName("face_size") val faceSize: Double,
    /** Milliseconds since the run started (monotonic). */
    @SerialName("t_ms") val tMs: Long,
    @SerialName("eye_open") val eyeOpen: Double? = null,
    val smile: Double? = null,
    @SerialName("mouth_open") val mouthOpen: Double? = null,
)

/** Head pose in degrees, sign as [ReportedFrame]. */
internal data class HeadPose(val yaw: Double, val pitch: Double, val roll: Double)

/**
 * The web app's `face-mesh-loader.ts::eulerFromMatrix`, verbatim: Tait-Bryan
 * angles from MediaPipe's column-major 4x4 facial transformation matrix, with
 * the raw yaw NEGATED so a turn to the subject's own left reads positive
 * (ML-Kit's convention, which the server's thresholds use).
 */
internal object HeadPoseMath {
    private const val RAD2DEG = 180.0 / Math.PI

    fun eulerFromMatrix(data: FloatArray): HeadPose {
        // Column-major: element (row, col) = data[col * 4 + row].
        fun m(row: Int, col: Int): Double = data.getOrNull(col * 4 + row)?.toDouble() ?: 0.0
        val r00 = m(0, 0)
        val r01 = m(0, 1)
        val r10 = m(1, 0)
        val r11 = m(1, 1)
        val r12 = m(1, 2)
        val r20 = m(2, 0)
        val r22 = m(2, 2)
        val pitch = asin((-r12).coerceIn(-1.0, 1.0)) * RAD2DEG
        val yawRaw = atan2(r20, r22) * RAD2DEG
        val roll = atan2(r10, r11) * RAD2DEG
        val rollSafe = if (roll.isFinite()) roll else atan2(-r01, r00) * RAD2DEG
        return HeadPose(yaw = -yawRaw, pitch = pitch, roll = rollSafe)
    }
}

/** What one detected face contributes, in the image's own pixel space. */
internal class FaceObservation(
    /** Landmarks in pixels (x right, y down) of the upright analysis image. */
    val landmarksPx: List<Pair<Float, Float>>,
    /** MediaPipe blendshape name → score; empty when the model gave none. */
    val blendshapes: Map<String, Float>,
    /** The 16-float column-major facial transformation matrix, if emitted. */
    val matrix: FloatArray?,
) {
    val minX: Float get() = landmarksPx.minOf { it.first }
    val maxX: Float get() = landmarksPx.maxOf { it.first }
    val minY: Float get() = landmarksPx.minOf { it.second }
    val maxY: Float get() = landmarksPx.maxOf { it.second }
}

/**
 * MediaPipe face → [ReportedFrame], the way `frame-signal.ts` does it:
 * `eye_open = 1 - mean(eyeBlink*)`, `smile = mean(mouthSmile*)`,
 * `mouth_open = jawOpen`, `face_size` = landmark box diagonal in pixels.
 * A blendshape the model did not emit leaves its field null.
 */
internal object FrameMapper {
    fun map(face: FaceObservation, tMs: Long): ReportedFrame? {
        if (face.landmarksPx.isEmpty()) return null
        val pose = face.matrix?.let(HeadPoseMath::eulerFromMatrix) ?: return null
        val b = face.blendshapes
        fun mean(a: String, c: String): Double? {
            val x = b[a] ?: return null
            val y = b[c] ?: return null
            return (x.toDouble() + y.toDouble()) / 2
        }
        val eyeOpen = mean("eyeBlinkLeft", "eyeBlinkRight")?.let { (1 - it).coerceIn(0.0, 1.0) }
        return ReportedFrame(
            yaw = pose.yaw,
            pitch = pose.pitch,
            roll = pose.roll,
            faceSize = hypot((face.maxX - face.minX).toDouble(), (face.maxY - face.minY).toDouble()),
            tMs = tMs,
            eyeOpen = eyeOpen,
            smile = mean("mouthSmileLeft", "mouthSmileRight"),
            mouthOpen = b["jawOpen"]?.toDouble(),
        )
    }
}
