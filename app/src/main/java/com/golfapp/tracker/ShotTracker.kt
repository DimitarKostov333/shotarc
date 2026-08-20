package com.golfapp.tracker

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class TrackState { SEARCHING, READY, FLIGHT, RESULT }

data class TrackPoint(val t: Double, val x: Float, val y: Float, val radius: Float)

/** Camera geometry. [fPx] is the focal length expressed in pixels of the analysis buffer. */
data class Intrinsics(val fPx: Double, val estimated: Boolean)

/** What the ball at address tells us: how big it is here, and what this light does to its colour. */
data class BallCalibration(val radius: Float, val colour: ColourSample)

data class ShotMetrics(
    val ballSpeedMs: Double,
    val launchAngleDeg: Double,
    val offlineDeg: Double,
    val curveDeg: Double,
    val apexM: Double,
    val flightSeconds: Double,
    val samples: Int,
    val score: Int,
    val grade: String,
    val speedIsLowerBound: Boolean,
    val stillRising: Boolean,
    val stoppedEarly: Boolean,
    val setup: ShotSetup,
) {
    val ballSpeedMph get() = ballSpeedMs * 2.2369363
}

/** Everything the overlay needs for one frame. */
data class TrackerFrame(
    val state: TrackState,
    val message: String,
    val path: List<TrackPoint>,
    val ball: TrackPoint?,
    val tee: TrackPoint?,
    val candidates: List<Blob>,
    val metrics: ShotMetrics?,
    val rejection: String?,
    val imgW: Int,
    val imgH: Int,
)

/**
 * Follows one yellow ball from the tee into flight and reconstructs the launch in 3D.
 *
 * The camera sits behind the player, so most of the ball's motion is straight away from the
 * lens and barely moves in the frame. Depth therefore comes from the ball shrinking: a golf
 * ball is 42.67 mm across, so z = f * D / (2r).
 */
class ShotTracker(private val intrinsics: Intrinsics) {

    companion object {
        const val BALL_DIAMETER_M = 0.04267
        private const val STATIONARY_FRAMES = 8
        private const val TEE_MISS_TO_LAUNCH = 2
        private const val MIN_GROUND_FRACTION = 0.35f
        private const val MAX_FLIGHT_MISSES = 6
        private const val MAX_FLAT_FRAMES = 12
        private const val MAX_FLIGHT_POINTS = 120
        private const val MAX_FLIGHT_SECONDS = 3.0
        private const val MIN_SHOT_POINTS = 4
        private const val ROLL_LAUNCH_DEG = 4.0
        private const val ROLL_RISE_M = 0.2
        private const val RESULT_HOLD_SECONDS = 4.0
    }

    private data class Sighting(val t: Double, val blob: Blob)

    var state = TrackState.SEARCHING
        private set
    var lastMetrics: ShotMetrics? = null
        private set

    /** Set once the ball has been found sitting on the grass; drives the adaptive colour window. */
    var calibration: BallCalibration? = null
        private set

    /** Club and lie, chosen by the player; both change what counts as a good strike. */
    var setup = ShotSetup(Club.DRIVER, Lie.FAIRWAY)

    /** Why the last swing was not registered, if it was not. */
    var rejection: String? = null
        private set

    private val recent = ArrayDeque<Sighting>()
    private val path = ArrayList<TrackPoint>()
    private var lastPath: List<TrackPoint> = emptyList()
    private var tee: TrackPoint? = null
    private var teeMisses = 0
    private var flightMisses = 0
    private var flightMinRadius = Float.MAX_VALUE
    private var flatFrames = 0
    private var seeded = false
    private var stoppedEarly = false
    private var sawUngroundedBall = false
    private var resultAt = 0.0
    private var imgW = 0
    private var imgH = 0

    fun reset() {
        state = TrackState.SEARCHING
        recent.clear()
        path.clear()
        tee = null
        teeMisses = 0
        flightMisses = 0
        flightMinRadius = Float.MAX_VALUE
        flatFrames = 0
        seeded = false
        stoppedEarly = false
        calibration = null
    }

    fun clearResult() {
        lastMetrics = null
        lastPath = emptyList()
        rejection = null
        reset()
    }

