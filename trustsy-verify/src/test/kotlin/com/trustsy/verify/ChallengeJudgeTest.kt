package com.trustsy.verify

import com.trustsy.verify.internal.liveness.ChallengeJudge
import com.trustsy.verify.internal.liveness.ReportedFrame
import com.trustsy.verify.internal.liveness.StepJudge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device judge against the server's rules. The first block replays the
 * server's own unit tests (`liveness_challenge.rs`) case for case; a port that
 * drifts from the server fails here before a real step is refused.
 */
class ChallengeJudgeTest {

    private fun f(
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        faceSize: Double = 200.0,
        t: Long = 0,
        eye: Double? = 0.95,
        smile: Double? = null,
        mouth: Double? = null,
    ) = ReportedFrame(yaw, pitch, 0.0, faceSize, t, eye, smile, mouth)

    private fun yaws(vararg v: Double) = v.mapIndexed { i, y -> f(yaw = y, t = i * 250L) }
    private fun jaws(vararg v: Double) = v.mapIndexed { i, m -> f(t = i * 250L, mouth = m) }
    private fun left(vararg v: Double) = ChallengeJudge.satisfied("turn_left", yaws(*v))
    private fun right(vararg v: Double) = ChallengeJudge.satisfied("turn_right", yaws(*v))
    private fun opened(vararg v: Double) = ChallengeJudge.satisfied("open_mouth", jaws(*v))

    // ---- the server's tests ----------------------------------------------------

    @Test
    fun `a turn is movement in the asked direction`() {
        assertTrue(left(0.0, 10.0, 25.0))
        assertFalse(right(0.0, 10.0, 25.0))
        assertTrue(right(0.0, -25.0))
        assertFalse(left(0.0, -25.0))
        assertFalse(left(2.0, 20.0))
        assertTrue(left(2.0, 20.5))
        assertFalse(left(25.0, 0.0))
    }

    @Test
    fun `a still face passes no turn at any angle`() {
        for (angle in listOf(21.6, 30.0, -25.0, 0.0)) {
            val still = DoubleArray(12) { angle }
            assertFalse("$angle", left(*still))
            assertFalse("$angle", right(*still))
        }
        assertTrue(left(21.6, 30.0, 41.0))
        assertTrue(right(21.6, 10.0, 2.0))
    }

    @Test
    fun `unreadable yaw is skipped, never a baseline`() {
        assertFalse(left(Double.NaN, 21.6, 21.6))
        assertTrue(left(0.0, Double.NaN, 25.0))
    }

    @Test
    fun `open mouth needs the jaw to open from closed`() {
        assertTrue(opened(0.1, 0.3, 0.6))
        assertFalse(opened(*DoubleArray(8) { 0.6 }))
        assertFalse(opened(*DoubleArray(8) { 0.1 }))
        assertFalse(opened(0.6, 0.1))
        assertTrue(opened(0.45, 0.46))
        assertFalse(ChallengeJudge.satisfied("open_mouth", yaws(0.0, 0.0)))
    }

    @Test
    fun `nod and head shake use the relaxed absolute threshold`() {
        assertTrue(ChallengeJudge.satisfied("nod", listOf(f(pitch = -15.0, eye = null))))
        val shake = listOf(f(yaw = 15.0, eye = null))
        assertTrue(ChallengeJudge.satisfied("head_shake", shake))
        assertFalse(ChallengeJudge.satisfied("turn_left", shake))
    }

    @Test
    fun `blink fires on close then reopen within the window`() {
        val frames = listOf(f(t = 0, eye = 0.9), f(t = 100, eye = 0.2), f(t = 300, eye = 0.8))
        assertTrue(ChallengeJudge.satisfied("blink", frames))
    }

    @Test
    fun `a slow droop is not a blink`() {
        assertFalse(ChallengeJudge.satisfied("blink", listOf(f(t = 0, eye = 0.2), f(t = 1500, eye = 0.8))))
    }

    @Test
    fun `no eye reading cannot be a blink`() {
        assertFalse(ChallengeJudge.satisfied("blink", listOf(f(eye = null))))
    }

