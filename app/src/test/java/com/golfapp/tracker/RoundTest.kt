package com.golfapp.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun metricsFor(speed: Double, launch: Double, offline: Double, club: Club) = ShotMetrics(
    ballSpeedMs = speed,
    launchAngleDeg = launch,
    offlineDeg = offline,
    curveDeg = 0.0,
    apexM = 0.0,
    flightSeconds = 0.3,
    samples = 12,
    score = 80,
    grade = "Good",
    speedIsLowerBound = false,
    stillRising = true,
    stoppedEarly = false,
    setup = ShotSetup(club, Lie.FAIRWAY),
)

class RoundTest {

    private val courses = CourseLibrary.parse(File("src/main/assets/courses.json").readText())

    @Test
    fun theShippedCoursesLoad() {
        assertTrue("no courses in the asset", courses.isNotEmpty())
        println("courses: " + courses.joinToString { "${it.name} (${it.holes.size} holes, par ${it.par})" })
        for (course in courses) {
            assertTrue("${course.name} has too few holes", course.holes.size >= 9)
            assertTrue("${course.name} has a silly par", course.par in 27..80)
            for (hole in course.holes) {
                assertTrue("hole ${hole.number} par ${hole.par}", hole.par in 3..6)
                assertTrue("hole ${hole.number} is ${hole.lengthM} m", hole.lengthM in 50..700)
                assertTrue(hole.path.size >= 2)
            }
        }
    }

    @Test
    fun aFullEighteenSumsToASensiblePar() {
        val full = courses.filter { it.holes.size == 18 }
        assertTrue("no complete courses", full.isNotEmpty())
        for (course in full) assertTrue("${course.name} par ${course.par}", course.par in 68..76)
    }

    @Test
    fun aDriveWalksTheBallUpTheHole() {
        val course = courses.first { it.holes.size == 18 }
        val round = Round(course)
        val startToGreen = round.metresToGreen
        val shot = round.record(metricsFor(65.0, 12.0, 0.0, Club.DRIVER))
        println("${course.name} hole ${round.hole.number}: ${startToGreen.toInt()} m -> ${shot.toGreenM.toInt()} m after ${shot.carryM.toInt()} m carry")

        assertTrue("the drive went nowhere", shot.carryM > 180)
        assertTrue("the ball should be closer to the green", shot.toGreenM < startToGreen)
        assertEquals(1, round.shotsOnThisHole)
        assertEquals(shot.to, round.ball)
    }

    @Test
    fun aPushedShotFinishesRightOfTheLine() {
        val course = courses.first { it.holes.size == 18 }
        val straight = Round(course).record(metricsFor(60.0, 13.0, 0.0, Club.DRIVER))
        val pushed = Round(course).record(metricsFor(60.0, 13.0, 8.0, Club.DRIVER))
        assertEquals(0.0, straight.lateralM, 0.1)
        assertTrue("8 degrees right should be tens of metres", pushed.lateralM > 20)
        assertTrue(pushed.to.metresTo(straight.to) > 20)
    }

    @Test
    fun scoreIsKeptAgainstPar() {
        val course = courses.first { it.holes.size == 18 }
        val round = Round(course)
        val firstPar = round.hole.par

        repeat(firstPar - 2) { round.record(metricsFor(55.0, 15.0, 0.0, Club.MID_IRON)) }
        round.holeOut(putts = 2)                       // par + 0
        assertEquals(0, round.throughPar)
        assertEquals(firstPar, round.strokesOn(course.holes[0].number))

        val secondPar = round.hole.par
        repeat(secondPar) { round.record(metricsFor(55.0, 15.0, 0.0, Club.MID_IRON)) }
        round.holeOut(putts = 2)                       // two over
        assertEquals(2, round.throughPar)
        assertTrue(round.describeAgainstPar().startsWith("+2"))
    }

    @Test
    fun eachHoleStartsFromItsOwnTee() {
        val course = courses.first { it.holes.size == 18 }
        val round = Round(course)
        round.record(metricsFor(60.0, 13.0, 0.0, Club.DRIVER))
        round.holeOut()
        assertEquals(course.holes[1].tee, round.ball)
        assertEquals(course.holes[1].number, round.hole.number)
        assertEquals(0, round.shotsOnThisHole)
    }
}
