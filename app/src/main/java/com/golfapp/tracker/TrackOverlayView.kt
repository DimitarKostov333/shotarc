package com.golfapp.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Draws the tracked ball over the preview. Coordinates arrive in upright image pixels and are
 * mapped the same way PreviewView's FILL_CENTER scales the preview, so the two line up.
 */
class TrackOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var frame: TrackerFrame? = null
    var showDebug = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(10f)
        color = Color.argb(70, 232, 255, 0)
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3f)
        color = Color.rgb(232, 255, 0)
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.WHITE
    }
    private val teePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.rgb(120, 255, 160)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 255, 0) }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x2EFFFFFF; strokeWidth = dp(1f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(10f)), 0f)
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF78FFA0.toInt(); strokeWidth = dp(2f)
    }
    private val reticleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x4D78FFA0; strokeWidth = dp(1f)
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF78FFA0.toInt(); strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(180, 0, 200, 255)
    }
    private val debugText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 200, 255)
        textSize = dp(11f)
    }

    private val drawPath = Path()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    fun submit(f: TrackerFrame) {
        frame = f
        invalidate()
    }

    fun clear() {
        frame = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        if (f.imgW == 0 || f.imgH == 0) return
        scale = max(width.toFloat() / f.imgW, height.toFloat() / f.imgH)
        offX = (width - f.imgW * scale) / 2f
        offY = (height - f.imgH * scale) / 2f

        if (showDebug) {
            for (b in f.candidates) {
                canvas.drawCircle(mx(b.x), my(b.y), max(dp(5f), b.radius * scale), debugPaint)
            }
            canvas.drawText("blobs ${f.candidates.size}", dp(12f), height - dp(12f), debugText)
        }

        // target line down the middle of the frame
        if (f.state != TrackState.RESULT) {
            canvas.drawLine(width / 2f, dp(8f), width / 2f, height - dp(8f), targetPaint)
        }

        // lock reticle on the ball at address
        f.tee?.let { tee ->
            if (f.state == TrackState.READY || f.state == TrackState.SEARCHING) {
                val cx = mx(tee.x); val cy = my(tee.y)
                val locked = f.state == TrackState.READY
                val r = dp(34f)
                reticlePaint.color = if (locked) 0xFF78FFA0.toInt() else 0x8078FFA0.toInt()
                canvas.drawCircle(cx, cy, r, reticlePaint)
                canvas.drawCircle(cx, cy, dp(46f), reticleRing)
                val b = dp(12f)
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                    val x = cx + sx * r; val y = cy + sy * r
                    canvas.drawLine(x, y, x - sx * b, y, bracketPaint)
                    canvas.drawLine(x, y, x, y - sy * b, bracketPaint)
                }
            }
        }

        if (f.path.size >= 2) {
            drawPath.reset()
            drawPath.moveTo(mx(f.path[0].x), my(f.path[0].y))
            for (i in 1 until f.path.size) drawPath.lineTo(mx(f.path[i].x), my(f.path[i].y))
            canvas.drawPath(drawPath, glowPaint)
            canvas.drawPath(drawPath, pathPaint)
            for (p in f.path) canvas.drawCircle(mx(p.x), my(p.y), dp(2.5f), dotPaint)
        }

        f.ball?.let { b ->
            canvas.drawCircle(mx(b.x), my(b.y), max(dp(10f), b.radius * scale * 2f), ballPaint)
        }
    }

    private fun mx(x: Float) = x * scale + offX
    private fun my(y: Float) = y * scale + offY
}
