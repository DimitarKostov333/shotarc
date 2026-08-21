package com.golfapp.tracker

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
        binding.progressRail.visibility = View.VISIBLE
        binding.stepLabel.visibility = View.VISIBLE
        binding.question.visibility = View.VISIBLE
        updateRail(index)
        binding.stepLabel.text = getString(R.string.step_of, (index + 1).coerceAtMost(steps), steps)
        when (index) {
            0 -> ask(R.string.where_playing) {
                environmentCards()
            }
            1 -> ask(R.string.which_ball) {
                ballList()
            }
            2 -> ask(R.string.what_light) {
                timeCards()
            }
            3 -> ask(R.string.which_course) {
                courseStep()
            }
            else -> showCard(course ?: return start())
        }
    }

    /** The scorecard, so the holes and their pars are visible before a ball is struck. */
    private fun showCard(course: Course) {
        binding.progressRail.visibility = View.GONE
        binding.stepLabel.visibility = View.GONE
        binding.question.visibility = View.GONE
        binding.options.removeAllViews()
        val o = binding.options
        val yellow = 0xFFE8FF00.toInt(); val white = 0xFFFFFFFF.toInt()

        fun mono(t: String, s: Float, c: Int, sp: Float = 0f, medium: Boolean = false) = TextView(this).apply {
            text = t; textSize = s; setTextColor(c)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, if (medium) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (sp != 0f) letterSpacing = sp
        }
        fun serif(t: String, s: Float, c: Int) = TextView(this).apply {
            text = t; textSize = s; setTextColor(c)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
        }

        // header on the brand-green gradient
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0F6E56.toInt(), 0xFF0C5745.toInt())
            ).apply { cornerRadius = 18 * dp }
            setPadding((22 * dp).toInt(), (22 * dp).toInt(), (22 * dp).toInt(), (18 * dp).toInt())
        }
        header.addView(mono("READY TO PLAY", 10.5f, 0xE6FFFFFF.toInt(), 0.18f))
        header.addView(serif(course.name, 30f, white).apply { setPadding(0, (4 * dp).toInt(), 0, (14 * dp).toInt()) })
        val length = course.holes.sumOf { it.lengthM }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(mono(label, 9.5f, 0xA6FFFFFF.toInt(), 0.1f))
            addView(mono(value, 18f, white, 0f, true).apply { setPadding(0, (2 * dp).toInt(), 0, 0) })
        }
        stats.addView(stat("HOLES", "${course.holes.size}"))
        stats.addView(stat("PAR", "${course.par}"))
        stats.addView(stat("LENGTH", "%,d m".format(length)))
        header.addView(stats)
        o.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2)
            .also { it.bottomMargin = (20 * dp).toInt() })

        // length chart
        o.addView(mono("EVERY HOLE, BY LENGTH", 10.5f, 0x80FFFFFF.toInt(), 0.16f))
        o.addView(HoleLengthChart(this).apply { holes = course.holes }
            .also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (96 * dp).toInt()).also { p -> p.topMargin = (10 * dp).toInt() } })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * dp).toInt(), 0, (18 * dp).toInt()) }
        listOf("PAR 3" to 0x4DFFFFFF, "PAR 4" to 0x8CFFFFFF.toInt(), "PAR 5" to yellow).forEach { (t, c) ->
            legend.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 2f; setColor(c) }
                layoutParams = LinearLayout.LayoutParams((14 * dp).toInt(), (8 * dp).toInt()).also { it.marginEnd = (6 * dp).toInt(); it.gravity = android.view.Gravity.CENTER_VERTICAL }
            })
            legend.addView(mono(t, 9.5f, 0x80FFFFFF.toInt(), 0.1f).apply { setPadding(0, 0, (18 * dp).toInt(), 0) })
        }
        o.addView(legend)

        // out / in tables
        fun column(title: String, holes: List<Hole>): LinearLayout {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            val par = holes.sumOf { it.par }; val len = holes.sumOf { it.lengthM }
            col.addView(mono("$title   PAR $par · %,d m".format(len), 9.5f, 0xA6FFFFFF.toInt(), 0.14f).apply { setPadding(0, 0, 0, (6 * dp).toInt()) })
            for (h in holes) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                    background = android.graphics.drawable.GradientDrawable().apply { setStroke((1 * dp).toInt(), 0); setColor(0) }
                }
                val topBorder = View(this).apply { setBackgroundColor(0x12FFFFFF); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()) }
                col.addView(topBorder)
                r.addView(mono("${h.number}", 12.5f, 0x8CFFFFFF.toInt()).also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                val parStr = "${h.par}${if (h.parKnown) "" else "~"}"
                r.addView(mono(parStr, 12.5f, if (h.par == 5) yellow else white).also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                r.addView(mono("${h.lengthM}", 12.5f, 0xB3FFFFFF.toInt()).apply { gravity = android.view.Gravity.END }.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1.4f) })
                col.addView(r)
            }
            return col
        }
        val front = course.holes.take(9); val back = course.holes.drop(9)
        val tables = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tables.addView(column("OUT", front))
        if (back.isNotEmpty()) tables.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), 1) }.also {})
        if (back.isNotEmpty()) tables.addView(column("IN", back))
        o.addView(tables, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2).also { it.topMargin = (6 * dp).toInt() })

        // footer
        o.addView(mono("© OpenStreetMap contributors · ODbL", 10.5f, 0x73FFFFFF.toInt()).apply { setPadding(0, (18 * dp).toInt(), 0, 0) })
        o.addView(MaterialButton(this).apply {
            text = getString(R.string.start_round); textSize = 17f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (12 * dp).toInt() }
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
            setOnClickListener { start() }
        })
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

    /** A full-bleed photo card with a title + subtitle, used by the environment and time steps. */
    private fun addImageCard(image: Int, title: String, sub: String, onClick: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = 18 * dp
            cardElevation = 4 * dp
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (150 * dp).toInt()
            ).also { it.bottomMargin = (14 * dp).toInt() }
            isClickable = true
            setOnClickListener { onClick() }
        }
        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            setImageResource(image)
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
            setPadding((18 * dp).toInt(), 0, (18 * dp).toInt(), (15 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }
        text.addView(TextView(this).apply {
            this.text = title; setTextColor(0xFFFFFFFF.toInt()); textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        text.addView(TextView(this).apply {
            this.text = sub; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f
        })
        frame.addView(text)
        card.addView(frame)
        binding.options.addView(card)
    }

    /** A course for outdoors, a driving-range bay for indoors. */
    private fun environmentCards() {
        addImageCard(R.drawable.photo_course, "Outdoors", "On the course or the range") {
            environment = Environment.OUTDOORS; showStep(1)
        }
        addImageCard(R.drawable.photo_range, "Indoors", "A bay or a net at home") {
            environment = Environment.INDOORS; showStep(1)
        }
    }

    /** Three photo cards for the light you are playing in. */
    private fun timeCards() {
        val advance: (TimeOfDay) -> Unit = { t ->
            time = t
            if (environment == Environment.OUTDOORS) showStep(3) else start()
        }
        addImageCard(R.drawable.photo_morning, "Morning", "Low, warm light") { advance(TimeOfDay.MORNING) }
        addImageCard(R.drawable.photo_noon, "Noon", "Bright, overhead sun") { advance(TimeOfDay.NOON) }
        addImageCard(R.drawable.photo_night, "Night", "Floodlit or after dark") { advance(TimeOfDay.NIGHT) }
    }

    /** A golf ball you can swipe through the colours, spinning as it goes. */
    /** A list of ball colours, grouped by how reliably the detector holds them. */
    private fun ballList() {
        fun groupLabel(t: String) = TextView(this).apply {
            text = t; textSize = 9.5f; setTextColor(0x66FFFFFF)
            typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.16f
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        }
        binding.options.addView(groupLabel("BEST TRACKED · WARM THROUGH COOL"))
        for (c in listOf(BallColour.YELLOW, BallColour.ORANGE, BallColour.PINK, BallColour.RED, BallColour.NEON_GREEN)) {
            binding.options.addView(ballRow(c))
        }
        binding.options.addView(groupLabel("HARDER — NEEDS CARE"))
        for (c in listOf(BallColour.BLUE, BallColour.WHITE, BallColour.BLACK)) {
            binding.options.addView(ballRow(c))
        }
    }

    private fun ballRow(c: BallColour): View {
        val selected = c == ball && c.enabled
        val card = MaterialCardView(this).apply {
            radius = 14 * dp
            cardElevation = 0f
            setCardBackgroundColor(if (selected) 0x0FE8FF00 else 0xFF0B100C.toInt())
            strokeWidth = (if (selected) 2 * dp else 1 * dp).toInt()
            strokeColor = if (selected) 0xFFE8FF00.toInt() else 0x24FFFFFF
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = (6 * dp).toInt() }
            isClickable = c.enabled
            alpha = if (c.enabled) 1f else 0.55f
            if (c.enabled) setOnClickListener { ball = c; showStep(2) }
        }
        val rowV = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
        }
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt())
                .also { it.marginEnd = (14 * dp).toInt() }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(c.displayColor); setStroke((1 * dp).toInt(), 0x22FFFFFF)
            }
        }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@SetupActivity).apply {
                text = c.label; textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
            })
            c.note?.let {
                addView(TextView(this@SetupActivity).apply {
                    text = it; textSize = 11.5f; setTextColor(0x8CFFFFFF.toInt())
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            if (!c.enabled) {
                addView(TextView(this@SetupActivity).apply {
                    text = "SOON"; textSize = 9.5f; setTextColor(0xFF0B100C.toInt())
                    typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.1f
                    setPadding((9 * dp).toInt(), (3 * dp).toInt(), (9 * dp).toInt(), (3 * dp).toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 999f; setColor(0x66FFFFFF)
                    }
                })
            } else {
                addView(TextView(this@SetupActivity).apply {
                    text = "±${c.spread}"; textSize = 9.5f; setTextColor(0x80FFFFFF.toInt())
                    typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.END
                })
                addView(TextView(this@SetupActivity).apply {
                    text = "FLOOR ${c.lumaFloor}"; textSize = 9.5f; setTextColor(0x80FFFFFF.toInt())
                    typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.END
                })
            }
        }
        rowV.addView(swatch); rowV.addView(mid); rowV.addView(right)
        card.addView(rowV)
        return card
    }

    private fun courseButton(container: LinearLayout, label: String, onClick: () -> Unit) {
        val pad = (18 * dp).toInt()
        val button = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = (10 * dp).toInt() }
            setOnClickListener { onClick() }
        }
        container.addView(button as View)
    }

    /** Course selection with a search field that filters the Gauteng list as you type. */
    private fun courseStep() {
        val notice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * dp; setColor(0x12E8FF00); setStroke((1 * dp).toInt(), 0x47E8FF00)
            }
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
            addView(TextView(this@SetupActivity).apply {
                text = "GAUTENG ONLY, FOR NOW"; textSize = 10f; setTextColor(0xFFE8FF00.toInt())
                typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.14f
            })
            addView(TextView(this@SetupActivity).apply {
                text = "Only golf courses in Gauteng, South Africa can be picked for now. Anywhere else, use Practice — no course; shots are still measured and scored."
                textSize = 12.5f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, (5 * dp).toInt(), 0, 0)
            })
        }
        binding.options.addView(notice)
        val search = EditText(this).apply {
            hint = getString(R.string.search_courses)
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setBackgroundResource(R.drawable.search_bg)
            setPadding((16 * dp).toInt(), (13 * dp).toInt(), (16 * dp).toInt(), (13 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = (14 * dp).toInt() }
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        binding.options.addView(search)
        binding.options.addView(list)

        fun rebuild(query: String) {
            list.removeAllViews()
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                courseButton(list, getString(R.string.no_course)) { course = null; start() }
            }
            val matches = courses.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            for (c in matches) {
                courseButton(list, "${c.name}\n${c.holes.size} holes · par ${c.par}") {
                    course = c; showStep(4)
                }
            }
            if (q.isNotEmpty() && matches.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = getString(R.string.no_course_match)
                    setTextColor(0x99FFFFFF.toInt())
                    textSize = 14f
                    setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0)
                })
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = rebuild(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        rebuild("")
    }

    /** The segmented progress rail: filled up to the current question. */
    private fun updateRail(index: Int) {
        val rail = binding.progressRail
        rail.removeAllViews()
        for (i in 0 until steps) {
            rail.addView(View(this).apply {
                setBackgroundColor(if (i <= index) 0xFFE8FF00.toInt() else 0x29FFFFFF)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .also { if (i > 0) it.marginStart = (5 * dp).toInt() }
            })
        }
    }

    private fun start() {
        val seenGuide = getSharedPreferences("guide", MODE_PRIVATE).getBoolean("seen", false)
        val target = if (seenGuide) MainActivity::class.java else SetupGuideActivity::class.java
        startActivity(
            Intent(this, target)
                .putExtra(EXTRA_ENVIRONMENT, environment.name)
                .putExtra(EXTRA_BALL, ball.name)
                .putExtra(EXTRA_TIME, time.name)
                .putExtra(EXTRA_COURSE, course?.name)
        )
    }
}
