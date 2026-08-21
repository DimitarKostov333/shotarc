package com.golfapp.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** One bar per hole, height by length, filled by par (3/4/5). */
class HoleLengthChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    var holes: List<Hole> = emptyList()
        set(value) { field = value; invalidate() }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF; strokeWidth = dp(1f) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x73FFFFFF; typeface = android.graphics.Typeface.MONOSPACE; textSize = dp(9f)
    }

    private fun parColor(par: Int) = when (par) {
        3 -> 0x4DFFFFFF
        5 -> 0xFFE8FF00.toInt()
        else -> 0x8CFFFFFF.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        if (holes.isEmpty()) return
        val w = width.toFloat()
        val baseY = height - dp(16f)
        val topY = dp(4f)
        canvas.drawLine(0f, baseY, w, baseY, base)
        val maxLen = holes.maxOf { it.lengthM }.coerceAtLeast(1)
        val n = holes.size
        val barW = dp(14f)
        val gap = (w - n * barW) / (n - 1).coerceAtLeast(1)
        holes.forEachIndexed { i, hole ->
            val x = i * (barW + gap)
            val h = (hole.lengthM.toFloat() / maxLen) * (baseY - topY)
            bar.color = parColor(hole.par)
            canvas.drawRoundRect(x, baseY - h, x + barW, baseY, dp(2f), dp(2f), bar)
        }
        tick.textAlign = Paint.Align.LEFT; canvas.drawText("1", 0f, height - dp(2f), tick)
        tick.textAlign = Paint.Align.CENTER; canvas.drawText("${(n + 1) / 2}", w / 2, height - dp(2f), tick)
        tick.textAlign = Paint.Align.RIGHT; canvas.drawText("$n", w, height - dp(2f), tick)
    }
}