    fun onFrame(t: Double, blobs: List<Blob>, width: Int, height: Int): TrackerFrame {
        imgW = width
        imgH = height
        when (state) {
            TrackState.SEARCHING -> search(t, blobs)
            TrackState.READY -> ready(t, blobs)
            TrackState.FLIGHT -> flight(t, blobs)
            TrackState.RESULT -> if (t - resultAt > RESULT_HOLD_SECONDS) reset()
        }
        return TrackerFrame(
            state = state,
            message = message(),
            path = if (state == TrackState.FLIGHT) ArrayList(path) else lastPath,
            ball = path.lastOrNull().takeIf { state == TrackState.FLIGHT },
            tee = tee,
            candidates = blobs,
            metrics = lastMetrics,
            rejection = rejection,
            imgW = width,
            imgH = height,
        )
    }

    private fun message() = when (state) {
        TrackState.SEARCHING ->
            if (sawUngroundedBall) "Rest the ball on the grass to calibrate"
            else "Point the camera down the target line at the ball"
        TrackState.READY -> "Ball locked — hit it"
        TrackState.FLIGHT -> "Tracking…"
        TrackState.RESULT -> rejection ?: "Shot captured"
    }

    /** The ball at address: round, still, and ringed by grass. */
    private fun teeCandidate(blobs: List<Blob>): Blob? {
        val round = blobs.filter { it.area >= 4 && it.aspect <= 2f && it.fill >= 0.55f }
        sawUngroundedBall = round.isNotEmpty()
        return round.filter { it.groundFraction >= MIN_GROUND_FRACTION }.maxByOrNull { it.area }
    }

    private fun search(t: Double, blobs: List<Blob>) {
        val b = teeCandidate(blobs)
        if (b == null) {
            recent.clear()
            return
        }
        val first = recent.firstOrNull()
        if (first != null &&
            hypot((b.x - first.blob.x).toDouble(), (b.y - first.blob.y).toDouble()) > max(6.0, first.blob.radius * 1.5)
        ) {
            recent.clear()
        }
        recent.addLast(Sighting(t, b))
        while (recent.size > STATIONARY_FRAMES) recent.removeFirst()
        if (recent.size < STATIONARY_FRAMES) return

        // Averaging a still ball over several frames beats any single frame's whole-pixel blob,
        // and this radius is what anchors every distance in the shot.
        val blobs0 = recent.map { it.blob }
        tee = TrackPoint(
            t,
            blobs0.map { it.x }.average().toFloat(),
            blobs0.map { it.y }.average().toFloat(),
            blobs0.map { it.radius }.average().toFloat(),
        )
        calibration = BallCalibration(
            radius = blobs0.map { it.radius }.average().toFloat(),
            colour = ColourSample(
                blobs0.map { it.colour.y }.average().toInt(),
                blobs0.map { it.colour.cb }.average().toInt(),
                blobs0.map { it.colour.cr }.average().toInt(),
            ),
        )
        teeMisses = 0
        state = TrackState.READY
    }

    private fun ready(t: Double, blobs: List<Blob>) {
        val teePoint = tee ?: run { state = TrackState.SEARCHING; return }
        val teeGate = max(12.0, teePoint.radius * 3.0)
        val near = blobs.filter {
            hypot((it.x - teePoint.x).toDouble(), (it.y - teePoint.y).toDouble()) <= teeGate
        }.maxByOrNull { it.area }

        if (near != null) {
            teeMisses = 0
            tee = TrackPoint(
                t,
                teePoint.x * 0.7f + near.x * 0.3f,
                teePoint.y * 0.7f + near.y * 0.3f,
                teePoint.radius * 0.7f + near.radius * 0.3f,
            )
            return
        }

        // Indoors the ball reaches the net in a handful of frames, so waiting to be sure the tee
        // is empty costs real samples: if the ball is already visible elsewhere, go at once.
        val flier = blobs.any {
            it.radius <= teePoint.radius * 1.35f + 1f &&
                hypot((it.x - teePoint.x).toDouble(), (it.y - teePoint.y).toDouble()) > teeGate
        }
        teeMisses++
        if (!flier && teeMisses < TEE_MISS_TO_LAUNCH) return

        path.clear()
        path.add(TrackPoint(teePoint.t, teePoint.x, teePoint.y, teePoint.radius))
        flightMisses = 0
        flightMinRadius = teePoint.radius
        flatFrames = 0
        seeded = false
        stoppedEarly = false
        state = TrackState.FLIGHT
        flight(t, blobs)
    }

