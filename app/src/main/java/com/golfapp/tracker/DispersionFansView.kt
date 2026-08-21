package com.golfapp.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** Where the session's shots landed, one translucent fan per club group, drawn from a shared tee. */
class DispersionFansView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Group(val label: String, val fill: Int, val stroke: Int, val shots: List<ShotRecord>)

    private var groups: List<Group> = emptyList()
    private var maxCarry = 1.0
    private var maxLateral = 1.0
    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * d; typeface = Typeface.MONOSPACE
    }
    private val tee = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }

    fun setShots(shots: List<ShotRecord>) {
        val bucketed = shots.groupBy { bucket(it.club) }
        groups = ORDER.mapNotNull { key ->
            bucketed[key]?.takeIf { it.isNotEmpty() }?.let { Group(key.label, key.fill, key.stroke, it) }
        }
        maxCarry = (shots.maxOfOrNull { it.carryM } ?: 1.0).coerceAtLeast(1.0)
        maxLateral = (shots.maxOfOrNull { kotlin.math.abs(it.lateralM) } ?: 1.0).coerceAtLeast(12.0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val teeX = width / 2f
        val teeY = height - 22 * d
        val top = 22 * d
        canvas.drawCircle(teeX, teeY, 3.5f * d, tee)

        fun x(lateral: Double) = teeX + (width * 0.44f) * (lateral / maxLateral).toFloat()
        fun y(carry: Double) = teeY - (teeY - top) * (carry / maxCarry).toFloat()

        groups.forEach { g ->
            val pts = g.shots.map { floatArrayOf(x(it.lateralM), y(it.carryM)) }
            val leftMost = g.shots.minByOrNull { it.lateralM }!!
            val rightMost = g.shots.maxByOrNull { it.lateralM }!!
            val path = Path().apply {
                moveTo(teeX, teeY)
                lineTo(x(leftMost.lateralM), y(leftMost.carryM))
                g.shots.sortedBy { it.lateralM }.forEach { lineTo(x(it.lateralM), y(it.carryM)) }
                lineTo(x(rightMost.lateralM), y(rightMost.carryM))
                close()
            }
            fill.color = g.fill; stroke.color = g.stroke; dot.color = g.stroke
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
            pts.forEach { canvas.drawCircle(it[0], it[1], 3 * d, dot) }

            val avg = g.shots.map { it.carryM }.average().roundToInt()
            val far = g.shots.maxByOrNull { it.carryM }!!
            label.color = g.stroke or 0xFF000000.toInt()
            canvas.drawText("${g.label} $avg m", (x(far.lateralM) + 6 * d).coerceAtMost(width - 90 * d), y(far.carryM) + 4 * d, label)
        }
    }

    private enum class Bucket(val label: String, val fill: Int, val stroke: Int) {
        DRIVER("DRIVER", 0x1AE8FF00, 0x66E8FF00),
        MID("MID IRON", 0x12FFFFFF, 0x4DFFFFFF),
        WEDGE("WEDGE", 0x0DFFFFFF, 0x33FFFFFF),
    }

    private companion object {
        val ORDER = listOf(Bucket.DRIVER, Bucket.MID, Bucket.WEDGE)
        fun bucket(c: Club) = when (c) {
            Club.DRIVER, Club.FAIRWAY_WOOD -> Bucket.DRIVER
            Club.WEDGE, Club.SHORT_IRON -> Bucket.WEDGE
            else -> Bucket.MID
        }
    }
}
