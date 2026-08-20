package com.golfapp.tracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
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
                environmentCards()
            }
            1 -> ask(R.string.which_ball) {
                ballCarousel()
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

    private val dp get() = resources.displayMetrics.density

    /** Two illustrated cards: a course for outdoors, a driving-range bay for indoors. */
    private fun environmentCards() {
        data class Choice(val env: Environment, val image: Int, val title: String, val sub: String)
        val choices = listOf(
            Choice(Environment.OUTDOORS, R.drawable.il_course, "Outdoors", "On the course or the range"),
            Choice(Environment.INDOORS, R.drawable.il_range, "Indoors", "A bay or a net at home"),
        )
        for (c in choices) {
            val card = MaterialCardView(this).apply {
                radius = 18 * dp
                cardElevation = 4 * dp
                strokeWidth = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (168 * dp).toInt()
                ).also { it.bottomMargin = (14 * dp).toInt() }
                isClickable = true
                setOnClickListener {
                    environment = c.env
                    showStep(1)
                }
            }
            val frame = FrameLayout(this)
            frame.addView(ImageView(this).apply {
                setImageResource(c.image)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            frame.addView(View(this).apply {
                setBackgroundResource(R.drawable.scrim_bottom)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            val text = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((18 * dp).toInt(), 0, (18 * dp).toInt(), (16 * dp).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
                )
            }
            text.addView(TextView(this).apply {
                this.text = c.title; setTextColor(0xFFFFFFFF.toInt()); textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            text.addView(TextView(this).apply {
                this.text = c.sub; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f
            })
            frame.addView(text)
            card.addView(frame)
            binding.options.addView(card)
        }
    }

    /** A golf ball you can swipe through the colours, spinning as it goes. */
    private fun ballCarousel() {
        val pager = ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (300 * dp).toInt()
            )
            offscreenPageLimit = 1
        }
        val name = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
        }
        val hint = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            text = getString(R.string.swipe_colours)
            setPadding(0, 0, 0, (16 * dp).toInt())
        }

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val ball = BallView(this@SetupActivity).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                return object : RecyclerView.ViewHolder(ball) {}
            }
            override fun getItemCount() = BallColour.entries.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as BallView).ballColor = BallColour.entries[position].displayColor
            }
        }

        fun reflect(position: Int) {
            val c = BallColour.entries[position]
            name.text = if (c == BallColour.WHITE) "${c.label} · default" else c.label
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = reflect(position)
        })
        pager.setCurrentItem(ball.ordinal, false)
        reflect(ball.ordinal)

        val choose = MaterialButton(this).apply {
            text = getString(R.string.choose_ball)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (10 * dp).toInt() }
            setOnClickListener {
                ball = BallColour.entries[pager.currentItem]
                showStep(2)
            }
        }

        binding.options.addView(pager)
        binding.options.addView(name)
        binding.options.addView(hint)
        binding.options.addView(choose)
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
