package com.golfapp.tracker

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Mean colour of a blob, in the camera's own YCbCr. */
data class ColourSample(val y: Int, val cb: Int, val cr: Int)

/** A yellow blob, in upright image pixels. */
data class Blob(
    val x: Float,
    val y: Float,
    /** Ball radius in image pixels, read off the blob's width rather than its length. */
    val radius: Float,
    val area: Int,
    val fill: Float,
    val aspect: Float,
    val colour: ColourSample,
    /** How much of the ring around the blob is grass — 1 means the ball is sitting on the mat. */
    val groundFraction: Float,
)

/**
 * Which pixels count as the ball: a circle in the Cb/Cr plane around the colour being looked for,
 * plus a brightness floor. Either the nominal window for the ball in play, or one refitted around
 * the ball's own measured colour once it has been found sitting on the grass.
 */
class DetectorParams private constructor(
    val targetCb: Int,
    val targetCr: Int,
    val spread: Int,
    val lumaMin: Int,
    /** Upper luma bound. 255 for a ball found by being bright; a dark ball is found by a ceiling. */
    val lumaMax: Int = 255,
) {
    val spreadSquared = spread * spread

    companion object {
        fun forSession(session: SessionSetup, sensitivity: Int): DetectorParams {
            val t = sensitivity.coerceIn(0, 100) / 100f
            val spread = (session.spread * (0.6f + 1.4f * t)).toInt().coerceAtLeast(8)
            // A dark ball has no colour and little light, so it is found by chroma near neutral and
            // a luma CEILING rather than a floor; sensitivity lifts the ceiling to reach a matt ball
            // in shadow instead of dropping a floor towards it.
            if (session.ball.dark) {
                return DetectorParams(
                    targetCb = session.ball.cb,
                    targetCr = session.ball.cr,
                    spread = spread,
                    lumaMin = 0,
                    lumaMax = (session.lumaFloor * (0.7f + 0.6f * t)).toInt().coerceIn(24, 150),
                )
            }
            return DetectorParams(
                targetCb = session.ball.cb,
                targetCr = session.ball.cr,
                spread = spread,
                lumaMin = (session.lumaFloor * (1.25f - 0.5f * t)).toInt().coerceAtLeast(20),
            )
        }

        /**
         * Light changes the ball: tungsten drags it towards orange, LED towards green, low sun
         * warms everything, far enough to fall outside any nominal window. Once the ball has been
         * measured at address the window is rebuilt around what this light does to it — tighter
         * than the nominal one, because it is now centred on the real thing, and opened up for
         * the blur and dimming of a ball in flight.
         */
        fun around(sample: ColourSample, session: SessionSetup): DetectorParams {
            // Deliberately not widened by the sensitivity slider. That slider exists to find the
            // ball; once its colour is known the only thing left to allow for is the blur and
            // dimming of flight. Opening up further just lets the mat in — indoors a green mat
            // sits closer to an LED-lit ball than the ball does to its own nominal colour.
            val spread = (session.ball.spread * 0.9f).toInt().coerceAtLeast(12)
            // A dark ball keeps its ceiling once measured — set a touch above the ball's own luma so
            // flight, which only dims it further, stays inside, while lit background stays out.
            if (session.ball.dark) {
                return DetectorParams(
                    targetCb = sample.cb, targetCr = sample.cr, spread = spread,
                    lumaMin = 0,
                    lumaMax = (sample.y * 1.7f + 18f).toInt().coerceIn(30, 150),
                )
            }
            return DetectorParams(
                targetCb = sample.cb, targetCr = sample.cr, spread = spread,
                lumaMin = (sample.y * 0.4f).toInt().coerceAtLeast(20),
            )
        }
    }
}

/**
 * Finds ball-coloured blobs in a YUV_420_888 frame, and notes how much grass surrounds each one. Works
 * on the chroma planes, which are already half resolution, so a 1280x720 frame costs one pass
 * over 230k pixels.
 */
class BallDetector {

    private companion object {
        const val QUANTISATION_BIAS = 0.375f
        const val LUMA_BIAS = 0.05f
        const val MIN_LUMA_CONTRAST = 25

        /** Grass has neither red nor blue in it, which puts Cr below Cb and both under 128. */
        const val GREEN_CR_MAX = 122
        const val GREEN_CB_MAX = 132
        const val GREEN_Y_MIN = 25
    }

    private var cw = 0
    private var ch = 0
    private var mask = ByteArray(0)
    private var green = ByteArray(0)
    private var stack = IntArray(0)
    private var yBuf = ByteArray(0)
    private var uBuf = ByteArray(0)
    private var vBuf = ByteArray(0)

    private var planeY = ByteArray(0)
    private var planeU = ByteArray(0)
    private var planeV = ByteArray(0)
    private var yRowStride = 0
    private var yPixStride = 0
    private var uRowStride = 0
    private var uPixStride = 0
    private var vRowStride = 0
    private var vPixStride = 0

    var uprightWidth = 0
        private set
    var uprightHeight = 0
        private set

