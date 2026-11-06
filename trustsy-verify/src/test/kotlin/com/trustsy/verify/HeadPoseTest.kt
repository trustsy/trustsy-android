package com.trustsy.verify

import com.trustsy.verify.internal.liveness.FaceObservation
import com.trustsy.verify.internal.liveness.FrameMapper
import com.trustsy.verify.internal.liveness.HeadPoseMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * MediaPipe's metric space is right-handed with the camera at the origin
 * looking down −Z, +X to the image's right, +Y up; the canonical face looks
 * along +Z (at the camera). In an UNMIRRORED frame — what both CameraX's
 * analysis stream and the browser's raw video deliver — the applicant's own
 * left is the image's right, so turning to their own left swings the nose from
 * +Z toward +X: a rotation about Y by a POSITIVE angle.
 */
class HeadPoseTest {

    /** Column-major 4x4 from a row-major 3x3 rotation. */
    private fun columnMajor(r: Array<DoubleArray>): FloatArray {
        val out = FloatArray(16)
        for (row in 0..2) for (col in 0..2) out[col * 4 + row] = r[row][col].toFloat()
        out[15] = 1f
        return out
    }

    private fun rotY(deg: Double): Array<DoubleArray> {
        val t = Math.toRadians(deg)
        return arrayOf(doubleArrayOf(cos(t), 0.0, sin(t)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(t), 0.0, cos(t)))
    }

    private fun rotX(deg: Double): Array<DoubleArray> {
        val t = Math.toRadians(deg)
        return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(t), -sin(t)), doubleArrayOf(0.0, sin(t), cos(t)))
    }

    private fun rotZ(deg: Double): Array<DoubleArray> {
        val t = Math.toRadians(deg)
        return arrayOf(doubleArrayOf(cos(t), -sin(t), 0.0), doubleArrayOf(sin(t), cos(t), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    }

    @Test
    fun `the nose of a +Y rotation points to the image right`() {
        // Sanity of the geometry the next test relies on.
        val r = rotY(25.0)
        val nose = doubleArrayOf(r[0][2], r[1][2], r[2][2]) // R · (0,0,1)
        assertTrue("x > 0", nose[0] > 0)
    }

    @Test
    fun `turning to the applicant's own left reads positive yaw`() {
        val pose = HeadPoseMath.eulerFromMatrix(columnMajor(rotY(25.0)))
        assertEquals(25.0, pose.yaw, 1e-4)
        assertEquals(0.0, pose.pitch, 1e-4)
        assertEquals(0.0, pose.roll, 1e-4)
    }

    @Test
    fun `turning to the applicant's own right reads negative yaw`() {
        assertEquals(-30.0, HeadPoseMath.eulerFromMatrix(columnMajor(rotY(-30.0))).yaw, 1e-4)
    }

    @Test
    fun `pitch and roll come out of their own axes`() {
        val pitched = HeadPoseMath.eulerFromMatrix(columnMajor(rotX(15.0)))
        assertEquals(15.0, pitched.pitch, 1e-4)
        assertEquals(0.0, pitched.yaw, 1e-4)
        val rolled = HeadPoseMath.eulerFromMatrix(columnMajor(rotZ(10.0)))
        assertEquals(10.0, rolled.roll, 1e-4)
        assertEquals(0.0, rolled.yaw, 1e-4)
    }

    @Test
    fun `identity is straight ahead`() {
        val pose = HeadPoseMath.eulerFromMatrix(columnMajor(rotY(0.0)))
        assertEquals(0.0, pose.yaw, 1e-9)
        assertEquals(0.0, pose.pitch, 1e-9)
        assertEquals(0.0, pose.roll, 1e-9)
    }

    // ---- frame mapping -------------------------------------------------------

    private fun face(blend: Map<String, Float>, matrix: FloatArray? = columnMajor(rotY(10.0))) = FaceObservation(
        landmarksPx = listOf(100f to 50f, 400f to 50f, 100f to 450f, 400f to 450f),
        blendshapes = blend,
        matrix = matrix,
    )

    @Test
    fun `blendshapes map onto the server's fields`() {
        val frame = FrameMapper.map(
            face(
                mapOf(
                    "eyeBlinkLeft" to 0.2f, "eyeBlinkRight" to 0.4f,
                    "mouthSmileLeft" to 0.6f, "mouthSmileRight" to 0.8f,
                    "jawOpen" to 0.5f,
                ),
            ),
            tMs = 1234,
        )!!
        assertEquals(0.7, frame.eyeOpen!!, 1e-6) // 1 - mean(0.2, 0.4)
        assertEquals(0.7, frame.smile!!, 1e-6) // mean(0.6, 0.8)
        assertEquals(0.5, frame.mouthOpen!!, 1e-6)
        assertEquals(500.0, frame.faceSize, 1e-6) // hypot(300, 400)
        assertEquals(10.0, frame.yaw, 1e-4)
        assertEquals(1234L, frame.tMs)
    }

    @Test
    fun `a blendshape the model did not give stays absent`() {
        val frame = FrameMapper.map(face(emptyMap()), tMs = 0)!!
        assertNull(frame.eyeOpen)
        assertNull(frame.smile)
        assertNull(frame.mouthOpen)
    }

    @Test
    fun `no matrix means no frame, never a constant pose`() {
        assertNull(FrameMapper.map(face(emptyMap(), matrix = null), tMs = 0))
    }
}
