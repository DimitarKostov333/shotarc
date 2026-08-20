package com.golfapp.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotSetupTest {

    private val fairway = Lie.FAIRWAY

    @Test
    fun theSameStrikeIsGradedAgainstTheClubInHand() {
        val shot = Triple(40.0, 25.0, 0.0)                     // 40 m/s, 25 degrees, dead straight
        val asShortIron = ShotSetup(Club.SHORT_IRON, fairway).score(shot.first, shot.second, shot.third)
        val asDriver = ShotSetup(Club.DRIVER, fairway).score(shot.first, shot.second, shot.third)
        println("40 m/s at 25°: short iron=$asShortIron driver=$asDriver")
        assertTrue("a fine short iron", asShortIron >= 85)
        assertTrue("a poor drive", asDriver < 70)
    }

    @Test
    fun aWedgeIsNotExpectedToMatchADriverForSpeed() {
        val wedge = ShotSetup(Club.WEDGE, fairway).score(33.0, 30.0, 0.0)
        val driver = ShotSetup(Club.DRIVER, fairway).score(33.0, 30.0, 0.0)
        assertTrue(wedge > driver)
    }

    @Test
    fun theRoughIsGradedMoreKindly() {
        val speed = 45.0
        val fromRough = ShotSetup(Club.MID_IRON, Lie.ROUGH).score(speed, 21.5, 0.0)
        val fromFairway = ShotSetup(Club.MID_IRON, fairway).score(speed, 21.5, 0.0)
        println("45 m/s mid iron: rough=$fromRough fairway=$fromFairway")
        assertTrue("rough costs ball speed, so expectations drop", fromRough > fromFairway)
    }

    @Test
    fun aTightLieWantsAFlatterLaunch() {
        val offGreen = ShotSetup(Club.MID_IRON, Lie.GREEN)
        val offRough = ShotSetup(Club.MID_IRON, Lie.ROUGH)
        assertTrue(offGreen.idealLaunchDeg < offRough.idealLaunchDeg)
        assertTrue("thick grass throws the ball up less predictably", offRough.launchSpreadDeg > offGreen.launchSpreadDeg)
    }

    @Test
    fun straightnessStillCarriesTheMostWeight() {
        val setup = ShotSetup(Club.MID_IRON, fairway)
        val straight = setup.score(50.0, 19.0, 0.0)
        val pushed = setup.score(50.0, 19.0, 12.0)
        assertTrue(straight - pushed >= 35)
    }

    @Test
    fun speedsNoClubCouldProduceAreImplausible() {
        val wedge = ShotSetup(Club.WEDGE, fairway)
        assertTrue(wedge.plausible(30.0))
        assertFalse("that was not a wedge", wedge.plausible(70.0))
        assertTrue(ShotSetup(Club.DRIVER, fairway).plausible(70.0))
    }
}
