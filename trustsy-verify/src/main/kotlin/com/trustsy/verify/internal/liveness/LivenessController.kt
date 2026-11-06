package com.trustsy.verify.internal.liveness

import com.trustsy.verify.internal.net.ChallengeReport
import com.trustsy.verify.internal.net.FlashSegment
import com.trustsy.verify.internal.net.LivenessSession
import com.trustsy.verify.internal.net.StepSubmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The two session calls; [com.trustsy.verify.internal.net.ApiClient] in production. */
internal interface LivenessApi {
    suspend fun startLiveness(flash: Boolean): LivenessSession
    suspend fun submitLivenessStep(body: StepSubmission): LivenessSession
}

/** What the camera layer does for the controller. Called on the controller's scope. */
internal interface LivenessEffects {
    /** The challenge run starts: begin the run recording. */
    fun startRunRecording()

    /** End the run recording; returns once the file is closed (or there was none). */
    suspend fun stopRunRecording()
}

internal sealed interface LivenessPhase {
    /** Framing the face; nothing has been asked of the server yet. */
    data class Positioning(val hint: PositionGate.Hint) : LivenessPhase

    data object Starting : LivenessPhase

    data class Challenge(
        val action: String,
        val step: Int,
        val total: Int,
        /** True until the applicant is back at a neutral pose after the previous step. */
        val returnToNeutral: Boolean,
        val submitting: Boolean,
    ) : LivenessPhase

    /** The screen shows the colour sequence while a separate clip records. */
    data class Flash(val sequence: List<FlashSegment>) : LivenessPhase

    /** The server says the run passed; evidence can be uploaded. */
    data object Passed : LivenessPhase

    /** The session failed or a call did; the applicant may start again. */
    data class Failed(val reason: String) : LivenessPhase
}

/**
 * One liveness run (contract §4 Liveness): positioning gate → session →
 * one server-issued step at a time → (flash) → passed.
 *
 * Frames arrive from the analysis thread through [onFrame]; network answers on
 * [scope]. Every state change happens under one lock.
 */
