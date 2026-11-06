package com.trustsy.verify.internal.liveness

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The positioning gate that runs BEFORE the liveness session is started
 * (contract §4 Liveness 1): each step expires 20 s after it is issued, so
 * framing the face must not eat into it.
 *
 * Passes after [STABLE_MS] of uninterrupted frames with ONE face that is
 * centred, large enough and roughly frontal. One bad frame restarts the clock.
 */
internal class PositionGate(private val stableMs: Long = STABLE_MS) {

    enum class Hint { NO_FACE, MULTIPLE_FACES, CENTER, MOVE_CLOSER, MOVE_BACK, LOOK_STRAIGHT, HOLD_STILL, READY }

    private var goodSince: Long? = null

    /**
     * @param faces faces detected in this frame.
     * @param frame the first face mapped to wire numbers, when there is one.
     * @param width,height the analysis image size in pixels (upright).
     */
    fun observe(
        faces: Int,
        face: FaceObservation?,
        frame: ReportedFrame?,
        width: Int,
        height: Int,
        nowMs: Long,
    ): Hint {
        val hint = assess(faces, face, frame, width, height)
        if (hint != Hint.HOLD_STILL) {
            goodSince = null
            return hint
        }
        val since = goodSince ?: nowMs.also { goodSince = it }
        return if (nowMs - since >= stableMs) Hint.READY else Hint.HOLD_STILL
    }

    fun reset() {
        goodSince = null
    }

    private fun assess(faces: Int, face: FaceObservation?, frame: ReportedFrame?, width: Int, height: Int): Hint {
        if (faces == 0 || face == null || frame == null || width <= 0 || height <= 0) return Hint.NO_FACE
        if (faces > 1) return Hint.MULTIPLE_FACES
        val cx = (face.minX + face.maxX) / 2.0
        val cy = (face.minY + face.maxY) / 2.0
        val dx = (cx - width / 2.0) / (width / 2.0)
        val dy = (cy - height / 2.0) / (height / 2.0)
        if (1 - hypot(dx, dy) < 1 - CENTER_TOLERANCE) return Hint.CENTER
        val area = (frame.faceSize / hypot(width.toDouble(), height.toDouble())).let { it * it }
        if (area < MIN_FACE_AREA) return Hint.MOVE_CLOSER
        if (area > MAX_FACE_AREA) return Hint.MOVE_BACK
        if (abs(frame.yaw) >= MAX_ANGLE || abs(frame.pitch) >= MAX_ANGLE || abs(frame.roll) >= MAX_ANGLE) {
            return Hint.LOOK_STRAIGHT
        }
        return Hint.HOLD_STILL
    }

    companion object {
        const val STABLE_MS = 1000L
        /** Offset of the face centre from the frame centre, as a fraction of the half-extent. */
        const val CENTER_TOLERANCE = 0.3
        /** Face-box area as a fraction of the frame (the web's `minFaceSize` / `maxFaceSize`). */
        const val MIN_FACE_AREA = 0.08
        const val MAX_FACE_AREA = 0.95
        /** Contract: |yaw|, |pitch|, |roll| < 12°. */
        const val MAX_ANGLE = 12.0
    }
}
