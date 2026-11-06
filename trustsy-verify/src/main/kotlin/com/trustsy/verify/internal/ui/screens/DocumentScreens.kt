package com.trustsy.verify.internal.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trustsy.verify.R
import com.trustsy.verify.internal.flow.DocumentKind
import com.trustsy.verify.internal.flow.DocumentSide
import com.trustsy.verify.internal.flow.Documents
import com.trustsy.verify.internal.flow.Geometry
import com.trustsy.verify.internal.net.FileUpload
import com.trustsy.verify.internal.ui.ErrorText
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.LinkButton
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.SecondaryButton
import com.trustsy.verify.internal.ui.StatusScreen
import com.trustsy.verify.internal.ui.StepScaffold
import com.trustsy.verify.internal.ui.openAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `document{offered}` (web `FlowRunner` + `DocumentChoice`): one document goes
 * straight to its guide; several ask the issuing country first.
 */
@Composable
internal fun DocumentScreen(controller: FlowController, offered: List<DocumentKind>) {
    val single = offered.singleOrNull()
    val chosen = single ?: controller.documentKind?.takeIf { it in offered }
    if (chosen == null) {
        DocumentChoice(controller, offered)
        return
    }
    DocumentCaptureFlow(
        controller,
        kind = chosen,
        side = DocumentSide.FRONT,
        country = if (single != null) null else controller.documentCountry,
        showGuide = "document" !in controller.guided,
        onChangeDocument = if (offered.size > 1) ({ controller.documentKind = null }) else null,
    )
}