internal class LivenessController(
    private val api: LivenessApi,
    private val effects: LivenessEffects,
    private val scope: CoroutineScope,
    /** True when the OS asks for reduced motion: the server then plans no flash. */
    private val declineFlash: Boolean,
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
) {
    private val lock = Any()
    private val _phase = MutableStateFlow<LivenessPhase>(LivenessPhase.Positioning(PositionGate.Hint.NO_FACE))
    val phase: StateFlow<LivenessPhase> = _phase

    private val gate = PositionGate()
    private var runStartMs: Long? = null
    private val history = ArrayDeque<ReportedFrame>()
    private val issued = ArrayList<String>()
    private var session: LivenessSession? = null
    private var judge: StepJudge? = null
    private var stepIssuedAtMs = 0L
    /** The frame clock's latest reading; a step's 20 s run on it. */
    private var latestNowMs = 0L
    private var submitting = false
    /** Frames that arrive while a step is in flight belong to the next step. */
    private val inFlight = ArrayList<ReportedFrame>()

    /** What [onFrame] asks of the caller for this frame. */
    enum class FrameAction { NONE, CAPTURE_SELFIE }

    /**
     * @param nowMs a monotonic clock, ms.
     * @return [FrameAction.CAPTURE_SELFIE] on the frame the gate passed: the
     *   applicant is centred and frontal, which is the still to submit.
     */
    fun onFrame(faces: Int, face: FaceObservation?, width: Int, height: Int, nowMs: Long): FrameAction = synchronized(lock) {
        latestNowMs = nowMs
        val start = runStartMs ?: nowMs.also { runStartMs = it }
        val frame = face?.let { FrameMapper.map(it, nowMs - start) }
        when (val current = _phase.value) {
            is LivenessPhase.Positioning -> {
                val hint = gate.observe(faces, face, frame, width, height, nowMs)
                if (hint == PositionGate.Hint.READY) {
                    startSession()
                    return FrameAction.CAPTURE_SELFIE
                }
                if (hint != current.hint) _phase.value = LivenessPhase.Positioning(hint)
            }
            is LivenessPhase.Challenge -> {
                if (frame != null) record(frame)
                if (submitting) {
                    frame?.let(inFlight::add)
                    return FrameAction.NONE
                }
                val judge = judge ?: return FrameAction.NONE
                val done = frame != null && judge.observe(frame)
                val timedOut = nowMs - stepIssuedAtMs >= stepTimeoutMs
                if (done || timedOut) {
                    // Timed out: submit anyway. The server refuses it and the
                    // session fails, so a retry draws a fresh session instead of
                    // resuming a step that has already expired.
                    submitStep(judge.frames.toList())
                } else if (judge.waitingForNeutral != current.returnToNeutral) {
                    _phase.value = current.copy(returnToNeutral = judge.waitingForNeutral)
                }
            }
            else -> Unit
        }
        FrameAction.NONE
    }

    /** The flash stage finished (clip recorded, or the applicant opted out). */
    fun onFlashFinished() = synchronized(lock) {
        if (_phase.value !is LivenessPhase.Flash) return
        submitStep(emptyList())
    }

    /** Start over after a failure: a new session, a new gate. */
    fun restart() = synchronized(lock) {
        gate.reset()
        runStartMs = null
        history.clear()
        issued.clear()
        session = null
        judge = null
        submitting = false
        inFlight.clear()
        _phase.value = LivenessPhase.Positioning(PositionGate.Hint.NO_FACE)
    }

    /** `challenge_report`: every action issued, and the last ≤ 30 frames. */
    fun report(): ChallengeReport = synchronized(lock) { ChallengeReport(issued.toList(), history.toList()) }

    // ---- internals (hold [lock]) ----------------------------------------------

    private fun record(frame: ReportedFrame) {
        history.addLast(frame)
        while (history.size > MAX_REPORTED_FRAMES) history.removeFirst()
    }

    private fun startSession() {
        _phase.value = LivenessPhase.Starting
        effects.startRunRecording()
        scope.launch {
            val result = runCatching { api.startLiveness(flash = !declineFlash) }
            synchronized(lock) {
                if (_phase.value != LivenessPhase.Starting) return@launch
                result.fold(
                    onSuccess = { apply(it, firstStep = true) },
                    onFailure = { _phase.value = LivenessPhase.Failed(START_FAILED) },
                )
            }
        }
    }

    private fun submitStep(frames: List<ReportedFrame>) {
        val s = session ?: return
        val challenge = s.challenge ?: return
        submitting = true
        inFlight.clear()
        val current = _phase.value
        if (current is LivenessPhase.Challenge) _phase.value = current.copy(submitting = true)
        scope.launch {
            val result = runCatching {
                api.submitLivenessStep(StepSubmission(s.sessionId, challenge.challengeId, frames))
            }
            synchronized(lock) {
                if (session?.sessionId != s.sessionId) return@launch
                submitting = false
                result.fold(
                    onSuccess = { apply(it, firstStep = false) },
                    onFailure = { _phase.value = LivenessPhase.Failed(STEP_FAILED) },
                )
            }
        }
    }

    private fun apply(next: LivenessSession, firstStep: Boolean) {
        session = next
        val challenge = next.challenge
        when {
            challenge == null && next.state == "passed" -> _phase.value = LivenessPhase.Passed
            challenge == null -> _phase.value = LivenessPhase.Failed(next.rejectReason ?: next.state)
            challenge.action == "flash" && !challenge.sequence.isNullOrEmpty() -> {
                issued += challenge.action
                // One recorder at a time: the run recording closes before the
                // flash clip opens, and the colours paint with the clip.
                scope.launch {
                    effects.stopRunRecording()
                    synchronized(lock) {
                        if (session === next) _phase.value = LivenessPhase.Flash(challenge.sequence.orEmpty())
                    }
                }
            }
            else -> {
                issued += challenge.action
                stepIssuedAtMs = latestNowMs
                judge = StepJudge(challenge.action).also { j ->
                    j.begin(requireNeutralFirst = !firstStep)
                    // Frames seen while the previous step was in flight came after
                    // it was submitted; they open this step's window.
                    inFlight.forEach { j.observe(it) }
                }
                inFlight.clear()
                _phase.value = LivenessPhase.Challenge(
                    action = challenge.action,
                    step = challenge.step,
                    total = next.stepsTotal,
                    returnToNeutral = judge?.waitingForNeutral == true,
                    submitting = false,
                )
            }
        }
    }

    companion object {
        /** The server's `STEP_TTL_SECS`. */
        const val STEP_TIMEOUT_MS = 20_000L
        /** `challenge_report.frames` holds at most this many (the newest). */
        const val MAX_REPORTED_FRAMES = 30
        const val START_FAILED = "start_failed"
        const val STEP_FAILED = "step_failed"
    }
}
