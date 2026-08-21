package com.golfapp.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Top-down of the hole: the fairway corridor, the green, and this shot tee→landing. */
class HoleMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var hole: Hole? = null
    private var shot: ShotRecord? = null

    private val rough = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0D160F.toInt() }
    private val corridor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF123F2F.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val corridorEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x14FFFFFF; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C6D4E.toInt() }
    private val shotLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFE8FF00.toInt(); strokeWidth = dp(2.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f); strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1.5f) }
    private val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8FF00.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE; textSize = dp(11f)
    }

    private val path = Path()
    private var proj: List<PointF> = emptyList()
    private var landing: PointF? = null
    private var teeScreen = PointF()

    fun setShot(hole: Hole, shot: ShotRecord?) {
        this.hole = hole; this.shot = shot
        requestLayout(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = project()

    private fun project() {
        val hole = hole ?: return
        if (width == 0) return
        val tee = hole.path.first()
        val greenPt = hole.path.last()
        val a = Math.toRadians(tee.bearingTo(greenPt))
        // to local metres (east, north), rotated so the green points up
        fun local(p: LatLon): PointF {
            val e = (p.lon - tee.lon) * 111320.0 * cos(Math.toRadians(tee.lat))
            val n = (p.lat - tee.lat) * 111320.0
            val ne = e * cos(a) - n * sin(a)
            val nn = e * sin(a) + n * cos(a)
            return PointF(ne.toFloat(), nn.toFloat())
        }
        val pts = hole.path.map { local(it) }.toMutableList()
        val land = shot?.let { local(it.to) }
        val all = pts + listOfNotNull(land)
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
        val pad = dp(28f)
        val sx = (width - 2 * pad) / (maxX - minX).coerceAtLeast(1f)
        val sy = (height - 2 * pad) / (maxY - minY).coerceAtLeast(1f)
        val s = minOf(sx, sy)
        fun toScreen(p: PointF) = PointF(
            pad + (p.x - minX) * s + (width - 2 * pad - (maxX - minX) * s) / 2,
            height - pad - (p.y - minY) * s,   // north up
        )
        proj = pts.map { toScreen(it) }
        landing = land?.let { toScreen(it) }
        teeScreen = proj.first()
        corridor.strokeWidth = dp(34f)
        corridorEdge.strokeWidth = dp(36f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0D160F.toInt())
        if (proj.size < 2) return
        path.reset(); path.moveTo(proj[0].x, proj[0].y)
        for (i in 1 until proj.size) path.lineTo(proj[i].x, proj[i].y)
        canvas.drawPath(path, corridorEdge)
        canvas.drawPath(path, corridor)

        val g = proj.last()
        canvas.drawOval(RectF(g.x - dp(34f), g.y - dp(24f), g.x + dp(34f), g.y + dp(24f)), green)
        canvas.drawLine(g.x, g.y, g.x, g.y - dp(26f), pin)
        canvas.drawPath(Path().apply { moveTo(g.x, g.y - dp(26f)); lineTo(g.x + dp(12f), g.y - dp(21f)); lineTo(g.x, g.y - dp(16f)); close() }, flag)

        landing?.let { land ->
            canvas.drawLine(teeScreen.x, teeScreen.y, land.x, land.y, shotLine)
            dot.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(teeScreen.x, teeScreen.y, dp(5f), dot)
            dot.color = 0xFFE8FF00.toInt(); canvas.drawCircle(land.x, land.y, dp(7f), dot)
            label.color = 0xFFE8FF00.toInt(); label.textAlign = Paint.Align.LEFT
            shot?.let { canvas.drawText("${it.carryM.toInt()} m carry", land.x + dp(10f), land.y, label) }
            label.color = 0x99FFFFFF.toInt()
            canvas.drawText("TEE", teeScreen.x + dp(8f), teeScreen.y + dp(4f), label)
            shot?.let { canvas.drawText("${it.toGreenM.toInt()} m to green", g.x - dp(30f), g.y + dp(40f), label) }
        }
    }
}
