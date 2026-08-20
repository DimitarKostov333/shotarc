package com.golfapp.tracker

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Sends the round to the dashboard. The whole session goes up after every shot rather than a
 * delta: the server upserts by session and ignores shots it already has, so a phone that was out
 * of signal on the back nine catches up on its own with no queue to keep.
 */
class Telemetry(context: Context, private val baseUrl: String) {

    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
    private val sender = Executors.newSingleThreadExecutor()
    private val installId: String = prefs.getString(KEY_INSTALL, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_INSTALL, it).apply() }

    val sessionId: String = UUID.randomUUID().toString()
    private val startedAt = iso()
    val enabled = baseUrl.isNotBlank()

    private var lastBody: JSONObject? = null
    @Volatile var lastPushOk = false
        private set

    /** Ask the server for the latest build; call back (off the main thread) if it is newer. */
    fun checkForUpdate(currentVersionCode: Int, onNewer: (String) -> Unit) {
        if (!enabled) return
        sender.execute {
            runCatching {
                val connection = (URL(baseUrl.trimEnd('/') + "/api/version").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONObject(body)
                if (json.optInt("versionCode", 0) > currentVersionCode) {
                    onNewer(json.optString("versionName", ""))
                }
            }
        }
    }

    fun announceInstall(versionName: String) {
        if (!enabled) return
        post("/api/install", JSONObject().apply {
            put("installId", installId)
            put("appVersion", versionName)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
        })
    }

    fun push(session: SessionSetup, course: Course?, round: Round?, shots: List<ShotRecord>) {
        if (!enabled) return
        val body = JSONObject().apply {
            put("installId", installId)
            put("sessionId", sessionId)
            put("startedAt", startedAt)
            put("endedAt", iso())
            put("environment", session.environment.name)
            put("ball", session.ball.name)
            put("timeOfDay", session.time.name)
            put("course", course?.name)
            put("coursePar", course?.par ?: JSONObject.NULL)
            put("holesPlayed", round?.holesPlayed ?: 0)
            put("throughPar", round?.throughPar ?: 0)
            put("shots", JSONArray().apply { shots.forEach { put(shotJson(it)) } })
        }
        lastBody = body
        post("/api/sessions", body)
    }

    /** Re-send the current round, for when the phone was off-network on the course. */
    fun resend(onDone: (Boolean) -> Unit) {
        val body = lastBody
        if (!enabled || body == null) { onDone(false); return }
        post("/api/sessions", body, onDone)
    }

    private fun shotJson(shot: ShotRecord) = JSONObject().apply {
        put("struckAt", iso())
        put("hole", shot.hole)
        put("shotNumber", shot.shotNumber)
        put("club", shot.club.name)
        put("lie", shot.lie.name)
        put("ballSpeedMs", shot.ballSpeedMs)
        put("launchDeg", shot.launchDeg)
        put("offlineDeg", shot.offlineDeg)
        put("carryM", shot.carryM)
        put("lateralM", shot.lateralM)
        put("apexM", shot.apexM)
        put("score", shot.score)
        put("fromLat", shot.from.lat)
        put("fromLon", shot.from.lon)
        put("toLat", shot.to.lat)
        put("toLon", shot.to.lon)
        put("toGreenM", shot.toGreenM)
        put("track", JSONArray().apply {
            shot.profile.forEach { point ->
                put(JSONArray().apply { put(round1(point[0])); put(round1(point[1])) })
            }
        })
    }

    private fun post(path: String, body: JSONObject, onDone: ((Boolean) -> Unit)? = null) {
        sender.execute {
            val result = runCatching {
                val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json")
                    if (BuildConfig.INGEST_KEY.isNotEmpty()) {
                        setRequestProperty("X-Ingest-Key", BuildConfig.INGEST_KEY)
                    }
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                connection.disconnect()
                code in 200..299
            }.getOrDefault(false)
            if (path == "/api/sessions") lastPushOk = result
            onDone?.invoke(result)
        }
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private fun iso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.UK)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private companion object {
        const val KEY_INSTALL = "install_id"
    }
}
