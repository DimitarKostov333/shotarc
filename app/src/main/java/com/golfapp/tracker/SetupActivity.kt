package com.golfapp.tracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.golfapp.tracker.databinding.ActivitySetupBinding
import com.google.android.material.button.MaterialButton

/** Three questions at the start of a session: where, which ball, what light. */
class SetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ENVIRONMENT = "environment"
        const val EXTRA_BALL = "ball"
        const val EXTRA_TIME = "time"
        const val EXTRA_COURSE = "course"
    }

    private lateinit var binding: ActivitySetupBinding
    private var step = 0
    private var environment = Environment.OUTDOORS
    private var ball = BallColour.WHITE
    private var time = TimeOfDay.NOON
    private var course: Course? = null
    private val courses by lazy { CourseLibrary.load(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showStep(0)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step == 0) finish() else showStep(step - 1)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        showStep(step)
    }

    /** Indoors there is no course to play, so that question is only asked outdoors. */
    private val steps: Int get() = if (environment == Environment.OUTDOORS) 4 else 3

    private fun showStep(index: Int) {
        step = index
        binding.stepLabel.text = getString(R.string.step_of, (index + 1).coerceAtMost(steps), steps)
        when (index) {
            0 -> ask(R.string.where_playing) {
                options(Environment.entries.map { it.label }) { picked ->
                    environment = Environment.entries[picked]
                    showStep(1)
                }
            }
            1 -> ask(R.string.which_ball) {
                options(BallColour.entries.map { if (it == BallColour.WHITE) "${it.label} (default)" else it.label }) { picked ->
                    ball = BallColour.entries[picked]
                    showStep(2)
                }
            }
            2 -> ask(R.string.what_light) {
                options(TimeOfDay.entries.map { it.label }) { picked ->
                    time = TimeOfDay.entries[picked]
                    if (environment == Environment.OUTDOORS) showStep(3) else start()
                }
            }
            3 -> ask(R.string.which_course) {
                val labels = listOf(getString(R.string.no_course)) +
                    courses.map { "${it.name}\n${it.holes.size} holes · par ${it.par}" }
                options(labels) { picked ->
                    if (picked == 0) {
                        course = null
                        start()
                    } else {
                        course = courses[picked - 1]
                        showStep(4)
                    }
                }
            }
            else -> showCard(course ?: return start())
        }
    }

    /** The scorecard, so the holes and their pars are visible before a ball is struck. */
    private fun showCard(course: Course) {
        binding.stepLabel.text = getString(R.string.par_total, course.par)
        binding.question.text = course.name
        binding.options.removeAllViews()
        val estimated = course.holes.count { !it.parKnown }
        addRow(getString(R.string.card_header), bold = true)
        for (hole in course.holes) {
            val marked = if (hole.parKnown) "" else " *"
            addRow("%-6s %-8s %s".format(hole.number, "par ${hole.par}$marked", "${hole.lengthM} m"))
        }
        if (estimated > 0) addRow(getString(R.string.par_estimated, estimated))
        addRow(CourseLibrary.ATTRIBUTION)
        val start = MaterialButton(this).apply {
            text = getString(R.string.start_round)
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (18 * resources.displayMetrics.density).toInt() }
            setOnClickListener { start() }
        }
        binding.options.addView(start as View)
    }

    private fun addRow(text: String, bold: Boolean = false) {
        binding.options.addView(TextView(this).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            setTextColor(if (bold) 0xFFE8FF00.toInt() else 0xCCFFFFFF.toInt())
            setPadding(0, (3 * resources.displayMetrics.density).toInt(), 0, 0)
        } as View)
    }

    private fun ask(questionRes: Int, build: () -> Unit) {
        binding.question.setText(questionRes)
        binding.options.removeAllViews()
        build()
    }

    private fun options(labels: List<String>, onPick: (Int) -> Unit) {
        val gap = (10 * resources.displayMetrics.density).toInt()
        val pad = (18 * resources.displayMetrics.density).toInt()
        labels.forEachIndexed { index, label ->
            val button = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.bottomMargin = gap }
                setOnClickListener { onPick(index) }
            }
            binding.options.addView(button as View)
        }
    }

    private fun start() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_ENVIRONMENT, environment.name)
                .putExtra(EXTRA_BALL, ball.name)
                .putExtra(EXTRA_TIME, time.name)
                .putExtra(EXTRA_COURSE, course?.name)
        )
    }
}
