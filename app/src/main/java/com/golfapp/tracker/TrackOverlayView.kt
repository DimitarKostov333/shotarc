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

        f.tee?.let { tee ->
            if (f.state == TrackState.READY || f.state == TrackState.SEARCHING) {
                val r = max(dp(14f), tee.radius * scale * 1.8f)
                canvas.drawCircle(mx(tee.x), my(tee.y), r, teePaint)
                canvas.drawLine(mx(tee.x) - r - dp(6f), my(tee.y), mx(tee.x) - r, my(tee.y), teePaint)
                canvas.drawLine(mx(tee.x) + r, my(tee.y), mx(tee.x) + r + dp(6f), my(tee.y), teePaint)
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
