package com.golfapp.tracker

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * What a well-struck shot looks like with each club: the launch window it should come out in and
 * the ball speed it is graded against.
 */
enum class Club(
    val label: String,
    val idealLaunchDeg: Double,
    val launchSpreadDeg: Double,
    val referenceSpeedMs: Double,
    /** Backspin cannot be seen from behind, so each club brings its typical figure. */
    val backspinRpm: Double,
) {
    DRIVER("Driver", 13.0, 6.0, 68.0, 2700.0),
    FAIRWAY_WOOD("Fairway wood", 12.0, 6.0, 63.0, 3500.0),
    HYBRID("Hybrid", 15.0, 7.0, 58.0, 4200.0),
    LONG_IRON("Long iron", 16.0, 7.0, 55.0, 4800.0),
    MID_IRON("Mid iron", 19.0, 8.0, 50.0, 6200.0),
    SHORT_IRON("Short iron", 24.0, 9.0, 43.0, 8000.0),
    WEDGE("Wedge", 30.0, 10.0, 33.0, 9500.0),
}

/**
 * What the ball is sitting on. Thick rough costs ball speed and throws the ball up higher and
 * less predictably; a tight lie off a green rewards clean contact with a flatter launch.
 */
enum class Lie(
    val label: String,
    val speedFactor: Double,
    val launchOffsetDeg: Double,
    val spreadFactor: Double,
) {
    FAIRWAY("Fairway", 1.0, 0.0, 1.0),
    ROUGH("Rough", 0.88, 2.5, 1.3),
    GREEN("Putting green", 1.0, -1.0, 0.9),
}

data class ShotSetup(val club: Club, val lie: Lie) {

    val idealLaunchDeg get() = club.idealLaunchDeg + lie.launchOffsetDeg
    val launchSpreadDeg get() = club.launchSpreadDeg * lie.spreadFactor
    val expectedSpeedMs get() = club.referenceSpeedMs * lie.speedFactor

    /** Well outside this and the club selection is wrong, or the track was never the ball. */
    fun plausible(speedMs: Double) =
        speedMs >= expectedSpeedMs * 0.2 && speedMs <= expectedSpeedMs * 1.8

    /**
     * 40% start direction, 30% launch angle against this club and lie, 30% ball speed against
     * what the club is capable of from this surface.
     */
    fun score(speedMs: Double, launchDeg: Double, offlineDeg: Double): Int {
        val straight = 1.0 - min(1.0, abs(offlineDeg) / 10.0)
        val spread = launchSpreadDeg
        val off = launchDeg - idealLaunchDeg
        val launch = exp(-(off * off) / (2 * spread * spread))
        val speed = min(1.0, speedMs / expectedSpeedMs)
        return (100 * (0.4 * straight + 0.3 * launch + 0.3 * speed)).toInt().coerceIn(0, 100)
    }

    fun describe() = "${club.label} from ${lie.label.lowercase()}"
}
