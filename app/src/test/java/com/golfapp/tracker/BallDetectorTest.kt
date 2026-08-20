package com.golfapp.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BallDetectorTest {

    @Test
    fun findsAYellowBallOnGrass() {
        val f = TestFrame(800, 600)
        f.fill(GRASS)
        f.disc(400.0, 300.0, 20.0, OPTIC_YELLOW)

        val b = f.detect().single()
        assertEquals(400f, b.x, 2f)
        assertEquals(300f, b.y, 2f)
        assertEquals(20f, b.radius, 2f)
    }

    @Test
    fun reportsUprightCoordinatesForARotatedFrame() {
        val f = TestFrame(800, 600)
        f.fill(GRASS)
        f.disc(400.0, 300.0, 20.0, OPTIC_YELLOW)

        val b = f.detect(rotation = 90).single()
        // 90 degrees clockwise: (x, y) -> (h - y, x)
        assertEquals(300f, b.x, 2f)
        assertEquals(400f, b.y, 2f)
    }

    @Test
    fun ignoresTheThingsThatShareAGolfCourseWithTheBall() {
        val f = TestFrame(600, 400)
        f.fill(GRASS)
        f.disc(80.0, 80.0, 15.0, WHITE_BALL)
        f.disc(200.0, 80.0, 15.0, RED_FLAG)
        f.disc(320.0, 80.0, 15.0, ORANGE_MARKER)
        f.disc(460.0, 80.0, 25.0, SKY)
        assertTrue("false positives: ${f.detect()}", f.detect().isEmpty())
    }

    @Test
    fun stillSeesABallOnlyAFewPixelsWide() {
        val f = TestFrame(800, 600)
        f.fill(GRASS)
        f.disc(500.0, 200.0, 3.0, OPTIC_YELLOW)

        val b = f.detect().single()
        assertEquals(500f, b.x, 3f)
        assertEquals(200f, b.y, 3f)
    }

    @Test
    fun sensitivityReachesABallInShadow() {
        val f = TestFrame(400, 300)
        f.fill(GRASS)
        f.disc(200.0, 150.0, 12.0, SHADED_YELLOW)

        assertTrue(f.detect(sensitivity = 20).isEmpty())
        assertEquals(1, f.detect(sensitivity = 90).size)
    }

    @Test
    fun keepsTheMinorAxisOfAMotionBlurredStreak() {
        val f = TestFrame(800, 600)
        f.fill(GRASS)
        for (i in 0..24) f.disc(300.0 + i, 200.0, 8.0, OPTIC_YELLOW)  // ball smeared sideways

        val b = f.detect().single()
        assertEquals(8f, b.radius, 1.5f)
        assertTrue("streak should read as elongated", b.aspect > 1.5f)
    }

    @Test
    fun seesTheGrassAroundABallAtAddress() {
        val onGrass = TestFrame(400, 300)
        onGrass.fill(GRASS)
        onGrass.disc(200.0, 150.0, 10.0, OPTIC_YELLOW)
        assertTrue(onGrass.detect().single().groundFraction > 0.9f)

        val onCarpet = TestFrame(400, 300)
        onCarpet.fill(INDOOR_FLOOR)
        onCarpet.disc(200.0, 150.0, 10.0, OPTIC_YELLOW)
        assertEquals(0f, onCarpet.detect().single().groundFraction, 0.05f)
    }

    @Test
    fun aBallInFlightIsNotGrounded() {
        val f = TestFrame(400, 300)
        f.fill(GRASS)
        f.disc(200.0, 40.0, 6.0, OPTIC_YELLOW)          // ball on the grass near the top
        f.disc(200.0, 40.0, 30.0, SKY)                  // ...but sky all around it
        f.disc(200.0, 40.0, 6.0, OPTIC_YELLOW)
        assertTrue(f.detect().single().groundFraction < 0.2f)
    }

    @Test
    fun theWindowFittedToTheBallFindsItUnderIndoorLight() {
        val f = TestFrame(400, 300)
        f.fill(GRASS)
        f.disc(200.0, 150.0, 10.0, LED_LIT_YELLOW)

        // Cool indoor light drags the ball green enough to fall outside the fixed window.
        assertTrue(f.detect(sensitivity = 50).isEmpty())

        val measured = f.detect(sensitivity = 100).single().colour
        val fitted = f.detect(DetectorParams.around(measured, YELLOW_DAY)).single()
        assertEquals(200f, fitted.x, 2f)
        assertEquals(10f, fitted.radius, 2f)
    }

    @Test
    fun aWindowFittedToTheBallStillRejectsTheFlag() {
        val f = TestFrame(400, 300)
        f.fill(GRASS)
        f.disc(120.0, 150.0, 12.0, OPTIC_YELLOW)
        f.disc(300.0, 150.0, 12.0, RED_FLAG)

        val measured = f.detect().first { it.x < 200f }.colour
        val found = f.detect(DetectorParams.around(measured, YELLOW_DAY))
        assertEquals(1, found.size)
        assertTrue(found.single().x < 200f)
    }

    @Test
    fun findsEveryBallColourItOffers() {
        val balls = mapOf(
            BallColour.WHITE to WHITE_BALL_LIT,
            BallColour.YELLOW to OPTIC_YELLOW,
            BallColour.ORANGE to ORANGE_BALL,
            BallColour.NEON_GREEN to NEON_BALL,
            BallColour.RED to RED_BALL,
        )
        for ((colour, paint) in balls) {
            val f = TestFrame(400, 300)
            f.fill(GRASS)
            f.disc(200.0, 150.0, 10.0, paint)
            val session = SessionSetup(Environment.OUTDOORS, colour, TimeOfDay.NOON)
            val found = f.detect(session = session)
            assertEquals("$colour on grass", 1, found.size)
            assertEquals(200f, found.single().x, 2f)
        }
    }

    @Test
    fun looksOnlyForTheBallInPlay() {
        val f = TestFrame(600, 300)
        f.fill(GRASS)
        f.disc(100.0, 150.0, 10.0, OPTIC_YELLOW)
        f.disc(250.0, 150.0, 10.0, RED_BALL)
        f.disc(400.0, 150.0, 10.0, ORANGE_BALL)
        f.disc(520.0, 150.0, 10.0, WHITE_BALL_LIT)

        fun onlyOne(colour: BallColour, near: Float) {
            val found = f.detect(session = SessionSetup(Environment.OUTDOORS, colour, TimeOfDay.NOON))
            assertEquals("$colour picked up something else", 1, found.size)
            assertEquals(near, found.single().x, 3f)
        }
        onlyOne(BallColour.YELLOW, 100f)
        onlyOne(BallColour.RED, 250f)
        onlyOne(BallColour.ORANGE, 400f)
        onlyOne(BallColour.WHITE, 520f)
    }

    @Test
    fun aWhiteBallIsFoundByBeingBrightAndColourless() {
        val f = TestFrame(400, 300)
        f.fill(GRASS)
        f.disc(120.0, 150.0, 10.0, WHITE_BALL_LIT)
        f.disc(280.0, 150.0, 12.0, GREY_STONE)     // same absence of colour, nothing like as bright

        val found = f.detect(session = WHITE_DAY)
        assertEquals(1, found.size)
        assertEquals(120f, found.single().x, 2f)
    }

    @Test
    fun aWhiteBallInPoorLightNeedsTheEveningSetting() {
        val f = TestFrame(400, 300)
        f.fill(GRASS)
        f.disc(200.0, 150.0, 10.0, DIM_WHITE_BALL)

        assertTrue("too dark to be a ball at noon", f.detect(session = WHITE_DAY).isEmpty())
        val night = SessionSetup(Environment.OUTDOORS, BallColour.WHITE, TimeOfDay.NIGHT)
        assertEquals(1, f.detect(session = night).size)
    }

    @Test
    fun neonGreenIsNotConfusedWithTheGrassItSitsOn() {
        val f = TestFrame(400, 300)
        f.fill(GRASS)
        f.disc(200.0, 150.0, 10.0, NEON_BALL)
        val session = SessionSetup(Environment.OUTDOORS, BallColour.NEON_GREEN, TimeOfDay.NOON)
        val ball = f.detect(session = session).single()
        assertEquals(10f, ball.radius, 2f)
        assertTrue("the mat underneath must still read as grass", ball.groundFraction > 0.9f)
    }

    @Test
    fun radiusTracksApparentSize() {
        for (r in listOf(4, 8, 16, 32)) {
            val f = TestFrame(800, 600)
            f.fill(GRASS)
            f.disc(400.0, 300.0, r.toDouble(), OPTIC_YELLOW)
            val b = f.detect().single()
            assertEquals("radius $r", r.toFloat(), b.radius, (r * 0.15f).coerceAtLeast(1.5f))
        }
    }
}
