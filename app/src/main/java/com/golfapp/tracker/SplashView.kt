package com.golfapp.tracker

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Launch animation: the ShotArc mark drawn as a struck shot — the arc traces out, ball at its tip. */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onDone: (() -> Unit)? = null

    private val green = 0xFF0F6E56.toInt()
    private var progress = 0f
    private var textAlpha = 0f
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f

    private val arcPath = Path()
    private val drawn = Path()
    private val measure = PathMeasure()

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; alpha = 128; strokeCap = Paint.Cap.ROUND
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val pos = FloatArray(2)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = minOf(w, h) * 0.5f / 96f
        ox = (w - 96f * scale) / 2f
        oy = h * 0.32f
        arcPath.reset()
        arcPath.moveTo(tx(16f), ty(84f))
        arcPath.quadTo(tx(46f), ty(8f), tx(84f), ty(44f))
        measure.setPath(arcPath, false)
        arcPaint.strokeWidth = 7f * scale
        groundPaint.strokeWidth = 6f * scale
        textPaint.textSize = 15f * scale
        if (!started) start()
    }

    private var started = false
    private fun start() {
        started = true
        val trace = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1050
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        }
        val fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener { textAlpha = it.animatedValue as Float; invalidate() }
        }
        AnimatorSet().apply {
            play(trace)
            play(fade).after(trace)
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(a: Animator) { postDelayed({ onDone?.invoke() }, 380) }
                override fun onAnimationStart(a: Animator) = Unit
                override fun onAnimationCancel(a: Animator) = Unit
                override fun onAnimationRepeat(a: Animator) = Unit
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(green)
        canvas.drawLine(tx(8f), ty(92f), tx(34f), ty(92f), groundPaint)

        val length = measure.length
        drawn.reset()
        measure.getSegment(0f, length * progress, drawn, true)
        canvas.drawPath(drawn, arcPaint)

        if (progress > 0f) {
            measure.getPosTan(length * progress, pos, null)
            canvas.drawCircle(pos[0], pos[1], 9.5f * scale, ballPaint)
        }
        if (textAlpha > 0f) {
            textPaint.alpha = (255 * textAlpha).toInt()
            canvas.drawText("ShotArc", width / 2f, oy + 118f * scale, textPaint)
        }
    }

    private fun tx(x: Float) = ox + x * scale
    private fun ty(y: Float) = oy + y * scale
}