    private fun flight(t: Double, blobs: List<Blob>) {
        val last = path.last()
        val prev = if (path.size >= 2) path[path.size - 2] else null
        var predX = last.x
        var predY = last.y
        var speedPx = 0.0
        if (prev != null && last.t > prev.t) {
            val vx = (last.x - prev.x) / (last.t - prev.t)
            val vy = (last.y - prev.y) / (last.t - prev.t)
            speedPx = hypot(vx, vy)
            predX = (last.x + vx * (t - last.t)).toFloat()
            predY = (last.y + vy * (t - last.t)).toFloat()
        }
        // Before the ball is re-acquired it can be anywhere but the tee: a 60 m/s strike clears
        // a third of the frame in the two frames it takes to notice the tee is empty.
        val teeGate = tee?.let { max(12.0, it.radius * 3.0) } ?: 0.0
        val gate = if (seeded) max(imgH * 0.10, speedPx * (t - last.t) * 1.5 + 30.0) else imgH * 0.75

        val pick = blobs
            .filter { it.radius <= last.radius * 1.35f + 1f }
            .filter { seeded || tee == null || hypot((it.x - tee!!.x).toDouble(), (it.y - tee!!.y).toDouble()) > teeGate }
            .map { it to hypot((it.x - predX).toDouble(), (it.y - predY).toDouble()) }
            .filter { it.second <= gate }
            .minByOrNull { it.second }
            ?.first

        if (pick != null) {
            // Growing again means it is no longer flying away: indoors that is the net, and the
            // frames after it would drag the launch fit down with them.
            if (pick.radius > flightMinRadius * 1.5f + 1f) {
                stoppedEarly = true
                finish(t)
                return
            }
            path.add(TrackPoint(t, pick.x, pick.y, pick.radius))
            if (pick.radius < flightMinRadius - 0.001f) {
                flightMinRadius = pick.radius
                flatFrames = 0
            } else {
                flatFrames++
            }
            flightMisses = 0
            seeded = true
        } else {
            flightMisses++
        }

        val head = path.last()
        val offScreen = head.x < 4 || head.y < 4 || head.x > imgW - 4 || head.y > imgH - 4
        // Indoors the ball ends up hanging in a net, still perfectly visible: once it has stopped
        // getting any smaller there is nothing left to measure, so call the shot there.
        if (flatFrames > MAX_FLAT_FRAMES) stoppedEarly = true
        val done = flatFrames > MAX_FLAT_FRAMES ||
            flightMisses > MAX_FLIGHT_MISSES ||
            path.size >= MAX_FLIGHT_POINTS ||
            t - path.first().t > MAX_FLIGHT_SECONDS ||
            offScreen
        if (done) finish(t)
    }

    private fun finish(t: Double) {
        rejection = null
        val metrics = if (path.size >= MIN_SHOT_POINTS) computeMetrics(path) else null
        if (metrics != null) {
            lastMetrics = metrics
            lastPath = ArrayList(path)
            resultAt = t
            state = TrackState.RESULT
        } else if (rejection != null) {
            lastMetrics = null
            lastPath = ArrayList(path)
            resultAt = t
            state = TrackState.RESULT
        } else {
            reset()
        }
    }

