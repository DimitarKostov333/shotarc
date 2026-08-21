package com.golfapp.tracker

/** BT.601 full range, the conversion the camera pipeline uses. */
fun ycbcr(r: Int, g: Int, b: Int) = Triple(
    (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255),
    (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255),
    (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255),
)

val OPTIC_YELLOW = ycbcr(223, 255, 0)
val SHADED_YELLOW = ycbcr(120, 130, 30)
val GRASS = ycbcr(60, 140, 50)
val RED_FLAG = ycbcr(220, 30, 30)
val ORANGE_MARKER = ycbcr(255, 140, 0)
val WHITE_BALL = ycbcr(240, 240, 240)
val SKY = ycbcr(120, 170, 235)
val LED_LIT_YELLOW = ycbcr(170, 200, 60)
val INDOOR_FLOOR = ycbcr(150, 140, 135)
val MAT_GREEN = ycbcr(45, 110, 45)

val WHITE_BALL_LIT = ycbcr(240, 240, 238)
val ORANGE_BALL = ycbcr(255, 140, 0)
val NEON_BALL = ycbcr(140, 255, 40)
val RED_BALL = ycbcr(220, 30, 30)
val GREY_STONE = ycbcr(110, 110, 112)
val DIM_WHITE_BALL = ycbcr(120, 120, 119)

val BLACK_BALL = ycbcr(40, 40, 40)          // matt black, some sheen — dark and colourless
val DIM_BLACK_BALL = ycbcr(24, 24, 24)      // the same ball dimmer, as in flight

val YELLOW_DAY = SessionSetup(Environment.OUTDOORS, BallColour.YELLOW, TimeOfDay.NOON)
val WHITE_DAY = SessionSetup(Environment.OUTDOORS, BallColour.WHITE, TimeOfDay.NOON)
val BLACK_DAY = SessionSetup(Environment.OUTDOORS, BallColour.BLACK, TimeOfDay.NOON)
