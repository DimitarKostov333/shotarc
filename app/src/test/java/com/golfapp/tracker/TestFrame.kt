package com.golfapp.tracker

import kotlin.math.sqrt

/** Builds I420 frames the same shape CameraX hands us, and runs the real detector over them. */
class TestFrame(val w: Int, val h: Int) {
    val y = ByteArray(w * h)
    val u = ByteArray(w / 2 * h / 2)
    val v = ByteArray(w / 2 * h / 2)
    private val detector = BallDetector()

    val uprightWidth get() = detector.uprightWidth
    val uprightHeight get() = detector.uprightHeight

    fun fill(colour: Triple<Int, Int, Int>) {
        y.fill(colour.first.toByte())
        u.fill(colour.second.toByte())
        v.fill(colour.third.toByte())
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, colour: Triple<Int, Int, Int>) {
        for (py in y0.coerceAtLeast(0)..y1.coerceAtMost(h - 1)) {
            for (px in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) paint(px, py, colour)
        }
    }

    fun disc(cx: Double, cy: Double, r: Double, colour: Triple<Int, Int, Int>) {
        val x0 = (cx - r).toInt().coerceAtLeast(0)
        val x1 = ((cx + r).toInt() + 1).coerceAtMost(w - 1)
        val y0 = (cy - r).toInt().coerceAtLeast(0)
        val y1 = ((cy + r).toInt() + 1).coerceAtMost(h - 1)
        for (py in y0..y1) for (px in x0..x1) {
            val dx = px - cx
            val dy = py - cy
            if (sqrt(dx * dx + dy * dy) <= r) paint(px, py, colour)
        }
    }

    fun detect(params: DetectorParams, rotation: Int = 0): List<Blob> =
        detector.detect(y, u, v, w, 1, w / 2, 1, w / 2, 1, w, h, rotation, params)

    fun detect(sensitivity: Int = 50, rotation: Int = 0, session: SessionSetup = YELLOW_DAY): List<Blob> =
        detect(DetectorParams.forSession(session, sensitivity), rotation)

    private fun paint(px: Int, py: Int, colour: Triple<Int, Int, Int>) {
        y[py * w + px] = colour.first.toByte()
        val ci = (py / 2) * (w / 2) + (px / 2)
        u[ci] = colour.second.toByte()
        v[ci] = colour.third.toByte()
    }
}
