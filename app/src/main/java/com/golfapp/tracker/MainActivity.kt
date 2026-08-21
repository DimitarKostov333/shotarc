package com.golfapp.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.drawable.GradientDrawable
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.golfapp.tracker.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    private val detector = BallDetector()
    private var tracker: ShotTracker? = null
    private var session = SessionSetup()
    private var negotiatedFps = 0
    private var sensitivity = 50
    private var params = DetectorParams.forSession(SessionSetup(), 50)
    private var paramsCalibration: BallCalibration? = null

    private var focalMm: Float? = null
    private var sensorWidthMm: Float? = null
    private var setup = ShotSetup(Club.DRIVER, Lie.FAIRWAY)
    private var round: Round? = null
    private var lastShot: ShotRecord? = null
    private var course: Course? = null
    private val shotLog = mutableListOf<ShotRecord>()
    private lateinit var telemetry: Telemetry
    private var shownMetrics: ShotMetrics? = null
    private var shownState: TrackState? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.statusMessage.text = getString(R.string.camera_permission_rationale)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        analysisExecutor = Executors.newSingleThreadExecutor()
        session = sessionFromIntent()
        params = DetectorParams.forSession(session, sensitivity)
        telemetry = Telemetry(this, BuildConfig.SERVER_BASE_URL)
        telemetry.announceInstall(BuildConfig.VERSION_NAME)
        telemetry.checkForUpdate(BuildConfig.VERSION_CODE) { name ->
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.update_available, name),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        if (telemetry.enabled) {
            binding.syncButton.setOnClickListener { resync() }
        }
        startRound()
        applyInsets()

        binding.sensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = progress
                params = DetectorParams.forSession(session, progress)
                paramsCalibration = null
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        setUpPickers()
        binding.debugSwitch.setOnCheckedChangeListener { _, checked -> binding.overlay.showDebug = checked }
        binding.resetButton.setOnClickListener {
            tracker?.clearResult()
            shownMetrics = null
            shownState = null
            binding.resultPanel.visibility = View.GONE
            binding.overlay.clear()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun sessionFromIntent() = SessionSetup(
        environment = enumFor(SetupActivity.EXTRA_ENVIRONMENT, Environment.entries, Environment.OUTDOORS),
        ball = enumFor(SetupActivity.EXTRA_BALL, BallColour.entries, BallColour.WHITE),
        time = enumFor(SetupActivity.EXTRA_TIME, TimeOfDay.entries, TimeOfDay.NOON),
    )

    private fun <T : Enum<T>> enumFor(extra: String, values: List<T>, fallback: T): T {
        val name = intent.getStringExtra(extra) ?: return fallback
        return values.firstOrNull { it.name == name } ?: fallback
    }

    private fun startRound() {
        val name = intent.getStringExtra(SetupActivity.EXTRA_COURSE) ?: return
        val course = CourseLibrary.load(this).firstOrNull { it.name == name } ?: return
        this.course = course
        round = Round(course)
        binding.holePanel.visibility = View.VISIBLE
        binding.holedButton.setOnClickListener {
            round?.holeOut()
            telemetry.push(session, course, round, shotLog)
            showHole()
        }
        showHole()
    }

    private fun resync() {
        binding.syncButton.isEnabled = false
        telemetry.resend { ok ->
            runOnUiThread {
                binding.syncButton.isEnabled = true
                android.widget.Toast.makeText(
                    this,
                    if (ok) R.string.synced else R.string.sync_failed,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showHole() {
        val round = round ?: return
        val hole = round.hole
        val par = if (hole.parKnown) "par ${hole.par}" else "par ${hole.par}~"
        val parts = mutableListOf("Hole ${hole.number}", par, "${round.metresToGreen.toInt()} m to green")
        if (round.shotsOnThisHole > 0) parts.add("${round.shotsOnThisHole} shots · ${round.describeAgainstPar()}")
        binding.holeText.text = parts.joinToString(" · ")
    }

    private fun setUpPickers() {
        binding.clubSpinner.adapter = labels(Club.entries.map { it.label })
        binding.lieSpinner.adapter = labels(Lie.entries.map { it.label })
        binding.clubSpinner.setSelection(setup.club.ordinal)
        binding.lieSpinner.setSelection(setup.lie.ordinal)
        val onPick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                setup = ShotSetup(
                    Club.entries[binding.clubSpinner.selectedItemPosition],
                    Lie.entries[binding.lieSpinner.selectedItemPosition],
                )
                tracker?.setup = setup
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.clubSpinner.onItemSelectedListener = onPick
        binding.lieSpinner.onItemSelectedListener = onPick
    }

    private fun labels(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    /** The preview runs edge to edge; only the two panels move clear of the system bars. */
    private var topInset = 0
    private var bottomInset = 0
    private fun applyInsets() {
        val gap = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = bars.top
            bottomInset = bars.bottom
            binding.statusText.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top + gap }
            binding.controls.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + gap }
            insets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            readIntrinsics(provider, selector)

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            fastestFrameRate(provider, selector)?.let { range ->
                negotiatedFps = range.upper
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            val analysis = analysisBuilder.build().also {
                it.setAnalyzer(analysisExecutor, ::analyze)
            }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, selector, preview, analysis)
            biasExposure(camera)
        }, ContextCompat.getMainExecutor(this))
    }

    /** A darker frame means a shorter shutter and a sharper ball, where there is light to spare. */
    private fun biasExposure(camera: androidx.camera.core.Camera) {
        val bias = session.exposureBiasEv
        if (bias == 0f) return
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val step = state.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val index = (bias / step).toInt().coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
        camera.cameraControl.setExposureCompensationIndex(index)
    }

    private fun readIntrinsics(provider: ProcessCameraProvider, selector: CameraSelector) {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return
        val camera2 = Camera2CameraInfo.from(info)
        focalMm = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        sensorWidthMm = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
    }

    /** Ball flight is quick — take the highest frame rate the camera offers, up to 120. */
    private fun fastestFrameRate(provider: ProcessCameraProvider, selector: CameraSelector): Range<Int>? {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return null
        val ranges = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper <= 120 }.maxByOrNull { it.upper * 10 - (it.upper - it.lower) }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val blobs = detector.detect(image, params)
            val t = image.imageInfo.timestamp / 1_000_000_000.0
            val shot = tracker ?: ShotTracker(intrinsicsFor(image.width)).also {
                it.setup = setup
                tracker = it
            }
            val frame = shot.onFrame(t, blobs, detector.uprightWidth, detector.uprightHeight)
            adaptToBall(shot.calibration)
            runOnUiThread { render(frame) }
        } finally {
            image.close()
        }
    }

    /** Track the ball by the colour this room actually makes it, once we have measured it. */
    private fun adaptToBall(calibration: BallCalibration?) {
        if (calibration === paramsCalibration) return
        paramsCalibration = calibration
        params = if (calibration == null) {
            DetectorParams.forSession(session, sensitivity)
        } else {
            DetectorParams.around(calibration.colour, session)
        }
    }

    private fun intrinsicsFor(bufferWidth: Int): Intrinsics {
        val f = focalMm
        val sw = sensorWidthMm
        return if (f != null && sw != null && sw > 0f) {
            Intrinsics(f.toDouble() / sw.toDouble() * bufferWidth, estimated = false)
        } else {
            // No lens data: assume a 65 degree horizontal field of view.
            Intrinsics(bufferWidth / (2.0 * tan(Math.toRadians(32.5))), estimated = true)
        }
    }

    private fun render(frame: TrackerFrame) {
        binding.overlay.submit(frame)
        if (frame.state != shownState) {
            shownState = frame.state
            binding.statusMessage.text = frame.message
        }
        updateConditions(frame)
        updateCue(frame)
        val metrics = frame.metrics
        if (metrics !== shownMetrics) {
            shownMetrics = metrics
            if (metrics == null) {
                binding.resultPanel.visibility = View.GONE
            } else {
                lastShot = round?.record(metrics)
                lastShot?.let { shotLog.add(it) }
                telemetry.push(session, course, round, shotLog)
                if (telemetry.enabled) binding.syncButton.visibility = View.VISIBLE
                showHole()
                buildResult(metrics, lastShot)
                showResultChrome(false)
                binding.resultPanel.visibility = View.VISIBLE
            }
        }
    }

    private val gd get() = resources.displayMetrics.density
    private fun gdp(v: Float) = (v * gd).toInt()

    private val cGround = 0xFF0B100C.toInt()
    private val cYellow = 0xFFE8FF00.toInt()
    private val cWhite = 0xFFFFFFFF.toInt()
    private val cOn70 = 0xB3FFFFFF.toInt()
    private val cOn55 = 0x8CFFFFFF.toInt()
    private val cHair = 0x1AFFFFFF.toInt()

    private fun tv(text: String, sizeSp: Float, color: Int, face: Typeface, spacing: Float = 0f): TextView =
        TextView(this).apply {
            this.text = text; textSize = sizeSp; setTextColor(color); typeface = face
            if (spacing != 0f) letterSpacing = spacing
        }

    private fun mono(t: String, s: Float, c: Int, sp: Float = 0f, medium: Boolean = false) =
        tv(t, s, c, Typeface.create(Typeface.MONOSPACE, if (medium) Typeface.BOLD else Typeface.NORMAL), sp)

    private fun rob(t: String, s: Float, c: Int, medium: Boolean = false) =
        tv(t, s, c, Typeface.create(Typeface.DEFAULT, if (medium) Typeface.BOLD else Typeface.NORMAL))

    private fun serif(t: String, s: Float, c: Int, semibold: Boolean = false) =
        tv(t, s, c, Typeface.create(Typeface.SERIF, if (semibold) Typeface.BOLD else Typeface.NORMAL))

    private fun row(vararg views: View, gravity: Int = android.view.Gravity.CENTER_VERTICAL) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
            views.forEach { addView(it) }
        }

    /** Page 1a — the arc hero: header, arc, score, weighted bars, metric grid. */
    private fun buildArcPage(m: ShotMetrics, rec: ShotRecord?): View {
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, gdp(16f))
        }
        scroll.addView(c)

        // header
        val holeLabel = round?.let { "HOLE ${it.hole.number} · PAR ${it.hole.par}" } ?: "PRACTICE"
        val pill = mono(m.setup.describe().uppercase(), 11f, cWhite, 0.06f).apply {
            setPadding(gdp(11f), gdp(5f), gdp(11f), gdp(5f))
            background = GradientDrawable().apply {
                cornerRadius = gdp(999f).toFloat(); setStroke(gdp(1f), 0x38FFFFFF)
            }
        }
        val header = row(
            mono(holeLabel, 11f, cOn55, 0.14f).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            },
            pill,
        ).apply { setPadding(gdp(16f), gdp(14f) + topInset, gdp(16f), gdp(10f)) }
        c.addView(header)

        // arc panel
        val arc = ResultArcView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gdp(300f))
                .also { it.setMargins(gdp(12f), 0, gdp(12f), 0) }
            progress = 0f
            setShot(m, rec)
        }
        c.addView(arc)

        c.addView(rob("White traced = what the camera saw. Yellow = the flight model taking it from there.",
            11f, cOn55).apply { setPadding(gdp(16f), gdp(10f), gdp(16f), gdp(4f)) })

        // score block
        if (m.speedIsLowerBound || m.stoppedEarly || m.score >= 0) {
            val score = serif(m.score.toString(), 76f, cYellow, semibold = true)
            val grade = serif(m.grade, 22f, cWhite)
            val outOf = mono("OUT OF 100", 11f, cOn55, 0.12f)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(gdp(10f), 0, 0, 0); addView(grade); addView(outOf)
            }
            c.addView(row(score, col, gravity = android.view.Gravity.BOTTOM)
                .apply { setPadding(gdp(16f), gdp(16f), gdp(16f), 0) })
        }

        // weighted bars
        val bd = m.setup.scoreBreakdown(m.ballSpeedMs, m.launchAngleDeg, m.offlineDeg)
        val barLabels = listOf("DIRECTION 40%", "LAUNCH 30%", "SPEED 30%")
        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(gdp(16f), gdp(18f), gdp(16f), gdp(6f))
        }
        for (i in 0..2) {
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { cornerRadius = gdp(3f).toFloat(); setColor(0x1FFFFFFF) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gdp(6f))
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply { cornerRadius = gdp(3f).toFloat(); setColor(cYellow) }
                    layoutParams = LinearLayout.LayoutParams(0, gdp(6f), bd[i].coerceIn(0.02f, 1f))
                })
                addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, gdp(6f), (1f - bd[i]).coerceIn(0f, 0.98f))
                })
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { if (i > 0) it.marginStart = gdp(10f) }
                addView(track)
                addView(mono(barLabels[i], 9.5f, cOn55, 0.10f).apply { setPadding(0, gdp(6f), 0, 0) })
            }
            bars.addView(cell)
        }
        c.addView(bars)

        // metric grid 2x2
        fun cell(labelTxt: String, value: String, unit: String): LinearLayout {
            val v = row(mono(value, 21f, cWhite, 0f, medium = true), mono(" $unit", 12f, cOn55))
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cGround)
                setPadding(gdp(13f), gdp(14f), gdp(13f), gdp(14f))
                addView(mono(labelTxt, 9.5f, cOn55, 0.14f))
                addView(v.apply { setPadding(0, gdp(4f), 0, 0) })
            }
        }
        val side = if (kotlin.math.abs(m.offlineDeg) < 1) "on line"
        else if (m.offlineDeg > 0) "%.1f right".format(m.offlineDeg) else "%.1f left".format(-m.offlineDeg)
        val shape = if (kotlin.math.abs(m.curveDeg) < 1) "straight"
        else if (m.curveDeg > 0) "fade %.1f".format(m.curveDeg) else "draw %.1f".format(-m.curveDeg)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cHair)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2)
                .also { it.setMargins(gdp(16f), gdp(24f), gdp(16f), 0) }
        }
        fun grRow(a: LinearLayout, b: LinearLayout, top: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2)
                .also { if (top) it.topMargin = gdp(1f) }
            addView(a, LinearLayout.LayoutParams(0, -2, 1f))
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = gdp(1f) })
        }
        val kmh = if (m.speedIsLowerBound) "%.0f".format(m.ballSpeedKmh) else "%.0f".format(m.ballSpeedKmh)
        grid.addView(grRow(cell("BALL SPEED", kmh, "km/h"), cell("LAUNCH", "%.1f".format(m.launchAngleDeg), "°"), false))
        grid.addView(grRow(cell("START", side, ""), cell("SHAPE", shape, "°"), true))
        c.addView(grid)

        // footer
        val samples = rob("${m.samples} samples · %.2f s of flight · landing modelled".format(m.flightSeconds),
            11f, 0x73FFFFFF).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val next = mono("Next shot", 15f, cGround, 0f, medium = true).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(gdp(22f), gdp(15f), gdp(22f), gdp(15f))
            background = GradientDrawable().apply { cornerRadius = gdp(999f).toFloat(); setColor(cYellow) }
            setOnClickListener { dismissResult() }
        }
        c.addView(row(samples, next).apply { setPadding(gdp(16f), gdp(16f), gdp(16f), gdp(8f) + bottomInset) })

        // trace + count-up animation
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1050; interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { arc.progress = it.animatedValue as Float }
            start()
        }
        return scroll
    }

    /** Page 1b — top-down: header, hole map, score, detail rows. */
    private fun buildMapPage(m: ShotMetrics, rec: ShotRecord?): View {
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, gdp(16f)) }
        scroll.addView(c)
        val hole = round?.hole
        val holeLabel = round?.let { "HOLE ${it.hole.number} · PAR ${it.hole.par} · ${it.hole.lengthM} m" } ?: ""
        c.addView(row(
            mono(holeLabel, 11f, cOn55, 0.14f).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
            mono("SHOT ${rec?.shotNumber ?: 1}", 11f, cYellow, 0.06f),
        ).apply { setPadding(gdp(16f), gdp(14f) + topInset, gdp(16f), gdp(10f)) })

        if (hole != null) {
            c.addView(HoleMapView(this).apply { setShot(hole, rec) }.also {
                it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gdp(440f))
                    .also { p -> p.setMargins(gdp(12f), 0, gdp(12f), 0) }
            })
        }

        // score row
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(gdp(10f), 0, 0, 0)
            addView(serif(m.grade, 13f, cWhite)) }
        c.addView(row(serif(m.score.toString(), 46f, cYellow, semibold = true), col, gravity = android.view.Gravity.BOTTOM)
            .apply { setPadding(gdp(16f), gdp(16f), gdp(16f), gdp(10f)) })

        // detail rows
        fun detail(labelTxt: String, value: String) {
            c.addView(View(this).apply { setBackgroundColor(cHair); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gdp(1f)) })
            c.addView(row(
                rob(labelTxt, 13f, cOn70).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                mono(value, 14f, cWhite, 0f, medium = true),
            ).apply { setPadding(gdp(16f), gdp(13f), gdp(16f), gdp(13f)) })
        }
        val side = if (kotlin.math.abs(m.offlineDeg) < 1) "on line" else if (m.offlineDeg > 0) "%.1f° right".format(m.offlineDeg) else "%.1f° left".format(-m.offlineDeg)
        detail("Ball speed", "%.0f km/h".format(m.ballSpeedKmh))
        detail("Launch", "%.1f°".format(m.launchAngleDeg))
        detail("Start", side)
        detail("Apex", "%.0f m".format(m.apexM))

        // actions
        val holed = mono("Holed", 15f, cWhite).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, gdp(14f), 0, gdp(14f))
            background = GradientDrawable().apply { cornerRadius = gdp(999f).toFloat(); setStroke(gdp(1f), 0x40FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = gdp(8f) }
            setOnClickListener { round?.holeOut(); telemetry.push(session, course, round, shotLog); dismissResult() }
        }
        val next = mono("Next shot", 15f, cGround, 0f, medium = true).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, gdp(14f), 0, gdp(14f))
            background = GradientDrawable().apply { cornerRadius = gdp(999f).toFloat(); setColor(cYellow) }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { dismissResult() }
        }
        c.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            setPadding(gdp(16f), gdp(14f), gdp(16f), gdp(8f) + bottomInset); addView(holed); addView(next) })
        return scroll
    }

    /** Build the result as a 1a/1b pager (page 2 only when a course is in play). */
    private fun buildResult(m: ShotMetrics, rec: ShotRecord?) {
        val pages = mutableListOf<View>(buildArcPage(m, rec))
        if (round?.course != null) pages.add(buildMapPage(m, rec))
        binding.resultPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val holder = android.widget.FrameLayout(this@MainActivity).apply {
                    layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT)
                }
                return object : RecyclerView.ViewHolder(holder) {}
            }
            override fun getItemCount() = pages.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                (h.itemView as android.widget.FrameLayout).apply {
                    removeAllViews(); (pages[position].parent as? android.view.ViewGroup)?.removeView(pages[position]); addView(pages[position])
                }
            }
        }
        binding.resultPager.setCurrentItem(0, false)
        buildIndicator(pages.size)
    }

    private fun buildIndicator(count: Int) {
        val ind = binding.resultIndicator
        ind.removeAllViews()
        if (count < 2) { ind.visibility = View.GONE; return }
        ind.visibility = View.VISIBLE
        val dots = (0 until count).map { View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(gdp(6f), gdp(6f)).also { it.marginEnd = gdp(6f) }
        } }
        val label = mono("SIDE ON", 9.5f, cOn55, 0.14f).apply { setPadding(gdp(8f), 0, 0, 0) }
        fun reflect(pos: Int) {
            dots.forEachIndexed { i, dv -> (dv.background as GradientDrawable).setColor(if (i == pos) cYellow else 0x33FFFFFF) }
            label.text = if (pos == 0) "SIDE ON" else "FROM ABOVE"
        }
        dots.forEach { ind.addView(it) }
        ind.addView(label)
        binding.resultPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = reflect(position)
        })
        reflect(0)
    }

    private fun dismissResult() {
        tracker?.clearResult()
        shownMetrics = null
        binding.resultPanel.visibility = View.GONE
        showResultChrome(true)
        binding.overlay.clear()
    }

    private val cMet = 0xFF78FFA0.toInt()
    private val cNot = 0x8CFFFFFF.toInt()

    private fun updateConditions(f: TrackerFrame) {
        if (f.state == TrackState.FLIGHT || f.state == TrackState.RESULT) {
            binding.conditionsText.visibility = View.GONE; return
        }
        val sb = SpannableStringBuilder()
        fun add(text: String, ok: Boolean) {
            val start = sb.length
            sb.append(if (ok) "✓ " else "· ").append(text).append("   ")
            sb.setSpan(ForegroundColorSpan(if (ok) cMet else cNot), start, sb.length, 0)
        }
        add("GRASS", f.groundSeen)
        add("STILL ${f.stillFrames}/${f.stillTarget}", f.stillFrames >= f.stillTarget)
        add(session.ball.label.uppercase(), true)
        if (negotiatedFps > 0) {
            val start = sb.length; sb.append("$negotiatedFps fps")
            sb.setSpan(ForegroundColorSpan(cNot), start, sb.length, 0)
        }
        binding.conditionsText.text = sb
        binding.conditionsText.visibility = View.VISIBLE
        binding.lockDot.alpha = if (f.state == TrackState.READY) 1f else 0.4f
    }

    private fun updateCue(f: TrackerFrame) {
        val show = f.state == TrackState.SEARCHING || f.state == TrackState.READY
        val msg = when {
            !show -> null
            f.tee != null && f.tee.y < f.imgH * 0.40f ->
                "Ball is high in frame — tilt the phone down so the flight stays in view"
            f.state == TrackState.SEARCHING && !f.groundSeen &&
                f.candidates.any { it.aspect <= 2f && it.area >= 4 } -> "Rest the ball on the grass"
            binding.overlay.showDebug && f.candidates.size > 12 ->
                "Too many ball-coloured blobs — lower sensitivity"
            else -> null
        }
        if (msg == null) { binding.cueText.visibility = View.GONE; return }
        val sb = SpannableStringBuilder("CUE   ").append(msg)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, 3, 0)
        binding.cueText.text = sb
        binding.cueText.visibility = View.VISIBLE
    }

    /** Hide the live HUD while the full-screen result is up. */
    private fun showResultChrome(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        binding.statusText.visibility = v
        binding.controls.visibility = v
        binding.pickers.visibility = v
        binding.holePanel.visibility = if (show && round != null) View.VISIBLE else View.GONE
        if (!show) binding.cueText.visibility = View.GONE
    }

}
