package com.trustsy.verify.internal.ui.screens

import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trustsy.verify.R
import com.trustsy.verify.internal.flow.Snapshot
import com.trustsy.verify.internal.liveness.FaceAnalyzer
import com.trustsy.verify.internal.liveness.LivenessController
import com.trustsy.verify.internal.liveness.LivenessPhase
import com.trustsy.verify.internal.liveness.PositionGate
import com.trustsy.verify.internal.net.ApiException
import com.trustsy.verify.internal.net.FileUpload
import com.trustsy.verify.internal.net.FlashSegment
import com.trustsy.verify.internal.net.LivenessSubmission
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.StatusScreen
import com.trustsy.verify.internal.ui.StepScaffold
import com.trustsy.verify.internal.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** The server's media cap (`upload_validation::max_media_bytes`): a bigger clip is left out. */
private const val MAX_VIDEO_BYTES = 16L * 1024 * 1024

/** Contract: sample at 5–10 Hz. */
private const val FRAME_INTERVAL_MS = 100L

/** Kept past the last colour so the final segment is fully in the clip (web `FlashStage`). */
private const val FLASH_TAIL_MS = 150L

@Composable
internal fun LivenessScreen(controller: FlowController, snapshot: Snapshot) {
    if ("selfie" !in controller.guided) {
        Guide(
            title = stringResource(R.string.trustsy_guide_selfie_title),
            lead = stringResource(R.string.trustsy_guide_selfie_lead),
            tips = listOf(
                stringResource(R.string.trustsy_guide_selfie_tip_light),
                stringResource(R.string.trustsy_guide_selfie_tip_clear),
                stringResource(R.string.trustsy_guide_selfie_tip_prompts),
            ),
            onStart = { controller.guided += "selfie" },
            onChangeDocument = null,
        )
        return
    }
    CameraPermission {
        var attempt by remember { mutableIntStateOf(0) }
        // Keyed by attempt: "Try again" is a new camera binding, recorder and session.
        androidx.compose.runtime.key(attempt) {
            LivenessRun(controller, snapshot, onRetry = { attempt++ })
        }
    }
}

