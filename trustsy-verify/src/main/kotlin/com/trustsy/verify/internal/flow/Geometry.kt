package com.trustsy.verify.internal.flow

import kotlin.math.max
import kotlin.math.roundToInt

/** A rectangle in an image's own pixel space. */
internal data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal object Geometry {
    /**
     * The web app's `capture.ts::coverCropRect`: map a guide drawn over a
     * centre-cropped ("cover") preview back into the image's pixels, so the
     * saved photo is exactly what sat inside the on-screen frame. Clamped to
     * the image.
     */
    fun coverCrop(
        imageW: Int, imageH: Int,
        boxW: Float, boxH: Float,
        guideX: Float, guideY: Float, guideW: Float, guideH: Float,
    ): CropRect {
        if (imageW <= 0 || imageH <= 0 || boxW <= 0f || boxH <= 0f) return CropRect(0, 0, max(1, imageW), max(1, imageH))
        val scale = max(boxW / imageW, boxH / imageH)
        val offsetX = (boxW - imageW * scale) / 2
        val offsetY = (boxH - imageH * scale) / 2
        val sx = ((guideX - offsetX) / scale).coerceIn(0f, imageW.toFloat())
        val sy = ((guideY - offsetY) / scale).coerceIn(0f, imageH.toFloat())
        val sw = (guideW / scale).coerceIn(1f, imageW - sx)
        val sh = (guideH / scale).coerceIn(1f, imageH - sy)
        return CropRect(sx.roundToInt(), sy.roundToInt(), max(1, sw.roundToInt()).coerceAtMost(imageW - sx.roundToInt()), max(1, sh.roundToInt()).coerceAtMost(imageH - sy.roundToInt()))
    }

    /** Scale so the long edge is at most [maxEdge]; never upscales. */
    fun fit(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxEdge) return width to height
        val s = maxEdge.toFloat() / long
        return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
    }

    /** ID-1 cards are 85.6 × 53.98 mm; a passport data page is close to 125 × 88 mm. */
    fun guideAspect(kind: DocumentKind): Float = if (kind == DocumentKind.PASSPORT) 125f / 88f else 85.6f / 53.98f
}
