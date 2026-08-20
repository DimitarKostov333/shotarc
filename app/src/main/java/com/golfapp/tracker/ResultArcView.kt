package com.golfapp.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * The arc panel of the shot-result screen: the flight drawn side-on, with the slice the camera
 * actually saw picked out in white and the rest — modelled by [Flight] — in yellow.
 */
class ResultArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var metrics: ShotMetrics? = null
    private var record: ShotRecord? = null

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0AFFFFFF; style = Paint.Style.FILL
    }
    private val groundLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF; strokeWidth = dp(1f)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x38E8FF00; strokeWidth = dp(11f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val modelled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFE8FF00.toInt(); strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val seen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rollout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x99E8FF00.toInt(); strokeWidth = dp(2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(5f)), 0f)
    }
    private val apexDrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x4DFFFFFF; strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(5f)), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE; textSize = dp(12f); letterSpacing = 0.04f
    }

    private val arc = Path()
    private val seenPath = Path()

    /** Animated reveal 0..1, driven from the host. */
    var progress = 1f
        set(value) { field = value; invalidate() }

    fun setShot(metrics: ShotMetrics, record: ShotRecord?) {
        this.metrics = metrics
        this.record = record
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF0F6E56.toInt(), 0xFF0B3D31.toInt(), 0xFF0B100C.toInt()),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, bg)

        // mowed stripes
        val period = dp(28f)
        var x = 0f
        while (x < w) { canvas.drawRect(x, 0f, x + dp(3.5f), h, stripe); x += period }

        val m = metrics ?: return
        val groundY = h * 0.84f
        canvas.drawLine(dp(12f), groundY, w - dp(12f), groundY, groundLine)

        // build the flight path in view space from the modelled profile, else a parabola
        val profile = record?.profile?.takeIf { it.size > 2 }
        val leftX = dp(24f)
        val rightX = w - dp(80f)
        val topY = h * 0.10f
        arc.reset()
        if (profile != null) {
            val maxX = profile.maxOf { it[0] }.coerceAtLeast(1.0)
            val maxY = profile.maxOf { it[1] }.coerceAtLeast(1.0)
            fun px(dist: Double) = leftX + (dist / maxX).toFloat() * (rightX - leftX)
            fun py(hgt: Double) = groundY - (hgt / maxY).toFloat() * (groundY - topY)
            arc.moveTo(px(profile[0][0]), py(profile[0][1]))
            for (p in profile) arc.lineTo(px(p[0]), py(p[1]))
        } else {
            arc.moveTo(leftX, groundY)
            arc.quadTo((leftX + rightX) / 2, topY, rightX, groundY)
        }

        val measure = android.graphics.PathMeasure(arc, false)
        val total = measure.length
        val shown = total * progress

        // glow + modelled (full revealed portion)
        val revealed = Path()
        measure.getSegment(0f, shown, revealed, true)
        canvas.drawPath(revealed, glow)
        canvas.drawPath(revealed, modelled)

        // measured (white) segment = fraction the camera actually saw
        val seenFrac = record?.let {
            if (it.hangTimeS > 0) (m.flightSeconds / it.hangTimeS).coerceIn(0.02, 1.0) else 0.08
        } ?: 0.08
        seenPath.reset()
        measure.getSegment(0f, (total * seenFrac.toFloat()).coerceAtMost(shown), seenPath, true)
        canvas.drawPath(seenPath, seen)

        // tee + landing dots
        val start = FloatArray(2); measure.getPosTan(0f, start, null)
        val end = FloatArray(2); measure.getPosTan(total, end, null)
        dot.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(start[0], start[1], dp(4.5f), dot)
        if (progress > 0.98f) {
            dot.color = 0xFFE8FF00.toInt(); canvas.drawCircle(end[0], end[1], dp(6f), dot)
            // roll-out
            canvas.drawLine(end[0], end[1], end[0] + dp(30f), groundY, rollout)
        }

        // labels
        val carry = record?.carryM?.toInt() ?: m.ballSpeedMs.toInt()
        label.color = 0xFFE8FF00.toInt(); label.textAlign = Paint.Align.CENTER
        if (progress > 0.98f) canvas.drawText("${carry} m", end[0], groundY - dp(14f), label)
        label.color = 0xFFFFFFFF.toInt(); label.textAlign = Paint.Align.LEFT
        val apexTxt = if (m.stillRising) "ROSE ${"%.1f".format(m.apexM)} m" else "APEX ${m.apexM.toInt()} m"
        canvas.drawText(apexTxt, w * 0.42f, topY + dp(2f), label)
        label.color = 0x8CFFFFFF.toInt(); label.textSize = dp(10f)
        canvas.drawText("SEEN ${"%.2f".format(m.flightSeconds)} s", dp(16f), groundY + dp(20f), label)
        val modelledTxt = if (m.stoppedEarly) "MODELLED · CUT SHORT" else "MODELLED"
        canvas.drawText(modelledTxt, w * 0.5f, groundY + dp(20f), label)
        label.textSize = dp(12f)
    }
}
