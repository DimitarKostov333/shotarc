package com.golfapp.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

private const val F_PX = 1000.0
private const val W = 720
private const val H = 1280
private const val D = ShotTracker.BALL_DIAMETER_M
private val BALL_COLOUR = ColourSample(216, 6, 133)

private fun ballBlob(x: Double, v: Double, radius: Double, grounded: Boolean) = Blob(
    x = x.toFloat(),
    y = v.toFloat(),
    radius = (Math.round(radius * 2.0) / 2.0).toFloat(),   // detector resolves about half a pixel
    area = (Math.PI * radius * radius).toInt(),
    fill = 0.8f,
    aspect = 1.1f,
    colour = BALL_COLOUR,
    groundFraction = if (grounded) 0.9f else 0.05f,
)

/**
 * Plays a physically simulated shot through the tracker: a ball sitting still on the grass, then
 * flying away from the camera under gravity, projected into the frame the way a real lens would.
 * With [netDistance] set the ball is stopped by a net that far in front of it, as it would be in
 * a garage or a simulator bay, and drops down the netting from there.
 */
private fun simulate(
    speed: Double,
    launchDeg: Double,
    offlineDeg: Double,
    z0: Double = 3.0,
    y0: Double = -0.6,
    teeFrames: Int = 12,
    flightFrames: Int = 90,
    fps: Double = 60.0,
    netDistance: Double? = null,
    rolling: Boolean = false,
    setup: ShotSetup = ShotSetup(Club.DRIVER, Lie.FAIRWAY),
): ShotTracker {
    val tracker = ShotTracker(Intrinsics(F_PX, estimated = false))
    tracker.setup = setup
    val dt = 1.0 / fps
    val cx = W / 2.0
    val cy = H / 2.0
    val vy = speed * sin(Math.toRadians(launchDeg))
    val horizontal = speed * cos(Math.toRadians(launchDeg))
    val vx = horizontal * sin(Math.toRadians(offlineDeg))
    val vz = horizontal * cos(Math.toRadians(offlineDeg))

    fun blobAt(x: Double, y: Double, z: Double, grounded: Boolean): Blob? {
        val r = F_PX * D / (2 * z)
        if (r < 1.2) return null
        val u = cx + F_PX * x / z
        val v = cy - F_PX * y / z
        if (u < 0 || v < 0 || u > W || v > H) return null
        return ballBlob(u, v, r, grounded)
    }

    var t = 0.0
    repeat(teeFrames) {
        tracker.onFrame(t, listOfNotNull(blobAt(0.0, y0, z0, grounded = true)), W, H)
        t += dt
    }
    val tLaunch = t - dt
    val netZ = netDistance?.let { z0 + it }
    repeat(flightFrames) {
        val e = t - tLaunch
        var z = if (rolling) z0 + speed * e else z0 + vz * e
        var y = if (rolling) y0 else y0 + vy * e - 4.905 * e * e
        if (netZ != null && z >= netZ) {
            // caught by the net: it stops going away and slides down the netting
            val hit = (netZ - z0) / vz
            z = netZ
            y = y0 + vy * hit - 4.905 * hit * hit - 3.0 * (e - hit)
        }
        tracker.onFrame(t, listOfNotNull(blobAt(vx * e, y, z, grounded = false)), W, H)
        t += dt
    }
    return tracker
}

class ShotTrackerTest {

    @Test
    fun armsItselfOnAStationaryBallOnTheGrass() {
        val tracker = ShotTracker(Intrinsics(F_PX, estimated = false))
        val ball = ballBlob(360.0, 900.0, 7.0, grounded = true)
        var frame = tracker.onFrame(0.0, listOf(ball), W, H)
        assertEquals(TrackState.SEARCHING, frame.state)
        for (i in 1..10) frame = tracker.onFrame(i / 60.0, listOf(ball), W, H)
        assertEquals(TrackState.READY, frame.state)
        assertNotNull(frame.tee)
        assertEquals(7f, tracker.calibration!!.radius, 0.1f)
        assertEquals(BALL_COLOUR, tracker.calibration!!.colour)
    }

    @Test
    fun willNotArmOnAYellowThingThatIsNotOnTheGrass() {
        val tracker = ShotTracker(Intrinsics(F_PX, estimated = false))
        val bag = ballBlob(360.0, 900.0, 7.0, grounded = false)
        var frame = tracker.onFrame(0.0, listOf(bag), W, H)
        for (i in 1..20) frame = tracker.onFrame(i / 60.0, listOf(bag), W, H)
        assertEquals(TrackState.SEARCHING, frame.state)
        assertNull(tracker.calibration)
        assertTrue(frame.message.contains("grass"))
    }

    @Test
    fun aWavingBallNeverArms() {
        val tracker = ShotTracker(Intrinsics(F_PX, estimated = false))
        var frame = tracker.onFrame(0.0, emptyList(), W, H)
        for (i in 1..30) {
            val jitter = if (i % 2 == 0) 0.0 else 60.0
            frame = tracker.onFrame(i / 60.0, listOf(ballBlob(360.0 + jitter, 900.0, 7.0, grounded = true)), W, H)
        }
        assertEquals(TrackState.SEARCHING, frame.state)
    }

    @Test
    fun recoversLaunchOfAStraightDrive() {
        val m = simulate(speed = 60.0, launchDeg = 13.0, offlineDeg = 0.0).lastMetrics
        assertNotNull("no shot captured", m)
        m!!
        println("straight drive -> $m")
        assertEquals(60.0, m.ballSpeedMs, 60.0 * 0.15)
        assertEquals(13.0, m.launchAngleDeg, 3.0)
        assertEquals(0.0, m.offlineDeg, 1.5)
        assertTrue(m.samples >= 8)
    }

