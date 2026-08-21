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

    private val appContext = context.applicationContext
    private val sender = Executors.newSingleThreadExecutor()
    private val installId: String = installId(appContext)

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

    /**
     * Claim this phone for whoever owns [code]. The dashboard issues the code; sending it back is
     * the only thing that tells the server whose rounds these are. Calls back off the main thread.
     */
    fun pair(code: String, onDone: (account: String?, error: String?) -> Unit) {
        if (!enabled) { onDone(null, "This build has no dashboard to connect to."); return }
        sender.execute {
            val body = JSONObject().apply {
                put("installId", installId)
                put("code", code.trim().uppercase())
            }
            val result = runCatching {
                val connection = (URL(baseUrl.trimEnd('/') + "/api/pair").openConnection() as HttpURLConnection).apply {
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
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                status to text
            }.getOrNull()

            if (result == null) { onDone(null, "Could not reach shotarc.co.za. Check the connection."); return@execute }
            val (status, text) = result
            when (status) {
                in 200..299 -> {
                    val account = runCatching { JSONObject(text).optString("account") }.getOrNull().orEmpty()
                    if (account.isEmpty()) onDone(null, "The dashboard sent an answer we could not read.")
                    else { setPairedAccount(appContext, account); onDone(account, null) }
                }
                404 -> onDone(null, "That code is wrong or has expired. Reload the dashboard for a fresh one.")
                429 -> onDone(null, "Too many tries from here. Wait a few minutes.")
                401 -> onDone(null, "This build is not allowed to talk to that dashboard.")
                else -> onDone(null, "The dashboard refused it (error $status).")
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

    companion object {
        private const val PREFS = "telemetry"
        private const val KEY_INSTALL = "install_id"
        private const val KEY_ACCOUNT = "paired_account"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The anonymous id this phone reports as. Made once on first run, then kept. */
        fun installId(context: Context): String = prefs(context).let { p ->
            p.getString(KEY_INSTALL, null)
                ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_INSTALL, it).apply() }
        }

        /** The account this phone's rounds belong to, or null while it is unpaired. */
        fun pairedAccount(context: Context): String? =
            prefs(context).getString(KEY_ACCOUNT, null)?.takeIf { it.isNotBlank() }

        fun setPairedAccount(context: Context, account: String?) {
            prefs(context).edit().putString(KEY_ACCOUNT, account).apply()
        }
    }
}
