package com.trustsy.verify.internal.ui.screens

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageProxy
import com.trustsy.verify.internal.flow.CropRect
import com.trustsy.verify.internal.flow.Geometry
import java.io.ByteArrayOutputStream

internal object Images {
    /** Contract: JPEG ~0.92, long edge ≤ 2048 px. */
    const val JPEG_QUALITY = 92
    const val MAX_EDGE = 2048

    /** The frame upright, as the applicant saw it. */
    fun upright(image: ImageProxy): Bitmap {
        val bitmap = image.toBitmap()
        val degrees = image.imageInfo.rotationDegrees
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    fun crop(bitmap: Bitmap, rect: CropRect): Bitmap =
        Bitmap.createBitmap(bitmap, rect.x, rect.y, rect.width, rect.height)

    /** JPEG bytes, scaled so the long edge is at most [maxEdge]. */
    fun jpeg(bitmap: Bitmap, maxEdge: Int = MAX_EDGE): ByteArray {
        val (w, h) = Geometry.fit(bitmap.width, bitmap.height, maxEdge)
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
