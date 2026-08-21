package com.golfapp.tracker

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Ties this phone to a dashboard account. The site issues a six-character code; typing it here is
 * the only thing that tells the server whose rounds these are. Skipping it costs nothing — the app
 * still tracks and still uploads, the rounds just sit on no dashboard until a code is entered.
 */
class PairActivity : AppCompatActivity() {

    private val dp get() = resources.displayMetrics.density
    private val yellow = 0xFFE8FF00.toInt()

    private lateinit var telemetry: Telemetry
    private lateinit var status: TextView
    private lateinit var field: EditText
    private lateinit var connect: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telemetry = Telemetry(this, BuildConfig.SERVER_BASE_URL)
        setContentView(buildView())
    }

    private fun buildView(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((28 * dp).toInt(), (48 * dp).toInt(), (28 * dp).toInt(), (48 * dp).toInt())
        }

        column.addView(mono(getString(R.string.app_name).uppercase(), 11f, yellow, 0.2f, true))
        column.addView(serif(getString(R.string.pair_title), 30f, 0xFFFFFFFF.toInt()).apply {
            setPadding(0, (14 * dp).toInt(), 0, (12 * dp).toInt())
        })

        val paired = Telemetry.pairedAccount(this)
        column.addView(body(if (paired != null) getString(R.string.pair_body_done, paired) else getString(R.string.pair_body)))

        column.addView(mono(getString(R.string.pair_field_label), 10.5f, 0x80FFFFFF.toInt(), 0.18f).apply {
            setPadding(0, (30 * dp).toInt(), 0, (10 * dp).toInt())
        })

        field = EditText(this).apply {
            setText(""); hint = "······"
            textSize = 30f
            gravity = Gravity.CENTER
            letterSpacing = 0.28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(yellow)
            setHintTextColor(0x33FFFFFF)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(CODE_LENGTH))
            background = GradientDrawable().apply {
                cornerRadius = 4 * dp
                setColor(0x0FE8FF00)
                setStroke((1 * dp).toInt(), 0x33FFFFFF)
            }
            setPadding((18 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2)
        }
        column.addView(field)

        connect = MaterialButton(this).apply {
            text = getString(R.string.pair_connect)
            textSize = 17f
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2)
                .also { it.topMargin = (16 * dp).toInt() }
            setOnClickListener { submit() }
        }
        column.addView(connect)

        status = body("").apply {
            setPadding(0, (14 * dp).toInt(), 0, 0)
            visibility = View.GONE
        }
        column.addView(status)

        column.addView(MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(if (paired != null) R.string.pair_disconnect else R.string.pair_skip)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2)
                .also { it.topMargin = (10 * dp).toInt() }
            setOnClickListener {
                if (paired != null) {
                    Telemetry.setPairedAccount(this@PairActivity, null)
                    recreate()
                } else {
                    finish()
                }
            }
        })

        column.addView(mono(getString(R.string.pair_where), 11.5f, 0x73FFFFFF.toInt(), 0f).apply {
            setPadding(0, (26 * dp).toInt(), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(0xFF0B100C.toInt())
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2))
        }
    }

    private fun submit() {
        val code = field.text.toString().trim()
        if (code.length != CODE_LENGTH) {
            say(getString(R.string.pair_need_six), false)
            return
        }
        connect.isEnabled = false
        say(getString(R.string.pair_connecting), null)
        telemetry.pair(code) { account, error ->
            runOnUiThread {
                connect.isEnabled = true
                if (account != null) {
                    say(getString(R.string.pair_done, account), true)
                    field.setText("")
                    field.postDelayed({ if (!isFinishing) finish() }, 1400)
                } else {
                    say(error ?: getString(R.string.pair_failed), false)
                }
            }
        }
    }

    /** ok = true green, false amber, null neutral while it is still in flight. */
    private fun say(message: String, ok: Boolean?) {
        status.text = message
        status.setTextColor(
            when (ok) {
                true -> 0xFF78FFA0.toInt()
                false -> 0xFFF2B24A.toInt()
                null -> 0xB3FFFFFF.toInt()
            }
        )
        status.visibility = View.VISIBLE
    }

    private fun mono(t: String, size: Float, colour: Int, spacing: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(colour)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (spacing != 0f) letterSpacing = spacing
        }

    private fun serif(t: String, size: Float, colour: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(colour)
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xB3FFFFFF.toInt())
        setLineSpacing(4 * dp, 1f)
    }

    private companion object {
        const val CODE_LENGTH = 6
    }
}