    private fun computeMetrics(whole: List<TrackPoint>): ShotMetrics? {
        val f = intrinsics.fPx
        val cx = imgW / 2.0
        val cy = imgH / 2.0

        // Only the part of the path where the ball is still getting smaller says anything about
        // how fast it left: past the smallest it has hit a net, or is falling back towards us.
        var farthest = 0
        for (i in 1 until whole.size) if (whole[i].radius < whole[i - 1].radius) farthest = i
        val pts = if (farthest + 1 >= MIN_SHOT_POINTS) whole.take(farthest + 1) else whole
        val cutShort = pts.size < whole.size
        val t0 = pts.first().t
        val launch = pts.take(min(12, pts.size))
        if (launch.size < MIN_SHOT_POINTS || launch.last().t - t0 < 0.02) return null

        // The ball at address is the best-measured thing in the whole shot: big, still, and
        // averaged over frames. Pin the launch distance to it and fit only how fast it recedes.
        val r0 = (calibration?.radius ?: pts.first().radius).toDouble()
        val s0 = 1.0 / (2.0 * max(0.5, r0))
        var num = 0.0
        var den = 0.0
        for (p in launch) {
            val dt = p.t - t0
            val r = max(0.5, p.radius.toDouble())
            // A radius is measured to a roughly constant fraction of itself, so 1/(2r) is that
            // much less certain the smaller the ball gets: weight each frame by r squared.
            val w = r * r
            num += w * dt * (1.0 / (2.0 * r) - s0)
            den += w * dt * dt
        }
        if (den < 1e-9) return null

        val scale = f * BALL_DIAMETER_M
        val z0 = scale * s0
        val speedIsLowerBound = num / den <= 0.0
        val vz = if (speedIsLowerBound) 0.0 else scale * (num / den)
        if (z0 <= 0.05 || z0 > 200.0) return null

        fun zAt(dt: Double) = max(0.2, z0 + vz * dt)
        val xs = launch.map { Pair(it.t - t0, (it.x - cx) * zAt(it.t - t0) / f) }
        val ys = launch.map { Pair(it.t - t0, -(it.y - cy) * zAt(it.t - t0) / f) }
        val vx = linearFit(xs)?.second ?: return null
        val vy = linearFit(ys)?.second ?: return null

        val horizontal = hypot(vx, vz)
        val speed = sqrt(vx * vx + vy * vy + vz * vz)
        if (speed < 2.0 || speed > 150.0) return null
        val launchAngle = Math.toDegrees(atan2(vy, max(0.01, horizontal)))
        val offline = Math.toDegrees(atan2(vx, max(0.01, vz)))

        // Curve: where the ball is heading late on, against where it started.
        var curve = 0.0
        if (pts.size >= 10 && !speedIsLowerBound) {
            val tail = pts.takeLast(5)
            val tailX = tail.map { Pair(it.t - t0, (it.x - cx) * zAt(it.t - t0) / f) }
            val tailVx = linearFit(tailX)?.second
            if (tailVx != null) curve = Math.toDegrees(atan2(tailVx, max(0.01, vz))) - offline
        }

        val heights = pts.map { -(it.y - cy) * zAt(it.t - t0) / f }
        val apex = (heights.max() - heights.first()).coerceAtLeast(0.0)
        val stillRising = heights.last() >= heights.max() - 1e-6

        // A ball that never left the ground is a duffed shot, not a shot: it comes out flat and
        // stays at the height it started, whichever surface it was struck from.
        if (launchAngle < ROLL_LAUNCH_DEG && apex < ROLL_RISE_M) {
            rejection = "Rolled along the ground — not registered"
            return null
        }
        if (!setup.plausible(speed)) {
            rejection = "Too ${if (speed > setup.expectedSpeedMs) "fast" else "slow"} for a ${setup.club.label.lowercase()}"
            return null
        }

        val score = setup.score(speed, launchAngle, offline)

        return ShotMetrics(
            ballSpeedMs = speed,
            launchAngleDeg = launchAngle,
            offlineDeg = offline,
            curveDeg = curve,
            apexM = apex,
            flightSeconds = pts.last().t - t0,
            samples = pts.size,
            score = score,
            grade = grade(score),
            speedIsLowerBound = speedIsLowerBound || intrinsics.estimated,
            stillRising = stillRising,
            stoppedEarly = stoppedEarly || cutShort,
            setup = setup,
        )
    }

    private fun grade(score: Int) = when {
        score >= 85 -> "Excellent"
        score >= 70 -> "Good"
        score >= 55 -> "Fair"
        score >= 35 -> "Loose"
        else -> "Poor"
    }

    /** Ordinary least squares: returns (intercept, slope). */
    private fun linearFit(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        val n = points.size
        if (n < 2) return null
        val mx = points.sumOf { it.first } / n
        val my = points.sumOf { it.second } / n
        var num = 0.0
        var den = 0.0
        for ((x, y) in points) {
            num += (x - mx) * (y - my)
            den += (x - mx) * (x - mx)
        }
        if (den < 1e-9) return null
        val slope = num / den
        return Pair(my - slope * mx, slope)
    }
}