    fun detect(image: ImageProxy, p: DetectorParams, maxBlobs: Int = 24): List<Blob> {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        yBuf = copyPlane(y.buffer, yBuf)
        uBuf = copyPlane(u.buffer, uBuf)
        vBuf = copyPlane(v.buffer, vBuf)
        return detect(
            yBuf, uBuf, vBuf,
            y.rowStride, y.pixelStride, u.rowStride, u.pixelStride, v.rowStride, v.pixelStride,
            image.width, image.height, image.imageInfo.rotationDegrees, p, maxBlobs,
        )
    }

    fun detect(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yRow: Int, yPix: Int, uRow: Int, uPix: Int, vRow: Int, vPix: Int,
        w: Int, h: Int, rotation: Int, p: DetectorParams, maxBlobs: Int = 24,
    ): List<Blob> {
        if (rotation == 90 || rotation == 270) {
            uprightWidth = h; uprightHeight = w
        } else {
            uprightWidth = w; uprightHeight = h
        }
        planeY = y; planeU = u; planeV = v
        yRowStride = yRow; yPixStride = yPix
        uRowStride = uRow; uPixStride = uPix
        vRowStride = vRow; vPixStride = vPix

        val cwN = w / 2
        val chN = h / 2
        if (cwN != cw || chN != ch) {
            cw = cwN; ch = chN
            mask = ByteArray(cw * ch)
            green = ByteArray(cw * ch)
            stack = IntArray(cw * ch)
        } else {
            java.util.Arrays.fill(mask, 0)
            java.util.Arrays.fill(green, 0)
        }

        for (cy in 0 until ch) {
            val uLine = cy * uRow
            val vLine = cy * vRow
            val yLine = (cy * 2) * yRow
            var mi = cy * cw
            for (cx in 0 until cw) {
                val cb = u[uLine + cx * uPix].toInt() and 0xFF
                val cr = v[vLine + cx * vPix].toInt() and 0xFF
                val luma = y[yLine + (cx * 2) * yPix].toInt() and 0xFF
                val dcb = cb - p.targetCb
                val dcr = cr - p.targetCr
                if (dcb * dcb + dcr * dcr <= p.spreadSquared) {
                    if (luma >= p.lumaMin && luma <= p.lumaMax) mask[mi] = 1
                } else if (cr <= GREEN_CR_MAX && cb <= GREEN_CB_MAX && cr <= cb + 4 && luma >= GREEN_Y_MIN) {
                    green[mi] = 1
                }
                mi++
            }
        }

        return growBlobs(maxBlobs, rotation, w, h)
    }

