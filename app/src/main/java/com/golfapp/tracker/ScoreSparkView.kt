package com.golfapp.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Shot score over the session as a yellow sparkline, with the best shot marked. */
class ScoreSparkView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var scores: List<Int> = emptyList()
    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFE8FF00.toInt(); strokeWidth = 2 * d
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val mid = Paint().apply {
        style = Paint.Style.STROKE; color = 0x1FFFFFFF; strokeWidth = 1 * d
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8FF00.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); textSize = 10 * d; typeface = android.graphics.Typeface.MONOSPACE
    }

    fun setScores(values: List<Int>) { scores = values.takeLast(24); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val left = 4 * d; val right = width - 4 * d
        val top = 14 * d; val bottom = height - 16 * d
        canvas.drawLine(left, (top + bottom) / 2, right, (top + bottom) / 2, mid)
        if (scores.size < 2) return

        val lo = (scores.min() - 4).coerceAtLeast(0)
        val hi = (scores.max() + 4).coerceAtMost(100)
        val span = (hi - lo).coerceAtLeast(1)
        fun x(i: Int) = left + (right - left) * i / (scores.size - 1)
        fun y(s: Int) = bottom - (bottom - top) * (s - lo) / span

        val path = Path()
        scores.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(i), y(s)) else path.lineTo(x(i), y(s)) }
        canvas.drawPath(path, line)

        val bestI = scores.indices.maxByOrNull { scores[it] }!!
        canvas.drawCircle(x(bestI), y(scores[bestI]), 4 * d, dot)
        val txt = scores[bestI].toString()
        val tx = (x(bestI) - label.measureText(txt) / 2).coerceIn(left, right - label.measureText(txt))
        canvas.drawText(txt, tx, y(scores[bestI]) - 8 * d, label)

        label.color = 0x80FFFFFF.toInt()
        canvas.drawText("SHOT 1", left, height - 2 * d, label)
        val last = scores.size.toString()
        canvas.drawText(last, right - label.measureText(last), height - 2 * d, label)
        label.color = 0xCCFFFFFF.toInt()
    }
}
