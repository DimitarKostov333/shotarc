package com.golfapp.tracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A golf ball that turns. Dimples are laid out evenly on a sphere and spun about a tilted axis,
 * so the front face rolls the way a real ball does — no texture, just projected geometry.
 */
class BallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var ballColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    private val dimples = fibonacciSphere(150)
    private val tilt = Math.toRadians(18.0)
    private var angle = 0f

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimplePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 0, 0, 0) }

    private val spin = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 4200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); spin.start() }
    override fun onDetachedFromWindow() { spin.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.82f

        // grounding shadow
        canvas.drawOval(cx - r * 0.85f, cy + r * 0.92f, cx + r * 0.85f, cy + r * 1.12f, shadowPaint)

        // the ball body: a lit sphere, highlight up and to the left
        ballPaint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
            intArrayOf(lighten(ballColor, 0.55f), ballColor, darken(ballColor, 0.4f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, ballPaint)

        // dimples on the front hemisphere, spun about a tilted axis
        val ct = cos(tilt); val st = sin(tilt)
        val ca = cos(angle.toDouble()); val sa = sin(angle.toDouble())
        val dimpleR = r * 0.075f
        val shade = darken(ballColor, 0.28f)
        for (p in dimples) {
            // spin about Y
            val x1 = p.x * ca + p.z * sa
            val z1 = -p.x * sa + p.z * ca
            val y1 = p.y
            // tilt the whole ball forward about X so the top is visible
            val y2 = y1 * ct - z1 * st
            val z2 = y1 * st + z1 * ct
            if (z2 <= 0.05) continue                     // back face, skip
            val sx = cx + x1.toFloat() * r
            val sy = cy - y2.toFloat() * r
            val depth = (0.45f + 0.55f * z2.toFloat())
            dimplePaint.color = shade
            dimplePaint.alpha = (70 + 150 * z2).toInt().coerceIn(0, 220)
            canvas.drawCircle(sx, sy, dimpleR * depth, dimplePaint)
        }
    }

    private data class P(val x: Double, val y: Double, val z: Double)

    private fun fibonacciSphere(n: Int): List<P> {
        val golden = Math.PI * (3.0 - sqrt(5.0))
        return (0 until n).map { i ->
            val y = 1 - (i / (n - 1.0)) * 2
            val rad = sqrt(1 - y * y)
            val theta = golden * i
            P(cos(theta) * rad, y, sin(theta) * rad)
        }
    }

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255),
    )

    private fun darken(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * (1 - f)).toInt(),
        (Color.green(c) * (1 - f)).toInt(),
        (Color.blue(c) * (1 - f)).toInt(),
    )
}
