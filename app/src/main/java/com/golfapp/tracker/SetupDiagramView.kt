package com.golfapp.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** The "how to stand behind the ball" diagram: phone, its field of view, the ball on grass. */
class SetupDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0AFFFFFF }
    private val target = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x40FFFFFF; strokeWidth = dp(1.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(8f)), 0f)
    }
    private val fovFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FE8FF00 }
    private val fovStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x73E8FF00; strokeWidth = dp(1f)
    }
    private val phoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B100C.toInt() }
    private val phoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x80FFFFFF.toInt(); strokeWidth = dp(1f)
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val grass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF78FFA0.toInt(); strokeWidth = dp(1.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = dp(1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE; textSize = dp(10f); letterSpacing = 0.1f
    }
    private val fov = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bg.shader = LinearGradient(0f, 0f, 0f, h,
            0xFF123F2F.toInt(), 0xFF0D160F.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bg)
        var x = 0f; while (x < w) { canvas.drawRect(x, 0f, x + dp(3.5f), h, stripe); x += dp(28f) }

        val cx = w / 2f
        val phoneY = h - dp(34f)
        val ballY = h * 0.30f

        // field of view wedge from the phone widening upward
        fov.reset()
        fov.moveTo(cx, phoneY - dp(20f))
        fov.lineTo(cx - w * 0.34f, dp(12f))
        fov.lineTo(cx + w * 0.34f, dp(12f))
        fov.close()
        canvas.drawPath(fov, fovFill)
        canvas.drawPath(fov, fovStroke)

        // target line up the middle
        canvas.drawLine(cx, phoneY - dp(24f), cx, dp(10f), target)
        label.color = 0x99FFFFFF.toInt(); label.textAlign = Paint.Align.LEFT
        canvas.drawText("TARGET LINE", cx + dp(6f), dp(24f), label)

        // grass ring + ball
        canvas.drawOval(RectF(cx - dp(30f), ballY - dp(16f), cx + dp(30f), ballY + dp(16f)), grass)
        canvas.drawCircle(cx, ballY, dp(7f), ballPaint)
        label.color = 0xFFFFFFFF.toInt(); label.textAlign = Paint.Align.CENTER
        canvas.drawText("BALL", cx, ballY - dp(22f), label)
        label.color = 0xFF78FFA0.toInt()
        canvas.drawText("GRASS ALL AROUND IT", cx, ballY + dp(34f), label)

        // phone body with lens dot
        val pw = dp(32f); val ph = dp(52f)
        canvas.drawRoundRect(RectF(cx - pw / 2, phoneY - ph, cx + pw / 2, phoneY), dp(5f), dp(5f), phoneFill)
        canvas.drawRoundRect(RectF(cx - pw / 2, phoneY - ph, cx + pw / 2, phoneY), dp(5f), dp(5f), phoneStroke)
        canvas.drawCircle(cx, phoneY - ph + dp(9f), dp(2.5f), ballPaint)

        // 2 m dimension between ball and phone
        val dimX = cx + w * 0.24f
        canvas.drawLine(dimX, ballY, dimX, phoneY - ph, dim)
        canvas.drawLine(dimX - dp(4f), ballY, dimX + dp(4f), ballY, dim)
        canvas.drawLine(dimX - dp(4f), phoneY - ph, dimX + dp(4f), phoneY - ph, dim)
        label.color = 0x99FFFFFF.toInt(); label.textAlign = Paint.Align.LEFT
        canvas.drawText("2 m", dimX + dp(6f), (ballY + phoneY - ph) / 2, label)
    }
}
