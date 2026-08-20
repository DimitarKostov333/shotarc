package com.golfapp.tracker

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the ball comes down, from the launch conditions the camera measured.
 *
 * The camera sees the first fifth of a second of flight and nothing after it, so the landing point
 * is modelled rather than observed: a golf ball flown forward under gravity, drag and the lift its
 * backspin generates. Spin is the one thing that cannot be seen from behind the ball, so each club
 * brings its typical figure — which is why the club selection matters to the answer.
 */
object Flight {

    private const val MASS_KG = 0.04593
    private const val RADIUS_M = 0.021335
    private const val AREA = Math.PI * RADIUS_M * RADIUS_M
    private const val AIR_DENSITY = 1.225           // sea level; thinner air carries further
    private const val GRAVITY = 9.81
    private const val STEP_S = 0.002
    private const val MAX_SECONDS = 15.0

    data class Landing(
        val carryM: Double,
        val lateralM: Double,
        val hangTimeS: Double,
        val apexM: Double,
        /** Distance/height pairs along the flight, for drawing the profile. */
        val profile: List<DoubleArray>,
    )

    fun landing(speedMs: Double, launchDeg: Double, offlineDeg: Double, club: Club): Landing =
        landing(speedMs, launchDeg, offlineDeg, club.backspinRpm)

    /** Carry alone, for checking the model against published launch monitor figures. */
    fun carryFor(speedMs: Double, launchDeg: Double, spinRpm: Double) =
        landing(speedMs, launchDeg, 0.0, spinRpm).carryM

    fun landing(speedMs: Double, launchDeg: Double, offlineDeg: Double, spinRpm: Double): Landing {
        val spin = spinRpm * 2 * Math.PI / 60.0        // rad/s
        val launch = Math.toRadians(launchDeg)
        var vx = speedMs * cos(launch)
        var vy = speedMs * sin(launch)
        var x = 0.0
        var y = 0.0
        var apex = 0.0
        var t = 0.0
        var nextSample = 0.0
        val profile = ArrayList<DoubleArray>()

        while (t < MAX_SECONDS) {
            val v = sqrt(vx * vx + vy * vy)
            if (v < 0.1) break
            // Spin ratio: how fast the surface is turning against how fast the ball is moving.
            val s = spin * RADIUS_M / v
            val drag = 0.24 + 0.18 * s
            val lift = (1.9 * s).coerceAtMost(0.35)
            val q = 0.5 * AIR_DENSITY * AREA * v * v / MASS_KG

            // drag opposes the path, lift acts square to it
            val ax = -q * (drag * vx / v + lift * vy / v)
            val ay = -q * (drag * vy / v - lift * vx / v) - GRAVITY

            vx += ax * STEP_S
            vy += ay * STEP_S
            x += vx * STEP_S
            y += vy * STEP_S
            t += STEP_S
            if (y > apex) apex = y
            if (t >= nextSample) {
                profile.add(doubleArrayOf(x, maxOf(0.0, y)))
                nextSample += 0.2
            }
            if (y <= 0.0 && t > 0.1) break
        }

        profile.add(doubleArrayOf(x, 0.0))
        val offline = Math.toRadians(offlineDeg)
        return Landing(
            carryM = x * cos(offline),
            lateralM = x * sin(offline),
            hangTimeS = t,
            apexM = apex,
            profile = profile,
        )
    }
}