    @Test
    fun recoversASlowerHigherWedge() {
        val m = simulate(speed = 30.0, launchDeg = 30.0, offlineDeg = 0.0).lastMetrics
        assertNotNull(m)
        m!!
        println("wedge -> $m")
        assertEquals(30.0, m.ballSpeedMs, 30.0 * 0.2)
        assertEquals(30.0, m.launchAngleDeg, 4.0)
    }

    @Test
    fun measuresAShotIntoANetThreeMetresAway() {
        val m = simulate(
            speed = 45.0, launchDeg = 12.0, offlineDeg = 2.0,
            z0 = 2.5, y0 = -0.5, fps = 120.0, netDistance = 3.2, flightFrames = 60,
        ).lastMetrics
        assertNotNull("indoor shot produced no result", m)
        m!!
        println("indoor net shot -> $m")
        assertEquals(45.0, m.ballSpeedMs, 45.0 * 0.2)
        assertEquals(12.0, m.launchAngleDeg, 4.0)
        assertEquals(2.0, m.offlineDeg, 2.0)
        assertTrue("net shot should be flagged as cut short", m.stoppedEarly)
    }

    @Test
    fun theNetItselfDoesNotDragTheSpeedDown() {
        val open = simulate(speed = 45.0, launchDeg = 12.0, offlineDeg = 0.0, z0 = 2.5, fps = 120.0)
        val netted = simulate(
            speed = 45.0, launchDeg = 12.0, offlineDeg = 0.0,
            z0 = 2.5, fps = 120.0, netDistance = 3.2, flightFrames = 60,
        )
        val a = open.lastMetrics!!.ballSpeedMs
        val b = netted.lastMetrics!!.ballSpeedMs
        println("open=$a netted=$b")
        assertEquals(a, b, a * 0.15)
    }

    @Test
    fun doesNotRegisterABallThatRollsAlongTheGround() {
        val tracker = simulate(speed = 18.0, launchDeg = 0.0, offlineDeg = 0.0, rolling = true)
        println("rolled -> ${tracker.rejection}")
        assertNull("a topped ball is not a shot", tracker.lastMetrics)
        assertTrue(tracker.rejection!!.contains("Rolled"))
    }

    @Test
    fun aLowStingingShotIsStillAShot() {
        val m = simulate(speed = 50.0, launchDeg = 6.0, offlineDeg = 0.0).lastMetrics
        assertNotNull("6 degrees is low, not rolling", m)
        assertTrue(m!!.launchAngleDeg < 10.0)
    }

    @Test
    fun saysSoWhenTheSpeedCannotHaveComeFromThatClub() {
        val tracker = simulate(
            speed = 70.0, launchDeg = 13.0, offlineDeg = 0.0,
            setup = ShotSetup(Club.WEDGE, Lie.FAIRWAY),
        )
        println("wrong club -> ${tracker.rejection}")
        assertNull(tracker.lastMetrics)
        assertTrue(tracker.rejection!!.contains("wedge"))
    }

    @Test
    fun theClubInHandChangesTheScore() {
        val asWedge = simulate(
            speed = 30.0, launchDeg = 30.0, offlineDeg = 0.0,
            setup = ShotSetup(Club.WEDGE, Lie.FAIRWAY),
        ).lastMetrics!!
        val asDriver = simulate(
            speed = 30.0, launchDeg = 30.0, offlineDeg = 0.0,
            setup = ShotSetup(Club.DRIVER, Lie.FAIRWAY),
        ).lastMetrics!!
        println("30 m/s at 30°: wedge=${asWedge.score} driver=${asDriver.score}")
        assertTrue("a good wedge is a bad drive", asWedge.score > asDriver.score + 20)
    }

    @Test
    fun readsSideOfTheTargetLine() {
        val right = simulate(speed = 55.0, launchDeg = 12.0, offlineDeg = 6.0).lastMetrics!!
        val left = simulate(speed = 55.0, launchDeg = 12.0, offlineDeg = -6.0).lastMetrics!!
        println("right -> ${right.offlineDeg}, left -> ${left.offlineDeg}")
        assertEquals(6.0, right.offlineDeg, 2.0)
        assertEquals(-6.0, left.offlineDeg, 2.0)
    }

    @Test
    fun scoresAStraightStrikeAboveAPush() {
        val straight = simulate(speed = 60.0, launchDeg = 13.0, offlineDeg = 0.0).lastMetrics!!
        val pushed = simulate(speed = 60.0, launchDeg = 13.0, offlineDeg = 9.0).lastMetrics!!
        val weak = simulate(speed = 25.0, launchDeg = 40.0, offlineDeg = 0.0).lastMetrics!!
        println("scores: straight=${straight.score} pushed=${pushed.score} weak=${weak.score}")
        assertTrue(straight.score > pushed.score)
        assertTrue(straight.score > weak.score)
        assertTrue(straight.score >= 70)
    }

    @Test
    fun ignoresABallThatIsOnlyGlimpsed() {
        val tracker = simulate(speed = 60.0, launchDeg = 13.0, offlineDeg = 0.0, flightFrames = 2)
        assertNull(tracker.lastMetrics)
    }

    @Test
    fun resetClearsTheResult() {
        val tracker = simulate(speed = 60.0, launchDeg = 13.0, offlineDeg = 0.0)
        assertNotNull(tracker.lastMetrics)
        tracker.clearResult()
        assertNull(tracker.lastMetrics)
        assertEquals(TrackState.SEARCHING, tracker.state)
        assertNull(tracker.calibration)
    }
}
