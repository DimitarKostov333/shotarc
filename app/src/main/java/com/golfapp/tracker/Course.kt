package com.golfapp.tracker

import android.content.Context
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double) {

    fun metresTo(other: LatLon): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(other.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(other.lon - lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R * asin(sqrt(h))
    }

    /** Compass bearing in degrees, clockwise from north. */
    fun bearingTo(other: LatLon): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(other.lat)
        val dl = Math.toRadians(other.lon - lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun moved(metres: Double, bearingDeg: Double): LatLon {
        val d = metres / EARTH_R
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), Math.toDegrees(l2))
    }

    private companion object {
        const val EARTH_R = 6371000.0
    }
}

data class Hole(
    val number: Int,
    val par: Int,
    /** False when no mapper filled in the par and it was read off the hole's length. */
    val parKnown: Boolean,
    val lengthM: Int,
    val path: List<LatLon>,
) {
    val tee get() = path.first()
    val green get() = path.last()
}

data class Course(val name: String, val centre: LatLon, val holes: List<Hole>) {
    val par get() = holes.sumOf { it.par }
}

/** Courses traced from OpenStreetMap and built into the app by tools/build_courses.py. */
object CourseLibrary {

    fun load(context: Context): List<Course> =
        context.assets.open("courses.json").bufferedReader().use { parse(it.readText()) }

    fun parse(json: String): List<Course> {
        val root = JSONObject(json)
        val attribution = root.optString("attribution")
        require(attribution.isNotEmpty()) { "course data must carry its attribution" }
        val courses = root.getJSONArray("courses")
        return (0 until courses.length()).map { index ->
            val course = courses.getJSONObject(index)
            val holes = course.getJSONArray("holes")
            Course(
                name = course.getString("name"),
                centre = LatLon(course.getDouble("lat"), course.getDouble("lon")),
                holes = (0 until holes.length()).map { holeIndex ->
                    val hole = holes.getJSONObject(holeIndex)
                    val path = hole.getJSONArray("path")
                    Hole(
                        number = hole.getInt("n"),
                        par = hole.getInt("par"),
                        parKnown = hole.optBoolean("known", false),
                        lengthM = hole.getInt("m"),
                        path = (0 until path.length()).map { pointIndex ->
                            val point = path.getJSONArray(pointIndex)
                            LatLon(point.getDouble(1), point.getDouble(0))
                        },
                    )
                },
            )
        }
    }

    const val ATTRIBUTION = "© OpenStreetMap contributors"
}
