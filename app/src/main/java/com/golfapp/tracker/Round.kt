package com.golfapp.tracker

/** One shot as it will be shown on the dashboard: what the camera saw and where it put the ball. */
data class ShotRecord(
    val hole: Int,
    val shotNumber: Int,
    val club: Club,
    val lie: Lie,
    val ballSpeedMs: Double,
    val launchDeg: Double,
    val offlineDeg: Double,
    val carryM: Double,
    val lateralM: Double,
    val apexM: Double,
    val from: LatLon,
    val to: LatLon,
    val toGreenM: Double,
    val profile: List<DoubleArray>,
    val hangTimeS: Double,
    val score: Int,
)

/**
 * A round in progress. The camera measures how the ball left; where it comes down is modelled from
 * that, so the ball walks up the hole shot by shot without the phone ever moving.
 */
class Round(val course: Course) {

    private val _shots = mutableListOf<ShotRecord>()
    private val holeScores = mutableMapOf<Int, Int>()

    val shots: List<ShotRecord> get() = _shots

    var holeIndex = 0
        private set

    val hole get() = course.holes[holeIndex]

    var ball: LatLon = course.holes[0].tee
        private set

    val shotsOnThisHole get() = _shots.count { it.hole == hole.number }
    val metresToGreen get() = ball.metresTo(hole.green)

    /** Strokes taken against par for the holes already finished. */
    val throughPar: Int
        get() = holeScores.entries.sumOf { (number, strokes) ->
            strokes - (course.holes.firstOrNull { it.number == number }?.par ?: 0)
        }

    val holesPlayed get() = holeScores.size

    fun record(metrics: ShotMetrics): ShotRecord {
        // The player aims at the green from wherever the ball is now.
        val aim = ball.bearingTo(hole.green)
        val landing = Flight.landing(
            metrics.ballSpeedMs, metrics.launchAngleDeg, metrics.offlineDeg, metrics.setup.club
        )
        val struck = ball.moved(landing.carryM, aim).moved(landing.lateralM, (aim + 90) % 360)
        val record = ShotRecord(
            hole = hole.number,
            shotNumber = shotsOnThisHole + 1,
            club = metrics.setup.club,
            lie = metrics.setup.lie,
            ballSpeedMs = metrics.ballSpeedMs,
            launchDeg = metrics.launchAngleDeg,
            offlineDeg = metrics.offlineDeg,
            carryM = landing.carryM,
            lateralM = landing.lateralM,
            apexM = landing.apexM,
            from = ball,
            to = struck,
            toGreenM = struck.metresTo(hole.green),
            profile = landing.profile,
            hangTimeS = landing.hangTimeS,
            score = metrics.score,
        )
        _shots.add(record)
        ball = struck
        return record
    }

    /**
     * Finish the hole. [putts] covers everything the camera cannot see — a ball rolling on a green
     * is not a shot it will register.
     */
    fun holeOut(putts: Int = 2) {
        holeScores[hole.number] = shotsOnThisHole + putts
        if (holeIndex < course.holes.lastIndex) {
            holeIndex++
            ball = hole.tee
        }
    }

    fun strokesOn(holeNumber: Int) = holeScores[holeNumber]

    fun describeAgainstPar(): String {
        val diff = throughPar
        return when {
            holesPlayed == 0 -> "no holes finished"
            diff == 0 -> "level par through $holesPlayed"
            diff > 0 -> "+$diff through $holesPlayed"
            else -> "$diff through $holesPlayed"
        }
    }
}