    @Test
    fun `blink hysteresis ignores the dead zone`() {
        // 0.4 is neither closed (< 0.35) nor re-opened (> 0.65).
        assertFalse(ChallengeJudge.satisfied("blink", listOf(f(t = 0, eye = 0.9), f(t = 100, eye = 0.4), f(t = 200, eye = 0.9))))
        // 0.6 after a close is not yet open; 0.7 is.
        assertFalse(ChallengeJudge.satisfied("blink", listOf(f(t = 0, eye = 0.3), f(t = 100, eye = 0.6))))
        assertTrue(ChallengeJudge.satisfied("blink", listOf(f(t = 0, eye = 0.3), f(t = 100, eye = 0.6), f(t = 200, eye = 0.7))))
    }

    @Test
    fun `smile threshold`() {
        assertTrue(ChallengeJudge.satisfied("smile", listOf(f(smile = 0.6))))
        assertFalse(ChallengeJudge.satisfied("smile", listOf(f(smile = 0.4))))
        assertFalse(ChallengeJudge.satisfied("smile", listOf(f(smile = 0.55))))
    }

    @Test
    fun `moves are judged against the step's own median`() {
        val still = List(4) { f(faceSize = 200.0, t = it * 100L) }
        assertFalse(ChallengeJudge.satisfied("move_closer", still))
        assertTrue(ChallengeJudge.satisfied("move_closer", still + f(faceSize = 250.0)))
        assertFalse(ChallengeJudge.satisfied("move_closer", still + f(faceSize = 249.0)))
        assertTrue(ChallengeJudge.satisfied("move_back", still + f(faceSize = 160.0)))
        assertFalse(ChallengeJudge.satisfied("move_back", still + f(faceSize = 161.0)))
        // Below four sizes there is no baseline.
        assertFalse(ChallengeJudge.satisfied("move_closer", listOf(f(faceSize = 100.0), f(faceSize = 400.0))))
    }

    @Test
    fun `flash passes and an unknown action never does`() {
        assertTrue(ChallengeJudge.satisfied("flash", emptyList()))
        assertFalse(ChallengeJudge.satisfied("wink", yaws(0.0, 40.0)))
    }

    // ---- the step judge --------------------------------------------------------

    @Test
    fun `returning from a right turn is not a left turn`() {
        val judge = StepJudge("turn_left").apply { begin(requireNeutralFirst = true) }
        // Head still turned right from the previous step, coming back to centre:
        // a 22° rise the server alone would count as turn_left.
        for (y in listOf(-22.0, -15.0, -8.0, 0.0)) assertFalse("$y", judge.observe(f(yaw = y)))
        assertTrue(ChallengeJudge.satisfied("turn_left", judge.frames))
        // Now a real left turn from neutral.
        assertFalse(judge.observe(f(yaw = 10.0)))
        assertTrue(judge.observe(f(yaw = 20.0)))
    }

    @Test
    fun `the step submits every frame since the previous step`() {
        val judge = StepJudge("blink").apply { begin(requireNeutralFirst = true) }
        judge.observe(f(yaw = -20.0, t = 0))
        judge.observe(f(yaw = 0.0, t = 100))
        judge.observe(f(t = 200, eye = 0.2))
        assertTrue(judge.observe(f(t = 300, eye = 0.9)))
        assertTrue(judge.frames.size == 4)
        // What the server will judge passes too.
        assertTrue(ChallengeJudge.satisfied("blink", judge.frames))
    }

    @Test
    fun `the first step needs no neutral wait`() {
        val judge = StepJudge("smile").apply { begin(requireNeutralFirst = false) }
        assertFalse(judge.waitingForNeutral)
        assertTrue(judge.observe(f(smile = 0.8)))
    }

    @Test
    fun `a smile does not count while waiting for neutral`() {
        val judge = StepJudge("smile").apply { begin(requireNeutralFirst = true) }
        // Still smiling from before: not neutral, not counted.
        assertFalse(judge.observe(f(smile = 0.8)))
        assertTrue(judge.waitingForNeutral)
        assertFalse(judge.observe(f(smile = 0.1)))
        assertTrue(judge.observe(f(smile = 0.7)))
    }
}
