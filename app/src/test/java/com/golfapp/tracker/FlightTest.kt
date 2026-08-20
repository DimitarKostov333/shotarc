package com.golfapp.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Checked against the carry numbers a launch monitor reports for these conditions. */
class FlightTest {

    /** PGA Tour averages as published by TrackMan: ball speed, launch, spin, carry. */
    @Test
    fun matchesPublishedLaunchMonitorCarries() {
        val cases = listOf(
            listOf(74.7, 10.4, 2685.0, 251.0),   // driver
            listOf(69.3, 9.3, 3655.0, 226.0),    // 3 wood
            listOf(60.8, 12.1, 5280.0, 179.0),   // 5 iron
            listOf(53.6, 16.3, 7124.0, 158.0),   // 7 iron
            listOf(46.0, 24.2, 9316.0, 128.0),   // pitching wedge
        )
        for ((speed, launch, spin, published) in cases) {
            val carry = Flight.carryFor(speed, launch, spin)
            val error = (carry - published) / published
            println("%.0f m/s at %.1f°, %.0f rpm -> %.0f m (published %.0f, %+.1f%%)"
                .format(speed, launch, spin, carry, published, error * 100))
            assertTrue("carry $carry against published $published", abs(error) < 0.10)
        }
    }

    @Test
    fun apexAndHangTimeAreRealistic() {
        val landing = Flight.landing(70.0, 13.0, 0.0, Club.DRIVER)
        assertTrue("apex was ${landing.apexM}", landing.apexM in 22.0..45.0)
        assertTrue("hang was ${landing.hangTimeS}", landing.hangTimeS in 5.0..8.5)
    }

    @Test
    fun aSlowerSwingCarriesLess() {
        val fast = Flight.landing(65.0, 13.0, 0.0, Club.DRIVER).carryM
        val slow = Flight.landing(50.0, 13.0, 0.0, Club.DRIVER).carryM
        assertTrue(fast > slow + 40)
    }

    @Test
    fun offlineAngleMovesTheBallSideways() {
        val straight = Flight.landing(60.0, 13.0, 0.0, Club.DRIVER)
        val pushed = Flight.landing(60.0, 13.0, 5.0, Club.DRIVER)
        assertEquals(0.0, straight.lateralM, 0.01)
        assertTrue("5 degrees right of a 200 m shot is roughly 18 m", pushed.lateralM in 12.0..25.0)
        assertTrue(pushed.carryM < straight.carryM)
    }

    @Test
    fun spinLiftsTheBallHigher() {
        val spun = Flight.landing(50.0, 19.0, 0.0, Club.MID_IRON)
        val lessSpun = Flight.landing(50.0, 19.0, 0.0, Club.DRIVER)
        println("apex at 6200 rpm ${spun.apexM} vs 2700 rpm ${lessSpun.apexM}")
        assertTrue("backspin is what holds a ball up", spun.apexM > lessSpun.apexM)
        assertTrue("and past a point it costs carry, which is why drivers are spun down",
            spun.carryM < lessSpun.carryM)
    }
}
