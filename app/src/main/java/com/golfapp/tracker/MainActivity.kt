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
import android.widget.SeekBar
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
        if (granted) startCamera() else binding.statusText.text = getString(R.string.camera_permission_rationale)
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
        if (telemetry.enabled) {
            binding.syncButton.visibility = View.VISIBLE
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
        binding.holeText.text = getString(
            R.string.hole_line,
            hole.number,
            par,
            hole.lengthM,
            round.metresToGreen.toInt(),
            round.shotsOnThisHole,
            round.describeAgainstPar(),
        )
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
    private fun applyInsets() {
        val gap = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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
        if (frame.state != shownState || binding.statusText.text.isEmpty()) {
            shownState = frame.state
            binding.statusText.text = "${frame.message}\n${session.describe()}"
        }
        val metrics = frame.metrics
        if (metrics !== shownMetrics) {
            shownMetrics = metrics
            if (metrics == null) {
                binding.resultPanel.visibility = View.GONE
            } else {
                lastShot = round?.record(metrics)
                lastShot?.let { shotLog.add(it) }
                telemetry.push(session, course, round, shotLog)
                showHole()
                binding.resultPanel.visibility = View.VISIBLE
                binding.scoreText.text = getString(R.string.score_format, metrics.score, metrics.grade)
                binding.resultText.text = describe(metrics)
            }
        }
    }

    private fun describe(m: ShotMetrics): String {
        val side = when {
            abs(m.offlineDeg) < 1.0 -> "straight"
            m.offlineDeg > 0 -> String.format("%.1f° right", m.offlineDeg)
            else -> String.format("%.1f° left", -m.offlineDeg)
        }
        val shape = when {
            abs(m.curveDeg) < 1.0 -> "no curve"
            m.curveDeg > 0 -> String.format("fade %.1f°", m.curveDeg)
            else -> String.format("draw %.1f°", -m.curveDeg)
        }
        val speed = if (m.speedIsLowerBound) {
            String.format("Ball speed ≈ %.0f mph (rough)", m.ballSpeedMph)
        } else {
            String.format("Ball speed %.0f mph (%.0f m/s)", m.ballSpeedMph, m.ballSpeedMs)
        }
        val apex = if (m.stillRising) {
            String.format("Rose %.1f m before leaving frame", m.apexM)
        } else {
            String.format("Apex %.1f m in view", m.apexM)
        }
        val launchGap = m.launchAngleDeg - m.setup.idealLaunchDeg
        val versus = when {
            abs(launchGap) < 1.5 -> "right in the window"
            launchGap > 0 -> String.format("%.1f° high", launchGap)
            else -> String.format("%.1f° low", -launchGap)
        }
        val onCourse = lastShot?.let {
            val side = when {
                kotlin.math.abs(it.lateralM) < 3 -> "on line"
                it.lateralM > 0 -> "${it.lateralM.toInt()} m right"
                else -> "${(-it.lateralM).toInt()} m left"
            }
            "Carried ${it.carryM.toInt()} m, $side — ${it.toGreenM.toInt()} m to the green"
        }
        return listOfNotNull(
            onCourse,
            speed,
            String.format("Launch %.1f°, start %s", m.launchAngleDeg, side),
            "Shape: $shape",
            apex,
            "${m.setup.describe()} — launch $versus",
            String.format("%d samples over %.2f s", m.samples, m.flightSeconds),
        ).joinToString("\n")
    }
}
