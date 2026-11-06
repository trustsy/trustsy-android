package com.trustsy.verify.internal.liveness

import kotlin.math.abs

/**
 * The server's `liveness_challenge::challenge_satisfied`, ported line for line.
 *
 * The device only uses it to decide WHEN to submit a step: it runs the same
 * function over the same frames it is about to send, so a step it completes is
 * one the server's movement rules accept. The server re-judges regardless.
 */
internal object ChallengeJudge {
    const val HEAD_ANGLE_THRESHOLD_DEG = 18.0
    const val HEAD_ANGLE_MULTIPLIER = 0.8
    const val SMILE_THRESHOLD = 0.55
    const val BLINK_CLOSE_OPEN_PROB = 0.35
    const val BLINK_REOPEN_OPEN_PROB = 0.65
    const val MAX_BLINK_DURATION_MS = 1000L
    const val MOUTH_OPEN_BLENDSHAPE = 0.45
    const val SCALE_MOVE = 1.25
    const val MIN_SCALE_FRAMES = 4

    fun satisfied(action: String, frames: List<ReportedFrame>): Boolean = when (action) {
        "turn_left" -> largestMove(frames.map { it.yaw }, rising = true) > HEAD_ANGLE_THRESHOLD_DEG
        "turn_right" -> largestMove(frames.map { it.yaw }, rising = false) > HEAD_ANGLE_THRESHOLD_DEG
        "nod" -> frames.any { abs(it.pitch) > HEAD_ANGLE_THRESHOLD_DEG * HEAD_ANGLE_MULTIPLIER }
        "head_shake" -> frames.any { abs(it.yaw) > HEAD_ANGLE_THRESHOLD_DEG * HEAD_ANGLE_MULTIPLIER }
        "smile" -> frames.mapNotNull { it.smile }.any { it > SMILE_THRESHOLD }
        "blink" -> blinkCompleted(frames)
        // Nothing in the frames can show the colours were met; the sidecar judges the clip.
        "flash" -> true
        "open_mouth" -> mouthOpened(frames)
        "move_closer" -> medianFaceSize(frames)?.let { base -> frames.any { it.faceSize >= base * SCALE_MOVE } } ?: false
        "move_back" -> medianFaceSize(frames)?.let { base ->
            frames.any { it.faceSize > 0.0 && it.faceSize <= base / SCALE_MOVE }
        } ?: false
        // An action this build does not know: never claim it was performed.
        else -> false
    }

    /** Median of positive face sizes, or null below [MIN_SCALE_FRAMES]. */
    internal fun medianFaceSize(frames: List<ReportedFrame>): Double? {
        val sizes = frames.map { it.faceSize }.filter { it > 0.0 }.sorted()
        if (sizes.size < MIN_SCALE_FRAMES) return null
        return sizes[sizes.size / 2]
    }

    /** Biggest `later - earlier` (rising) or `earlier - later`; NaN never a baseline. */
    internal fun largestMove(values: List<Double>, rising: Boolean): Double {
        var extreme: Double? = null
        var best = 0.0
        for (v in values) {
            if (v.isNaN()) continue
            val e = extreme
            if (e != null) best = maxOf(best, if (rising) v - e else e - v)
            extreme = if (e == null) v else if (rising) minOf(e, v) else maxOf(e, v)
        }
        return best
    }

    private fun mouthOpened(frames: List<ReportedFrame>): Boolean {
        var seenClosed = false
        for (m in frames.mapNotNull { it.mouthOpen }) {
            if (m > MOUTH_OPEN_BLENDSHAPE) {
                if (seenClosed) return true
            } else {
                seenClosed = true
            }
        }
        return false
    }

    private fun blinkCompleted(frames: List<ReportedFrame>): Boolean {
        var wasBlinking = false
        var lastBlinkMs = 0L
        for (f in frames) {
            val open = f.eyeOpen ?: continue
            if (open < BLINK_CLOSE_OPEN_PROB && !wasBlinking) {
                wasBlinking = true
                lastBlinkMs = f.tMs
                continue
            }
            if (open > BLINK_REOPEN_OPEN_PROB && wasBlinking) {
                wasBlinking = false
                if (f.tMs - lastBlinkMs < MAX_BLINK_DURATION_MS) return true
            }
        }
        return false
    }
}

/**
 * When to submit one step.
 *
 * Two frame windows, both required to pass:
 * - **submitted**: every frame since the previous step was accepted — what the
 *   server will judge, so passing here means the server's movement rules pass.
 * - **anchored**: frames since the applicant came back to a neutral pose after
 *   the previous step. Without it, the head RETURNING from a right turn rises
 *   by ~20° and would read as a `turn_left` the applicant never made.
 */
internal class StepJudge(private val action: String) {
    private val submitted = ArrayList<ReportedFrame>()
    private val anchored = ArrayList<ReportedFrame>()
    private var anchoredAtNeutral = false

    /** Frames to send with the step. */
    val frames: List<ReportedFrame> get() = submitted

    /**
     * @param requireNeutralFirst true after a previous step: wait for a neutral
     *   frame before measuring. False for the first step (positioning already
     *   ended at a neutral pose).
     */
    fun begin(requireNeutralFirst: Boolean) {
        submitted.clear()
        anchored.clear()
        anchoredAtNeutral = !requireNeutralFirst
    }

    /** Feed one frame; true once the action is done. */
    fun observe(frame: ReportedFrame): Boolean {
        submitted += frame
        if (!anchoredAtNeutral) {
            if (!Neutral.isNeutral(frame)) return false
            anchoredAtNeutral = true
        }
        anchored += frame
        return ChallengeJudge.satisfied(action, anchored) && ChallengeJudge.satisfied(action, submitted)
    }

    /** True while the judge is still waiting for the applicant to return to neutral. */
    val waitingForNeutral: Boolean get() = !anchoredAtNeutral
}

/** The web's `NeutralPositionDetector`: no smile, eyes open, facing the camera. */
internal object Neutral {
    const val SMILE_BELOW = 0.35
    const val HEAD_ANGLE_BELOW = 12.0

    fun isNeutral(f: ReportedFrame): Boolean =
        (f.smile ?: 0.0) < SMILE_BELOW &&
            (f.eyeOpen ?: 1.0) > ChallengeJudge.BLINK_REOPEN_OPEN_PROB &&
            abs(f.yaw) < HEAD_ANGLE_BELOW
}
