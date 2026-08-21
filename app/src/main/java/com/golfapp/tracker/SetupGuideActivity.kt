package com.golfapp.tracker

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Shown once before the first camera session: how to stand behind the ball. */
class SetupGuideActivity : AppCompatActivity() {

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF0B100C.toInt()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(48f), dp(22f), dp(32f))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(mono("BEFORE THE FIRST SHOT", 10.5f, 0xFFE8FF00.toInt(), 0.18f))
        root.addView(serif("Set the phone behind the ball", 29f, 0xFFFFFFFF.toInt()).apply {
            setPadding(0, dp(8f), 0, dp(20f))
        })

        root.addView(SetupDiagramView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250f))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18f).toFloat() }
            clipToOutline = true
        })

        val rows = listOf(
            Triple("01", "Ball in the lower half of the frame", "The flight needs the top half to happen in."),
            Triple("02", "Turf or a mat under the ball", "Green around the ball is what stops the app locking onto a yellow bag."),
            Triple("03", "A yellow ball beats a white one", "White has no colour to find — it is tracked by brightness alone."),
        )
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x1AFFFFFF)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(24f) }
        }
        rows.forEachIndexed { i, (n, title, body) ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF0B100C.toInt())
                setPadding(dp(16f), dp(15f), dp(16f), dp(15f))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { if (i > 0) it.topMargin = dp(1f) }
                addView(mono(n, 12f, 0xFFE8FF00.toInt()).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(30f), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(LinearLayout(this@SetupGuideActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(rob(title, 15f, 0xFFFFFFFF.toInt(), true))
                    addView(rob(body, 12.5f, 0x8CFFFFFF.toInt()).apply { setPadding(0, dp(3f), 0, 0) })
                })
            }
            list.addView(r)
        }
        root.addView(list)

        root.addView(MaterialButton(this).apply {
            text = "I'm set up"; textSize = 16f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(22f) }
            setPadding(0, dp(16f), 0, dp(16f))
            setOnClickListener { proceed() }
        })
        root.addView(rob("Skip — I've done this before", 12.5f, 0x80FFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, 0)
            setOnClickListener { proceed() }
        })
    }

    private fun proceed() {
        getSharedPreferences("guide", MODE_PRIVATE).edit().putBoolean("seen", true).apply()
        startActivity(Intent(this, MainActivity::class.java).putExtras(intent.extras ?: Bundle()))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun mono(t: String, s: Float, c: Int, sp: Float = 0f) = TextView(this).apply {
        text = t; textSize = s; setTextColor(c); typeface = Typeface.MONOSPACE; if (sp != 0f) letterSpacing = sp
    }
    private fun serif(t: String, s: Float, c: Int) = TextView(this).apply {
        text = t; textSize = s; setTextColor(c); typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private fun rob(t: String, s: Float, c: Int, medium: Boolean = false) = TextView(this).apply {
        text = t; textSize = s; setTextColor(c)
        typeface = Typeface.create(Typeface.DEFAULT, if (medium) Typeface.BOLD else Typeface.NORMAL)
    }
}