@Composable
private fun LivenessRun(controller: FlowController, snapshot: Snapshot, onRetry: () -> Unit) {
    val host = LocalHostContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recorder = remember { CameraRecorder(host, null) }
    val reducedMotion = remember {
        Settings.Global.getFloat(host.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val liveness = remember { LivenessController(controller.api, recorder, scope, declineFlash = reducedMotion) }
    val phase by liveness.phase.collectAsState()
    val selfie = remember { AtomicReference<ByteArray?>(null) }
    var analyzer by remember { mutableStateOf<FaceAnalyzer?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(host).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }

    // The model loads off the main thread (a few hundred ms on a phone).
    LaunchedEffect(Unit) {
        analyzer = withContext(Dispatchers.Default) { runCatching { FaceAnalyzer(host) }.getOrNull() }
        if (analyzer == null) cameraFailed = true
    }

    val face = analyzer
    DisposableEffect(face, lifecycleOwner) {
        if (face == null) return@DisposableEffect onDispose {}
        val future = ProcessCameraProvider.getInstance(host)
        var provider: ProcessCameraProvider? = null
        var lastMs = 0L
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: run { cameraFailed = true; return@addListener }
            provider = p
            if (!runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)) {
                cameraFailed = true
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    ).build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image ->
                image.use {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMs < FRAME_INTERVAL_MS) return@setAnalyzer
                    lastMs = now
                    // CameraX front frames are NOT mirrored — the same orientation
                    // as the browser's raw video, which the yaw sign relies on.
                    val bitmap = Images.upright(image)
                    val detection = runCatching { face.detect(bitmap, now) }.getOrNull()
                        ?: FaceAnalyzer.Detection(0, null)
                    val action = liveness.onFrame(detection.faces, detection.first, bitmap.width, bitmap.height, now)
                    if (action == LivenessController.FrameAction.CAPTURE_SELFIE) selfie.set(Images.jpeg(bitmap))
                    bitmap.recycle()
                }
            }
            val video = VideoCapture.withOutput(
                Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    // ~1 Mbps, as the web caps it: the run must fit the upload limit.
                    .setTargetVideoEncodingBitRate(1_000_000)
                    .build(),
            )
            val bound = runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, video)
            }.isSuccess
            if (bound) {
                recorder.videoCapture = video
            } else {
                // Some devices cannot run three streams: video is optional on the wire.
                recorder.videoCapture = null
                runCatching {
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }.onFailure { cameraFailed = true }
            }
        }, ContextCompat.getMainExecutor(host))
        onDispose {
            provider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            analyzer?.close()
            recorder.discard()
        }
    }

    if (cameraFailed) {
        StepScaffold(
            title = stringResource(R.string.trustsy_liveness_error_start),
            actions = { PrimaryButton(stringResource(R.string.trustsy_retry), onClick = onRetry) },
        )
        return
    }

    when (val p = phase) {
        is LivenessPhase.Flash -> FlashStage(p.sequence, recorder, onDone = liveness::onFlashFinished)
        LivenessPhase.Passed -> Submit(controller, snapshot, liveness, recorder, selfie)
        is LivenessPhase.Failed -> StepScaffold(
            title = stringResource(
                if (p.reason == LivenessController.START_FAILED) R.string.trustsy_liveness_error_start
                else R.string.trustsy_liveness_error_challenge,
            ),
            actions = { PrimaryButton(stringResource(R.string.trustsy_retry), onClick = onRetry) },
        )
        else -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val w = size.width * 0.72f
                val h = w * 1.3f
                val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2.4f)
                drawRect(Color.Black.copy(alpha = 0.6f))
                drawOval(Color.Transparent, topLeft, GSize(w, h), blendMode = BlendMode.Clear)
                drawOval(Color.White, topLeft, GSize(w, h), style = Stroke(3.dp.toPx()))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp)) {
                Text(
                    if (face == null) stringResource(R.string.trustsy_liveness_loading_models) else prompt(p),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun prompt(phase: LivenessPhase): String = when (phase) {
    is LivenessPhase.Positioning -> when (phase.hint) {
        PositionGate.Hint.NO_FACE -> stringResource(R.string.trustsy_liveness_no_face)
        PositionGate.Hint.MULTIPLE_FACES -> stringResource(R.string.trustsy_liveness_one_face)
        PositionGate.Hint.CENTER -> stringResource(R.string.trustsy_liveness_prompt_center)
        PositionGate.Hint.MOVE_CLOSER -> stringResource(R.string.trustsy_liveness_prompt_move_closer)
        PositionGate.Hint.MOVE_BACK -> stringResource(R.string.trustsy_liveness_prompt_move_back)
        PositionGate.Hint.LOOK_STRAIGHT -> stringResource(R.string.trustsy_liveness_prompt_neutral)
        PositionGate.Hint.HOLD_STILL, PositionGate.Hint.READY -> stringResource(R.string.trustsy_liveness_hold_steady)
    }
    LivenessPhase.Starting -> stringResource(R.string.trustsy_liveness_hold_steady)
    is LivenessPhase.Challenge -> when {
        phase.submitting -> stringResource(R.string.trustsy_liveness_verifying)
        phase.returnToNeutral -> stringResource(R.string.trustsy_liveness_prompt_neutral)
        else -> stringResource(actionPrompt(phase.action))
    }
    else -> stringResource(R.string.trustsy_liveness_verifying)
}

private fun actionPrompt(action: String): Int = when (action) {
    "smile" -> R.string.trustsy_liveness_prompt_smile
    "blink" -> R.string.trustsy_liveness_prompt_blink
    "turn_left" -> R.string.trustsy_liveness_prompt_turn_left
    "turn_right" -> R.string.trustsy_liveness_prompt_turn_right
    "nod" -> R.string.trustsy_liveness_prompt_nod
    "head_shake" -> R.string.trustsy_liveness_prompt_head_shake
    "open_mouth" -> R.string.trustsy_liveness_prompt_open_mouth
    "move_closer" -> R.string.trustsy_liveness_prompt_move_closer
    "move_back" -> R.string.trustsy_liveness_prompt_move_back
    else -> R.string.trustsy_liveness_prompt_neutral
}

/**
 * The flash step (web `FlashStage.tsx`): full brightness, the server's colours
 * in order, a separate clip that starts with the first colour. Segments are
 * ≥ 450 ms (server-enforced), under WCAG 2.3.1's three flashes a second.
 */
@Composable
private fun FlashStage(sequence: List<FlashSegment>, recorder: CameraRecorder, onDone: () -> Unit) {
    val host = LocalHostContext.current
    var index by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val window = host.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }

    LaunchedEffect(sequence, skipped) {
        if (skipped) {
            recorder.stop()
            recorder.flashFile.delete() // the clip of a skipped sequence is not sent
            onDone()
            return@LaunchedEffect
        }
        // Colour 0 is already on screen in this frame: the clip may begin a
        // little after it (recorder latency), never before.
        recorder.startFlashClip()
        for ((i, segment) in sequence.withIndex()) {
            index = i
            delay(segment.ms.toLong())
        }
        delay(FLASH_TAIL_MS)
        recorder.stop()
        onDone()
    }

    Box(Modifier.fillMaxSize().background(paint(sequence.getOrNull(index)?.color))) {
        Text(
            stringResource(R.string.trustsy_liveness_flash_title),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        )
        TextButton(onClick = { skipped = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
            Text(stringResource(R.string.trustsy_liveness_flash_skip), color = Color.White)
        }
    }
}

private fun paint(color: String?): Color = when (color) {
    "red" -> Color(240, 40, 40)
    "green" -> Color(20, 200, 60)
    else -> Color(30, 60, 255)
}

/** Uploads the evidence and submits the run; the answer is the next snapshot. */
@Composable
private fun Submit(
    controller: FlowController,
    snapshot: Snapshot,
    liveness: LivenessController,
    recorder: CameraRecorder,
    selfie: AtomicReference<ByteArray?>,
) {
    fun submit() = controller.advance {
        recorder.stop()
        val photo = selfie.get() ?: throw java.io.IOException("no selfie was captured")
        val photoId = controller.api.upload(FileUpload(Images.base64(photo), "image/jpeg", "selfie.jpg")).fileId
        val videoId = uploadClip(controller, recorder.usable(recorder.runFile), "liveness.mp4")
        val flashId = uploadClip(controller, recorder.usable(recorder.flashFile), "liveness-flash.mp4")
        controller.api.submitLiveness(
            snapshot.id,
            LivenessSubmission(photoId, videoId, flashId, liveness.report()),
        )
    }
    LaunchedEffect(Unit) { submit() }
    if (controller.actionError != null && !controller.busy) {
        StepScaffold(
            title = stringResource(R.string.trustsy_liveness_error_submit),
            actions = { PrimaryButton(stringResource(R.string.trustsy_retry), onClick = { submit() }) },
        )
    } else {
        StatusScreen(stringResource(R.string.trustsy_submitting), null, null, {}, spinner = true)
    }
}

/**
 * A clip is supplementary evidence and optional on the wire: one too big for
 * the upload limit, or one the server refuses as media, is left out rather
 * than failing the run. A network or channel failure still fails the submit.
 */
private suspend fun uploadClip(controller: FlowController, file: File?, name: String): String? {
    if (file == null || file.length() > MAX_VIDEO_BYTES) return null
    val base64 = withContext(Dispatchers.IO) { Images.base64(file.readBytes()) }
    return try {
        controller.api.upload(FileUpload(base64, "video/mp4", name)).fileId
    } catch (e: ApiException) {
        if (e.status in 400..499) null else throw e
    }
}