@Composable
private fun DocumentChoice(controller: FlowController, offered: List<DocumentKind>) {
    val kinds = Documents.documentsFor(controller.documentCountry).filter { it in offered }
    StepScaffold(title = stringResource(R.string.trustsy_document_choice_title)) {
        CountryField(
            stringResource(R.string.trustsy_document_choice_country),
            controller.documentCountry,
            stringResource(R.string.trustsy_document_choice_country),
        ) { controller.documentCountry = it }
        if (kinds.isEmpty()) Text(stringResource(R.string.trustsy_document_choice_none))
        for (kind in kinds) {
            Row(
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { controller.documentKind = kind }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(kindLabel(kind)), style = MaterialTheme.typography.titleMedium)
                Text("›", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

internal fun kindLabel(kind: DocumentKind): Int = when (kind) {
    DocumentKind.THAI_ID -> R.string.trustsy_document_kind_thai_id
    DocumentKind.DRIVING_LICENSE -> R.string.trustsy_document_kind_driving_license
    DocumentKind.PASSPORT -> R.string.trustsy_document_kind_passport
}

/** Guide (front only) → camera → review → upload + the event for this document and side. */
@Composable
internal fun DocumentCaptureFlow(
    controller: FlowController,
    kind: DocumentKind,
    side: DocumentSide,
    country: String?,
    showGuide: Boolean,
    onChangeDocument: (() -> Unit)?,
) {
    if (showGuide) {
        Guide(
            title = stringResource(guideTitle(kind)),
            lead = stringResource(guideLead(kind)),
            tips = guideTips(kind).map { stringResource(it) },
            onStart = { controller.guided += "document" },
            onChangeDocument = onChangeDocument,
        )
        return
    }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    val captured = photo
    if (captured == null) {
        CameraPermission {
            DocumentCamera(kind, side) { photo = it }
        }
        return
    }
    StepScaffold(
        title = stringResource(R.string.trustsy_document_review_title),
        actions = {
            controller.actionError?.let { ErrorText(stringResource(R.string.trustsy_error)) }
            PrimaryButton(
                if (controller.busy) stringResource(R.string.trustsy_submitting) else stringResource(R.string.trustsy_document_use_photo),
                onClick = {
                    controller.advance {
                        val jpeg = withContext(Dispatchers.Default) { Images.base64(Images.jpeg(captured)) }
                        val file = controller.api.upload(FileUpload(jpeg, "image/jpeg", Documents.fileNameFor(kind, side)))
                        val payload = buildJsonObject {
                            put("file_id", file.fileId)
                            put("side", side.wire)
                            if (country != null) put("country", country)
                        }
                        controller.api.event(Documents.eventFor(kind, side), payload)
                    }
                },
                busy = controller.busy,
            )
            SecondaryButton(stringResource(R.string.trustsy_document_retake), onClick = { photo = null }, enabled = !controller.busy)
        },
    ) {
        Image(captured.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
    }
}

/** A short "how to" before the camera (web `CaptureGuide`). */
@Composable
internal fun Guide(title: String, lead: String, tips: List<String>, onStart: () -> Unit, onChangeDocument: (() -> Unit)?) {
    StepScaffold(
        title = title,
        body = lead,
        actions = {
            PrimaryButton(stringResource(R.string.trustsy_guide_start), onClick = onStart)
            if (onChangeDocument != null) LinkButton(stringResource(R.string.trustsy_guide_change_document), onChangeDocument)
        },
    ) {
        for (tip in tips) Text("•  $tip", style = MaterialTheme.typography.bodyLarge)
    }
}

/** Asks for the camera, explains a refusal, and offers the OS settings. */
@Composable
internal fun CameraPermission(content: @Composable () -> Unit) {
    val host = LocalHostContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(host, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    when {
        granted -> content()
        denied -> StepScaffold(
            title = stringResource(R.string.trustsy_camera_denied),
            actions = {
                PrimaryButton(stringResource(R.string.trustsy_retry), onClick = { launcher.launch(Manifest.permission.CAMERA) })
                SecondaryButton(stringResource(R.string.trustsy_open_settings), onClick = { host.openAppSettings() })
            },
        )
        else -> StatusScreen(stringResource(R.string.trustsy_camera_starting), null, null, {}, spinner = true)
    }
}

/** Back camera with the card guide; the photo is cropped to exactly the guide. */
@Composable
private fun DocumentCamera(kind: DocumentKind, side: DocumentSide, onCaptured: (Bitmap) -> Unit) {
    val host = LocalHostContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(host).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val selector = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val capture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(selector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val aspect = Geometry.guideAspect(kind)

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(host)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = runCatching { future.get() }.getOrNull()
            val p = provider ?: run { failed = true; return@addListener }
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(host))
        onDispose { provider?.unbindAll() }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(stringResource(captureTitle(kind, side)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(captureHint(kind, side)), style = MaterialTheme.typography.bodyMedium)
            if (failed) ErrorText(stringResource(R.string.trustsy_error))
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged { box = it }) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            // Dim outside the guide; the guide itself is clear.
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val g = guideRect(size.width, size.height, aspect)
                drawRect(Color.Black.copy(alpha = 0.55f))
                drawRoundRect(Color.Transparent, g.first, g.second, CornerRadius(16.dp.toPx()), blendMode = BlendMode.Clear)
                drawRoundRect(Color.White, g.first, g.second, CornerRadius(16.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
        Box(Modifier.padding(24.dp)) {
            PrimaryButton(
                stringResource(R.string.trustsy_document_capture),
                busy = busy,
                enabled = !failed && box != IntSize.Zero,
                onClick = {
                    busy = true
                    capture.takePicture(ContextCompat.getMainExecutor(host), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bitmap = image.use { Images.upright(it) }
                            val (origin, size) = guideRect(box.width.toFloat(), box.height.toFloat(), aspect)
                            val rect = Geometry.coverCrop(
                                bitmap.width, bitmap.height, box.width.toFloat(), box.height.toFloat(),
                                origin.x, origin.y, size.width, size.height,
                            )
                            busy = false
                            onCaptured(Images.crop(bitmap, rect))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                            failed = true
                        }
                    })
                },
            )
        }
    }
}

/** The guide: 88% of the width, the document's own aspect, centred. */
private fun guideRect(w: Float, h: Float, aspect: Float): Pair<Offset, Size> {
    var gw = w * 0.88f
    var gh = gw / aspect
    if (gh > h * 0.8f) {
        gh = h * 0.8f
        gw = gh * aspect
    }
    return Offset((w - gw) / 2, (h - gh) / 2) to Size(gw, gh)
}

private fun guideTitle(kind: DocumentKind) = when (kind) {
    DocumentKind.THAI_ID -> R.string.trustsy_guide_document_title
    DocumentKind.DRIVING_LICENSE -> R.string.trustsy_guide_license_title
    DocumentKind.PASSPORT -> R.string.trustsy_guide_passport_title
}

private fun guideLead(kind: DocumentKind) = when (kind) {
    DocumentKind.THAI_ID -> R.string.trustsy_guide_document_lead
    DocumentKind.DRIVING_LICENSE -> R.string.trustsy_guide_license_lead
    DocumentKind.PASSPORT -> R.string.trustsy_guide_passport_lead
}

/** The web falls back to the card's tips where a document has none of its own. */
private fun guideTips(kind: DocumentKind) = when (kind) {
    DocumentKind.PASSPORT -> listOf(
        R.string.trustsy_guide_passport_tip_original,
        R.string.trustsy_guide_passport_tip_surface,
        R.string.trustsy_guide_document_tip_glare,
    )
    else -> listOf(
        R.string.trustsy_guide_document_tip_original,
        R.string.trustsy_guide_document_tip_surface,
        R.string.trustsy_guide_document_tip_glare,
    )
}

private fun captureTitle(kind: DocumentKind, side: DocumentSide) = when (kind) {
    DocumentKind.THAI_ID -> if (side == DocumentSide.FRONT) R.string.trustsy_document_front_title else R.string.trustsy_document_back_title
    DocumentKind.DRIVING_LICENSE ->
        if (side == DocumentSide.FRONT) R.string.trustsy_document_license_front_title else R.string.trustsy_document_license_back_title
    DocumentKind.PASSPORT -> R.string.trustsy_document_passport_title
}

private fun captureHint(kind: DocumentKind, side: DocumentSide) = when (kind) {
    DocumentKind.THAI_ID -> if (side == DocumentSide.FRONT) R.string.trustsy_document_front_hint else R.string.trustsy_document_back_hint
    DocumentKind.DRIVING_LICENSE ->
        if (side == DocumentSide.FRONT) R.string.trustsy_document_license_front_hint else R.string.trustsy_document_license_back_hint
    DocumentKind.PASSPORT -> R.string.trustsy_document_passport_hint
}
