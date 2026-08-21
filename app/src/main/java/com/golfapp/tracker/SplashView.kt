package com.golfapp.tracker

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Launch animation: the whole product in one gesture — the white arc the camera saw is drawn
 * first, then the yellow model overtakes it and carries the ball to where it lands.
 */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onDone: (() -> Unit)? = null

    private val ground = 0xFF0B100C.toInt()
    private val yellow = 0xFFE8FF00.toInt()

    // ms milestones
    private val measuredEnd = 420f
    private val modelledStart = 360f
    private val modelledDur = 1050f
    private val modelledEnd = modelledStart + modelledDur           // 1410
    private val wordDur = 420f
    private val total = modelledEnd + wordDur + 380f                // ~2210

    private var t = 0f

    private val glowBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x2EFFFFFF; strokeWidth = 1f
    }
    private val modelGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x33E8FF00; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val modelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = yellow; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val seenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; letterSpacing = 0.24f
    }
    private val keyLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; letterSpacing = 0.12f
    }

    private val modelled = Path()
    private val measured = Path()
    private val seg = Path()
    private val mModel = PathMeasure()
    private val mSeen = PathMeasure()
    private var started = false
    private var sx = 1f
    private var sy = 1f
    private fun x(v: Float) = v * sx
    private fun y(v: Float) = v * sy

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sx = w / 396f; sy = h / 812f
        modelled.reset(); modelled.moveTo(x(108f), y(470f)); modelled.quadTo(x(198f), y(214f), x(306f), y(424f))
        measured.reset(); measured.moveTo(x(108f), y(470f)); measured.quadTo(x(138f), y(384f), x(168f), y(342f))
        mModel.setPath(modelled, false); mSeen.setPath(measured, false)
        modelGlow.strokeWidth = 16f * sy; modelStroke.strokeWidth = 6f * sy; seenStroke.strokeWidth = 9f * sy
        word.textSize = 46f * sy; sub.textSize = 11f * sy; keyLabel.textSize = 10f * sy
        if (!started) start()
    }

    private fun start() {
        started = true
        ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong()
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(a: Animator) { onDone?.invoke() }
                override fun onAnimationStart(a: Animator) = Unit
                override fun onAnimationCancel(a: Animator) = Unit
                override fun onAnimationRepeat(a: Animator) = Unit
            })
            start()
        }
    }

    private val ease = DecelerateInterpolator(1.4f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(ground)
        glowBg.shader = LinearGradient(0f, h - y(300f), 0f, h,
            0x000F6E56, 0x590F6E56, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - y(300f), w, h, glowBg)

        canvas.drawLine(x(60f), y(470f), x(336f), y(470f), baseline)

        // modelled (yellow), traced
        val mp = ease.getInterpolation(min(1f, max(0f, (t - modelledStart) / modelledDur)))
        if (mp > 0f) {
            val len = mModel.length
            seg.reset(); mModel.getSegment(0f, len * mp, seg, true)
            canvas.drawPath(seg, modelGlow); canvas.drawPath(seg, modelStroke)
        }
        // measured (white) on top, traced faster
        val sp = min(1f, t / measuredEnd)
        if (sp > 0f) {
            val len = mSeen.length
            seg.reset(); mSeen.getSegment(0f, len * sp, seg, true)
            canvas.drawPath(seg, seenStroke)
        }
        // tee dot (static) + landing dot (scales in with the arc end)
        dot.color = Color.WHITE; canvas.drawCircle(x(108f), y(470f), 8f * sy, dot)
        if (mp > 0f) {
            val end = FloatArray(2); mModel.getPosTan(mModel.length * mp, end, null)
            dot.color = yellow
            canvas.drawCircle(end[0], end[1], (14f * sy) * (0.4f + 0.6f * mp), dot)
        }

        // wordmark + subtitle fading up after the trace
        val wp = min(1f, max(0f, (t - modelledEnd) / wordDur))
        if (wp > 0f) {
            word.alpha = (255 * wp).toInt()
            canvas.drawText("ShotArc", x(60f), y(580f) - (1f - wp) * 10f * sy, word)
            sub.color = 0x8CFFFFFF.toInt(); sub.alpha = (140 * wp).toInt()
            canvas.drawText("SEEN · THEN MODELLED", x(60f), y(612f) - (1f - wp) * 10f * sy, sub)
        }

        // key, bottom-left
        val ky = h - y(60f)
        dot.color = Color.WHITE; canvas.drawRect(x(60f), ky, x(60f) + 16f * sy, ky + 3f * sy, dot)
        keyLabel.color = 0x99FFFFFF.toInt()
        canvas.drawText("CAMERA", x(60f) + 22f * sy, ky + 4f * sy, keyLabel)
        dot.color = yellow; canvas.drawRect(x(150f), ky, x(150f) + 16f * sy, ky + 3f * sy, dot)
        canvas.drawText("MODEL", x(150f) + 22f * sy, ky + 4f * sy, keyLabel)
    }
}
