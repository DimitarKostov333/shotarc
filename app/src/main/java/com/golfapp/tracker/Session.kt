package com.golfapp.tracker

enum class Environment(val label: String) {
    OUTDOORS("Outdoors"),
    INDOORS("Indoors"),
}

/**
 * The ball being hit, as a point in YCbCr and how far around it to look.
 *
 * White is the hard one: it has no colour at all — Cb and Cr both sit at 128, exactly where
 * concrete, paint and cloud sit — so it is found by being bright and unsaturated rather than by
 * being any particular hue, and it needs a tight window to stay off everything else that is
 * neutral. Red is the opposite: unmistakable in colour but dark, so it gets a low luma floor.
 */
enum class BallColour(
    val label: String,
    val cb: Int,
    val cr: Int,
    val spread: Int,
    val lumaFloor: Int,
) {
    WHITE("White", 128, 128, 16, 150),
    YELLOW("Yellow", 6, 133, 42, 80),
    ORANGE("Orange", 39, 197, 45, 70),
    NEON_GREEN("Neon green", 40, 88, 30, 115),
    RED("Red", 96, 223, 45, 45),
}

/**
 * How much light there is and what colour it is. Low sun and artificial light both dim the ball
 * and push its colour around, so both the brightness floor and the colour window move with it.
 */
enum class TimeOfDay(
    val label: String,
    val lumaFactor: Double,
    val spreadFactor: Double,
    val exposureBiasEv: Float,
) {
    MORNING("Morning", 0.70, 1.15, -0.5f),
    NOON("Noon", 1.00, 1.00, -0.7f),
    NIGHT("Night", 0.45, 1.30, 0.0f),
}

/** Chosen once at the start of a session. */
data class SessionSetup(
    val environment: Environment = Environment.OUTDOORS,
    val ball: BallColour = BallColour.WHITE,
    val time: TimeOfDay = TimeOfDay.NOON,
) {
    private val indoors get() = environment == Environment.INDOORS

    val lumaFloor: Int
        get() = (ball.lumaFloor * time.lumaFactor * if (indoors) 0.75 else 1.0).toInt()

    val spread: Int
        get() = (ball.spread * time.spreadFactor * if (indoors) 1.15 else 1.0).toInt()

    /** A darker frame means a shorter shutter and a sharper ball — but only if there is light. */
    val exposureBiasEv: Float
        get() = (time.exposureBiasEv + if (indoors) 0.25f else 0f).coerceAtMost(0f)

    fun describe() = "${environment.label} · ${ball.label.lowercase()} ball · ${time.label.lowercase()}"
}
