package com.trustsy.verify

import com.trustsy.verify.internal.liveness.FaceObservation
import com.trustsy.verify.internal.liveness.LivenessApi
import com.trustsy.verify.internal.liveness.LivenessController
import com.trustsy.verify.internal.liveness.LivenessController.FrameAction
import com.trustsy.verify.internal.liveness.LivenessEffects
import com.trustsy.verify.internal.liveness.LivenessPhase
import com.trustsy.verify.internal.liveness.PositionGate
import com.trustsy.verify.internal.net.FlashSegment
import com.trustsy.verify.internal.net.IssuedChallenge
import com.trustsy.verify.internal.net.LivenessSession
import com.trustsy.verify.internal.net.StepSubmission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class LivenessControllerTest {

    /** A face centred in a 640x480 frame, box 300x300, with the given pose and blendshapes. */
    private fun face(yawDeg: Double = 0.0, smile: Float = 0f, cx: Float = 320f, blink: Float = 0f): FaceObservation {
        val t = Math.toRadians(yawDeg)
        val m = FloatArray(16)
        // Row-major R_y(t) written column-major.
        val r = arrayOf(doubleArrayOf(cos(t), 0.0, sin(t)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(t), 0.0, cos(t)))
        for (row in 0..2) for (col in 0..2) m[col * 4 + row] = r[row][col].toFloat()
        m[15] = 1f
        return FaceObservation(
            landmarksPx = listOf(cx - 150f to 90f, cx + 150f to 90f, cx - 150f to 390f, cx + 150f to 390f),
            blendshapes = mapOf(
                "eyeBlinkLeft" to blink, "eyeBlinkRight" to blink,
                "mouthSmileLeft" to smile, "mouthSmileRight" to smile, "jawOpen" to 0f,
            ),
            matrix = m,
        )
    }

    private class FakeApi(val plan: ArrayDeque<LivenessSession>) : LivenessApi {
        val starts = mutableListOf<Boolean>()
        val steps = mutableListOf<StepSubmission>()
        override suspend fun startLiveness(flash: Boolean): LivenessSession {
            starts += flash
            return plan.removeFirst()
        }

        override suspend fun submitLivenessStep(body: StepSubmission): LivenessSession {
            steps += body
            return plan.removeFirst()
        }
    }

    private class FakeEffects : LivenessEffects {
        var started = 0
        var stopped = 0
        override fun startRunRecording() {
            started++
        }

        override suspend fun stopRunRecording() {
            stopped++
        }
    }

    private fun session(state: String, action: String?, step: Int = 1, sequence: List<FlashSegment>? = null) =
        LivenessSession(
            sessionId = "s1",
            state = state,
            stepsTotal = 3,
            challenge = action?.let { IssuedChallenge("c$step", step, it, sequence = sequence) },
        )

    private fun TestScope.controller(api: FakeApi, effects: FakeEffects, declineFlash: Boolean = false) =
        LivenessController(api, effects, this, declineFlash)

    private fun LivenessController.feed(face: FaceObservation?, now: Long, faces: Int = if (face == null) 0 else 1) =
        onFrame(faces, face, 640, 480, now)

    @Test
    fun `the session starts only after one second of good framing`() = runTest {
        val api = FakeApi(ArrayDeque(listOf(session("active", "smile"))))
        val effects = FakeEffects()
        val c = controller(api, effects)
        assertEquals(FrameAction.NONE, c.feed(face(), 0))
        assertEquals(FrameAction.NONE, c.feed(face(), 500))
        assertTrue(api.starts.isEmpty())
        // A turned head restarts the clock.
        c.feed(face(yawDeg = 20.0), 700)
        assertEquals(LivenessPhase.Positioning(PositionGate.Hint.LOOK_STRAIGHT), c.phase.value)
        c.feed(face(), 800)
        assertEquals(FrameAction.NONE, c.feed(face(), 1700))
        assertEquals(FrameAction.CAPTURE_SELFIE, c.feed(face(), 1800))
        advanceUntilIdle()
        assertEquals(listOf(true), api.starts)
        assertEquals(1, effects.started)
        assertEquals(LivenessPhase.Challenge("smile", 1, 3, returnToNeutral = false, submitting = false), c.phase.value)
    }

    @Test
    fun `a completed step submits its frames and moves to the next`() = runTest {
        val api = FakeApi(ArrayDeque(listOf(session("active", "smile"), session("active", "turn_left", 2), session("passed", null))))
        val c = controller(api, FakeEffects())
        c.feed(face(), 0)
        c.feed(face(), 1000)
        advanceUntilIdle()
        c.feed(face(), 1100)
        c.feed(face(smile = 0.8f), 1200)
        advanceUntilIdle()
        assertEquals(1, api.steps.size)
        assertEquals("c1", api.steps[0].challengeId)
        assertEquals(2, api.steps[0].frames.size)
        // Still smiling: the next step waits for neutral first.
        c.feed(face(smile = 0.8f), 1300)
        assertEquals(true, (c.phase.value as LivenessPhase.Challenge).returnToNeutral)
        c.feed(face(), 1400)
        c.feed(face(yawDeg = 10.0), 1500)
        c.feed(face(yawDeg = 25.0), 1600)
        advanceUntilIdle()
        assertEquals(2, api.steps.size)
        assertEquals(LivenessPhase.Passed, c.phase.value)
        val report = c.report()
        assertEquals(listOf("smile", "turn_left"), report.challenges)
        assertTrue(report.frames.size <= LivenessController.MAX_REPORTED_FRAMES)
    }

    @Test
    fun `a refused step fails the run and a restart begins again`() = runTest {
        val api = FakeApi(ArrayDeque(listOf(session("active", "smile"), session("failed", null).copy(rejectReason = "step_expired"))))
        val c = controller(api, FakeEffects())
        c.feed(face(), 0)
        c.feed(face(), 1000)
        advanceUntilIdle()
        c.feed(face(smile = 0.9f), 1100)
        advanceUntilIdle()
        assertEquals(LivenessPhase.Failed("step_expired"), c.phase.value)
        c.restart()
        assertEquals(LivenessPhase.Positioning(PositionGate.Hint.NO_FACE), c.phase.value)
    }

    @Test
    fun `a step not done in time is submitted anyway`() = runTest {
        val api = FakeApi(ArrayDeque(listOf(session("active", "blink"), session("failed", null))))
        val c = controller(api, FakeEffects())
        c.feed(face(), 0)
        c.feed(face(), 1000)
        advanceUntilIdle()
        c.feed(face(), 2000)
        c.feed(null, 21_000)
        advanceUntilIdle()
        assertEquals(1, api.steps.size)
        assertTrue(c.phase.value is LivenessPhase.Failed)
    }

    @Test
    fun `flash closes the run recording, then submits with no frames`() = runTest {
        val sequence = listOf(FlashSegment("red", 500), FlashSegment("blue", 600))
        val api = FakeApi(ArrayDeque(listOf(session("active", "smile"), session("active", "flash", 2, sequence), session("passed", null))))
        val effects = FakeEffects()
        val c = controller(api, effects)
        c.feed(face(), 0)
        c.feed(face(), 1000)
        advanceUntilIdle()
        c.feed(face(smile = 0.9f), 1100)
        advanceUntilIdle()
        assertEquals(1, effects.stopped)
        assertEquals(LivenessPhase.Flash(sequence), c.phase.value)
        c.onFlashFinished()
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), api.steps[1].frames)
        assertEquals(LivenessPhase.Passed, c.phase.value)
        assertEquals(listOf("smile", "flash"), c.report().challenges)
    }

    @Test
    fun `reduced motion declines the flash`() = runTest {
        val api = FakeApi(ArrayDeque(listOf(session("active", "smile"))))
        val c = controller(api, FakeEffects(), declineFlash = true)
        c.feed(face(), 0)
        c.feed(face(), 1000)
        advanceUntilIdle()
        assertEquals(listOf(false), api.starts)
    }

    @Test
    fun `two faces never pass the gate`() = runTest {
        val api = FakeApi(ArrayDeque())
        val c = controller(api, FakeEffects())
        c.feed(face(), 0, faces = 2)
        c.feed(face(), 2000, faces = 2)
        assertEquals(LivenessPhase.Positioning(PositionGate.Hint.MULTIPLE_FACES), c.phase.value)
    }

    @Test
    fun `an off-centre face is asked to centre`() = runTest {
        val c = controller(FakeApi(ArrayDeque()), FakeEffects())
        c.feed(face(cx = 560f), 0)
        assertEquals(LivenessPhase.Positioning(PositionGate.Hint.CENTER), c.phase.value)
    }
}
