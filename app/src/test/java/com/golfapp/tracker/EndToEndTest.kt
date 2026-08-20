package com.golfapp.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders shots as YUV frames and runs them through the real detector and tracker together —
 * outdoors on grass, and indoors off a mat into a net a few metres away.
 */
class EndToEndTest {

    private val w = 1280
    private val h = 720
    private val f = 1000.0
    private val cx = w / 2.0
    private val cy = h / 2.0
    private val d = ShotTracker.BALL_DIAMETER_M

    private fun radiusAt(z: Double) = f * d / (2 * z)
    private fun u(x: Double, z: Double) = cx + f * x / z
    private fun v(y: Double, z: Double) = cy - f * y / z

    @Test
    fun tracksARenderedDriveOnGrass() {
        val speed = 55.0
        val launchDeg = 14.0
        val offlineDeg = 3.0
        val z0 = 3.0
        val ballY0 = -0.5
        val dt = 1.0 / 60.0
        val vy = speed * sin(Math.toRadians(launchDeg))
        val horiz = speed * cos(Math.toRadians(launchDeg))
        val vx = horiz * sin(Math.toRadians(offlineDeg))
        val vz = horiz * cos(Math.toRadians(offlineDeg))

        val tracker = ShotTracker(Intrinsics(f, estimated = false))
        val frame = TestFrame(w, h)
        var t = 0.0
        var state = TrackState.SEARCHING

        repeat(12) {
            frame.fill(GRASS)
            frame.disc(120.0, 90.0, 4.0, OPTIC_YELLOW)                 // a yellow marker off to the side
            frame.disc(cx, v(ballY0, z0), radiusAt(z0), OPTIC_YELLOW)
            state = tracker.onFrame(t, frame.detect(), frame.uprightWidth, frame.uprightHeight).state
            t += dt
        }
        assertEquals(TrackState.READY, state)
        assertEquals("address radius anchors every distance", radiusAt(z0).toFloat(), tracker.calibration!!.radius, 0.3f)

        val tLaunch = t - dt
        repeat(60) {
            val e = t - tLaunch
            val z = z0 + vz * e
            val by = ballY0 + vy * e - 4.905 * e * e
            frame.fill(GRASS)
            frame.disc(120.0, 90.0, 4.0, OPTIC_YELLOW)
            val r = radiusAt(z)
            if (r >= 1.0) frame.disc(u(vx * e, z), v(by, z), r, OPTIC_YELLOW)
            tracker.onFrame(t, frame.detect(), frame.uprightWidth, frame.uprightHeight)
            t += dt
        }

        val m = tracker.lastMetrics
        assertNotNull("pipeline produced no shot", m)
        m!!
        println("rendered drive -> $m")
        assertEquals(speed, m.ballSpeedMs, speed * 0.2)
        assertEquals(launchDeg, m.launchAngleDeg, 4.0)
        assertEquals(offlineDeg, m.offlineDeg, 2.5)
    }

    /**
     * A garage bay: grey floor, a green mat to hit off, a yellow bag on the floor that is bigger
     * than the ball, and a net 3 m away. The bag is the trap — without the grass check it is the
     * largest yellow thing in the room and the tracker would lock onto it.
     */
    @Test
    fun tracksAShotOffAMatIntoANetIndoors() {
        val speed = 42.0
        val launchDeg = 15.0
        val z0 = 2.5
        val ballY0 = -0.45
        val netAt = z0 + 3.0
        val dt = 1.0 / 120.0
        val vy = speed * sin(Math.toRadians(launchDeg))
        val vz = speed * cos(Math.toRadians(launchDeg))

        val tracker = ShotTracker(Intrinsics(f, estimated = false))
        val frame = TestFrame(w, h)
        val ballU = u(0.0, z0)
        val ballV = v(ballY0, z0)

        fun room() {
            frame.fill(INDOOR_FLOOR)
            frame.rect((ballU - 260).toInt(), (ballV - 90).toInt(), (ballU + 260).toInt(), h - 1, MAT_GREEN)
            frame.disc(150.0, 250.0, 26.0, OPTIC_YELLOW)               // the yellow bag, off the mat
        }

        // Indoor light drags the ball out of the fixed window, so the slider goes up first —
        // then the tracker calibrates and the app narrows back in on the colour it measured.
        var params = DetectorParams.forSession(YELLOW_DAY, 100)
        var t = 0.0
        var state = TrackState.SEARCHING
        repeat(14) {
            room()
            frame.disc(ballU, ballV, radiusAt(z0), LED_LIT_YELLOW)
            val f2 = tracker.onFrame(t, frame.detect(params), frame.uprightWidth, frame.uprightHeight)
            state = f2.state
            tracker.calibration?.let { params = DetectorParams.around(it.colour, YELLOW_DAY) }
            t += dt
        }
        assertEquals(TrackState.READY, state)
        val calibrated = tracker.calibration!!
        assertEquals("locked onto the bag, not the ball", radiusAt(z0).toFloat(), calibrated.radius, 1.5f)

        val tLaunch = t - dt
        repeat(60) {
            val e = t - tLaunch
            var z = z0 + vz * e
            var by = ballY0 + vy * e - 4.905 * e * e
            if (z >= netAt) {                                          // caught by the net
                val hit = (netAt - z0) / vz
                z = netAt
                by = ballY0 + vy * hit - 4.905 * hit * hit - 3.0 * (e - hit)
            }
            room()
            frame.disc(u(0.0, z), v(by, z), radiusAt(z), LED_LIT_YELLOW)
            tracker.onFrame(t, frame.detect(params), frame.uprightWidth, frame.uprightHeight)
            t += dt
        }

        val m = tracker.lastMetrics
        assertNotNull("indoor pipeline produced no shot", m)
        m!!
        println("indoor rendered shot -> $m")
        assertEquals(speed, m.ballSpeedMs, speed * 0.25)
        assertEquals(launchDeg, m.launchAngleDeg, 5.0)
        assertTrue("net shot should be flagged as cut short", m.stoppedEarly)
    }

    @Test
    fun theYellowBagOnTheFloorIsNeverMistakenForTheBall() {
        val frame = TestFrame(w, h)
        frame.fill(INDOOR_FLOOR)
        frame.rect(400, 380, 900, h - 1, MAT_GREEN)
        frame.disc(150.0, 250.0, 26.0, OPTIC_YELLOW)                   // bag on bare floor
        frame.disc(650.0, 470.0, 9.0, OPTIC_YELLOW)                    // ball on the mat

        val blobs = frame.detect(sensitivity = 100)
        val bag = blobs.first { it.x < 300f }
        val ball = blobs.first { it.x > 300f }
        assertTrue("bag is the bigger blob", bag.area > ball.area)
        assertTrue("bag is not on grass", bag.groundFraction < 0.2f)
        assertTrue("ball is on grass", ball.groundFraction > 0.7f)

        val tracker = ShotTracker(Intrinsics(f, estimated = false))
        var frameOut = tracker.onFrame(0.0, blobs, frame.uprightWidth, frame.uprightHeight)
        for (i in 1..12) frameOut = tracker.onFrame(i / 120.0, blobs, frame.uprightWidth, frame.uprightHeight)
        assertEquals(TrackState.READY, frameOut.state)
        val tee = frameOut.tee!!
        assertTrue("locked onto the bag", hypot(tee.x - 650f, tee.y - 470f) < 12f)
    }
}
