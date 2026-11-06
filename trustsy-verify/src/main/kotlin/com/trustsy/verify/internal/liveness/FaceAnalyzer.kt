package com.trustsy.verify.internal.liveness

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaPipe Face Landmarker, configured as the web app configures it
 * (`face-mesh-loader.ts`): blendshapes and the facial transformation matrix on,
 * 0.5 confidences, CPU. `numFaces = 2` so a second face can be NOTICED — the
 * positioning gate wants exactly one.
 *
 * The model is read into a direct buffer rather than opened by path: an asset
 * the host app's build compresses cannot be memory-mapped, and a library
 * cannot set the app's `noCompress`.
 */
internal class FaceAnalyzer(context: Context) : Closeable {

    class Detection(val faces: Int, val first: FaceObservation?)

    private val landmarker: FaceLandmarker = run {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buffer.rewind()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(2)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        FaceLandmarker.createFromOptions(context, options)
    }

    /**
     * @param bitmap the UPRIGHT, unmirrored frame (ARGB_8888).
     * @param timestampMs strictly increasing across calls (VIDEO mode).
     */
    fun detect(bitmap: Bitmap, timestampMs: Long): Detection {
        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), timestampMs)
        val faces = result.faceLandmarks()
        val landmarks = faces.firstOrNull() ?: return Detection(0, null)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val blendshapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
            ?.associate { it.categoryName() to it.score() }
            .orEmpty()
        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        return Detection(
            faces.size,
            FaceObservation(
                landmarksPx = landmarks.map { it.x() * w to it.y() * h },
                blendshapes = blendshapes,
                matrix = matrix,
            ),
        )
    }

    override fun close() = landmarker.close()

    companion object {
        const val MODEL_ASSET = "trustsy/face_landmarker.task"
    }
}