    private fun growBlobs(maxBlobs: Int, rotation: Int, w: Int, h: Int): List<Blob> {
        val minArea = 2
        val maxArea = cw * ch / 8
        val blobs = ArrayList<Blob>()

        for (seed in mask.indices) {
            if (mask[seed] != 1.toByte()) continue
            var sp = 0
            stack[sp++] = seed
            mask[seed] = 2
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var sumLuma = 0L
            var sumCb = 0L
            var sumCr = 0L
            var minX = cw; var maxX = 0; var minY = ch; var maxY = 0

            while (sp > 0) {
                val idx = stack[--sp]
                val px = idx % cw
                val py = idx / cw
                area++
                sumX += px; sumY += py
                sumLuma += planeY[(py * 2) * yRowStride + (px * 2) * yPixStride].toInt() and 0xFF
                sumCb += planeU[py * uRowStride + px * uPixStride].toInt() and 0xFF
                sumCr += planeV[py * vRowStride + px * vPixStride].toInt() and 0xFF
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py

                if (px > 0 && mask[idx - 1] == 1.toByte()) { mask[idx - 1] = 2; stack[sp++] = idx - 1 }
                if (px < cw - 1 && mask[idx + 1] == 1.toByte()) { mask[idx + 1] = 2; stack[sp++] = idx + 1 }
                if (py > 0 && mask[idx - cw] == 1.toByte()) { mask[idx - cw] = 2; stack[sp++] = idx - cw }
                if (py < ch - 1 && mask[idx + cw] == 1.toByte()) { mask[idx + cw] = 2; stack[sp++] = idx + cw }
            }

            if (area < minArea || area > maxArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val fill = area.toFloat() / (bw * bh)
            val aspect = max(bw, bh).toFloat() / min(bw, bh)
            if (fill < 0.45f || aspect > 4f) continue

            // chroma pixels are half-size in each axis, so everything doubles back to image pixels
            val cxF = sumX.toFloat() / area
            val cyF = sumY.toFloat() / area
            val (ux, uy) = toUpright(cxF * 2f + 0.5f, cyF * 2f + 0.5f, rotation, w, h)
            val chromaRadius = radiusOf(area, max(bw, bh))
            val meanLuma = (sumLuma.toFloat() / area).roundToInt()
            blobs.add(
                Blob(
                    x = ux,
                    y = uy,
                    radius = if (aspect <= 1.4f && area >= 8) {
                        sharpenRadius(minX, minY, maxX, maxY, meanLuma, chromaRadius, w, h)
                    } else {
                        chromaRadius
                    },
                    area = area,
                    fill = fill,
                    aspect = aspect,
                    colour = ColourSample(
                        meanLuma,
                        (sumCb.toFloat() / area).roundToInt(),
                        (sumCr.toFloat() / area).roundToInt(),
                    ),
                    groundFraction = grassAround(minX, minY, maxX, maxY),
                )
            )
        }

        blobs.sortByDescending { it.area }
        return if (blobs.size > maxBlobs) blobs.subList(0, maxBlobs) else blobs
    }


    /**
     * Re-measures a round blob against the full-resolution luma plane. Chroma is half resolution,
     * so a ball at address is only a handful of cells across and its radius lands within about a
     * tenth of itself depending on where the ball happens to sit inside the chroma grid. Every
     * distance in the shot is anchored to that one number, so it is worth the extra 300 pixels.
     */
    private fun sharpenRadius(
        minX: Int, minY: Int, maxX: Int, maxY: Int,
        blobLuma: Int, chromaRadius: Float, w: Int, h: Int,
    ): Float {
        val x0 = max(0, (minX - 2) * 2)
        val x1 = min(w - 1, (maxX + 3) * 2 - 1)
        val y0 = max(0, (minY - 2) * 2)
        val y1 = min(h - 1, (maxY + 3) * 2 - 1)
        val ballX0 = minX * 2; val ballX1 = maxX * 2 + 1
        val ballY0 = minY * 2; val ballY1 = maxY * 2 + 1

        var background = 0L
        var backgroundCount = 0
        for (py in y0..y1) {
            val line = py * yRowStride
            for (px in x0..x1) {
                if (px in ballX0..ballX1 && py in ballY0..ballY1) continue
                background += planeY[line + px * yPixStride].toInt() and 0xFF
                backgroundCount++
            }
        }
        if (backgroundCount < 8) return chromaRadius
        val bg = background / backgroundCount
        val contrast = blobLuma - bg
        if (contrast > -MIN_LUMA_CONTRAST && contrast < MIN_LUMA_CONTRAST) return chromaRadius

        val threshold = (blobLuma + bg) / 2
        var ballPixels = 0
        for (py in y0..y1) {
            val line = py * yRowStride
            for (px in x0..x1) {
                val luma = planeY[line + px * yPixStride].toInt() and 0xFF
                if (if (contrast > 0) luma >= threshold else luma <= threshold) ballPixels++
            }
        }
        val refined = sqrt(ballPixels / Math.PI.toFloat()) - LUMA_BIAS
        // anything wildly off is the background bleeding in, not a better measurement
        return if (abs(refined - chromaRadius) > 0.4f * chromaRadius + 2f) chromaRadius else max(0.5f, refined)
    }

    /**
     * Fraction of grass in the ring around a blob. A ball at address is ringed by the mat; a ball
     * in flight, against a net or the sky, is not.
     */
    private fun grassAround(minX: Int, minY: Int, maxX: Int, maxY: Int): Float {
        val padX = max(2, (maxX - minX + 1))
        val padY = max(2, (maxY - minY + 1))
        val x0 = max(0, minX - padX); val x1 = min(cw - 1, maxX + padX)
        val y0 = max(0, minY - padY); val y1 = min(ch - 1, maxY + padY)
        var sampled = 0
        var grass = 0
        for (py in y0..y1) {
            val line = py * cw
            for (px in x0..x1) {
                if (px in minX..maxX && py in minY..maxY) continue
                sampled++
                if (green[line + px] == 1.toByte()) grass++
            }
        }
        return if (sampled < 4) 0f else grass.toFloat() / sampled
    }

    /**
     * A ball in flight smears into a streak, so length is meaningless but width is not. Treating
     * the blob as a stadium (a rectangle capped with half discs) recovers the same radius from a
     * round ball and from a smeared one: area = pi*r^2 + 2r(major - 2r).
     * QUANTISATION_BIAS is the half-cell of yellow a blob picks up around its edge at chroma
     * resolution, measured against rendered discs.
     */
    private fun radiusOf(area: Int, major: Int): Float {
        val m = major.toFloat()
        val discriminant = m * m - 0.858f * area
        val rChroma = if (discriminant >= 0f) {
            (m - sqrt(discriminant)) / 0.858f
        } else {
            sqrt(area / Math.PI.toFloat())
        }
        return max(0.5f, (rChroma - QUANTISATION_BIAS) * 2f)
    }

    private fun toUpright(x: Float, y: Float, rotation: Int, w: Int, h: Int): Pair<Float, Float> =
        when (rotation) {
            90 -> Pair(h - y, x)
            180 -> Pair(w - x, h - y)
            270 -> Pair(y, w - x)
            else -> Pair(x, y)
        }

    private fun copyPlane(buffer: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
        val dup = buffer.duplicate()
        dup.rewind()
        val n = dup.remaining()
        val out = if (reuse.size >= n) reuse else ByteArray(n)
        dup.get(out, 0, n)
        return out
    }
}
