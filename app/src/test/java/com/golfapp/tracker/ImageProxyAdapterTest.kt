package com.golfapp.tracker

import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Phones hand CameraX NV21-style frames: one Cr/Cb buffer read through a pixel stride of two,
 * with the V plane starting one byte before the U plane. Getting that indexing wrong is silent,
 * so the adapter gets its own frame.
 */
class ImageProxyAdapterTest {

    private val w = 640
    private val h = 480

    @Test
    fun readsInterleavedChromaPlanes() {
        val y = ByteArray(w * h) { GRASS.first.toByte() }
        val vu = ByteArray(w * h / 2)                       // interleaved V,U at half resolution
        for (i in vu.indices step 2) {
            vu[i] = GRASS.third.toByte()
            vu[i + 1] = GRASS.second.toByte()
        }

        val ballX = 250f
        val ballY = 180f
        val ballR = 14f
        for (py in 0 until h) for (px in 0 until w) {
            val dx = px - ballX
            val dy = py - ballY
            if (sqrt(dx * dx + dy * dy) > ballR) continue
            y[py * w + px] = OPTIC_YELLOW.first.toByte()
            val ci = (py / 2) * w + (px / 2) * 2
            vu[ci] = OPTIC_YELLOW.third.toByte()
            vu[ci + 1] = OPTIC_YELLOW.second.toByte()
        }

        val vBuffer = ByteBuffer.wrap(vu)
        val uBuffer = ByteBuffer.wrap(vu).also { it.position(1) }.slice()
        val image = FakeImageProxy(
            w, h, rotation = 90,
            planes = arrayOf(
                FakePlane(ByteBuffer.wrap(y), rowStride = w, pixelStride = 1),
                FakePlane(uBuffer, rowStride = w, pixelStride = 2),
                FakePlane(vBuffer, rowStride = w, pixelStride = 2),
            ),
        )

        val blob = BallDetector().detect(image, DetectorParams.forSession(YELLOW_DAY, 50)).single()
        // rotated 90 degrees clockwise for display: (x, y) -> (h - y, x)
        assertEquals(h - ballY, blob.x, 2f)
        assertEquals(ballX, blob.y, 2f)
        assertEquals(ballR, blob.radius, 2f)
    }
}

private class FakePlane(
    private val buffer: ByteBuffer,
    private val rowStride: Int,
    private val pixelStride: Int,
) : ImageProxy.PlaneProxy {
    override fun getBuffer(): ByteBuffer = buffer
    override fun getRowStride(): Int = rowStride
    override fun getPixelStride(): Int = pixelStride
}

private class FakeImageProxy(
    private val width: Int,
    private val height: Int,
    private val rotation: Int,
    private val planes: Array<ImageProxy.PlaneProxy>,
) : ImageProxy {
    override fun getWidth() = width
    override fun getHeight() = height
    override fun getFormat() = android.graphics.ImageFormat.YUV_420_888
    override fun getPlanes() = planes
    override fun getImageInfo(): ImageInfo = FakeImageInfo(rotation)
    override fun getCropRect() = Rect(0, 0, width, height)
    override fun setCropRect(rect: Rect?) = Unit
    override fun close() = Unit
    override fun getImage() = null
}

private class FakeImageInfo(private val rotation: Int) : ImageInfo {
    override fun getTimestamp() = 0L
    override fun getRotationDegrees() = rotation
    override fun getTagBundle() = throw UnsupportedOperationException()
    override fun getSensorToBufferTransformMatrix() = throw UnsupportedOperationException()
    override fun populateExifData(builder: androidx.camera.core.impl.utils.ExifData.Builder) = Unit
}
