import express from 'express'
import { createHash, randomUUID } from 'node:crypto'
import { existsSync, statSync, createReadStream } from 'node:fs'
import { join } from 'node:path'
import { openDatabase } from './db.js'
import { renderDashboard, renderSession } from './views.js'

const PORT = Number(process.env.PORT ?? 8080)
const BIND = process.env.BIND ?? '127.0.0.1'
const DATA_DIR = process.env.DATA_DIR ?? './data'
const APK_PATH = process.env.APK_PATH ?? join(DATA_DIR, 'golf-tracker.apk')
const INGEST_KEY = process.env.INGEST_KEY ?? ''
const DASHBOARD_KEY = process.env.DASHBOARD_KEY ?? ''

const db = openDatabase(join(DATA_DIR, 'golf.db'))
const app = express()
app.disable('x-powered-by')
app.set('trust proxy', true)
app.use(express.json({ limit: '2mb' }))

const now = () => new Date().toISOString()

/** Keep enough of the address to spot one device hammering the endpoint, not enough to locate it. */
function ipPrefix(req) {
  const ip = (req.ip ?? '').replace('::ffff:', '')
  if (ip.includes(':')) return ip.split(':').slice(0, 3).join(':') + '::'
  return ip.split('.').slice(0, 3).join('.') + '.0'
}

function requireKey(configured, header) {
  return (req, res, next) => {
    if (!configured) return next()
    const offered = req.get(header) ?? req.query.key
    if (offered === configured) return next()
    res.status(401).json({ error: 'unauthorised' })
  }
}

// ---------------------------------------------------------------- the app itself

app.get(['/', '/install'], (req, res) => {
  const apk = existsSync(APK_PATH) ? statSync(APK_PATH) : null
  res.type('html').send(renderInstallPage(req, apk))
})

app.get('/golf-tracker.apk', (req, res) => {
  if (!existsSync(APK_PATH)) return res.status(404).send('No build uploaded yet')
  db.prepare('INSERT INTO downloads (at, user_agent, ip_prefix) VALUES (?, ?, ?)')
    .run(now(), req.get('user-agent') ?? '', ipPrefix(req))
  res.setHeader('Content-Type', 'application/vnd.android.package-archive')
  res.setHeader('Content-Disposition', 'attachment; filename="golf-tracker.apk"')
  createReadStream(APK_PATH).pipe(res)
})

// ---------------------------------------------------------------- ingest from the phone

app.post('/api/install', requireKey(INGEST_KEY, 'x-ingest-key'), (req, res) => {
  const { installId, appVersion, device, android } = req.body ?? {}
  if (!installId) return res.status(400).json({ error: 'installId required' })
  db.prepare(`
    INSERT INTO installs (install_id, first_seen, last_seen, app_version, device, android)
    VALUES (@id, @at, @at, @appVersion, @device, @android)
    ON CONFLICT (install_id) DO UPDATE SET
      last_seen = @at, app_version = @appVersion, device = @device, android = @android
  `).run({ id: installId, at: now(), appVersion: appVersion ?? null, device: device ?? null, android: android ?? null })
  res.json({ ok: true })
})

app.post('/api/sessions', requireKey(INGEST_KEY, 'x-ingest-key'), (req, res) => {
  const s = req.body ?? {}
  if (!s.installId) return res.status(400).json({ error: 'installId required' })
  const sessionId = s.sessionId ?? randomUUID()

  const save = db.transaction(() => {
    db.prepare(`
      INSERT INTO sessions (session_id, install_id, started_at, ended_at, environment, ball,
                            time_of_day, course, course_par, holes_played, through_par)
      VALUES (@sessionId, @installId, @startedAt, @endedAt, @environment, @ball,
              @timeOfDay, @course, @coursePar, @holesPlayed, @throughPar)
      ON CONFLICT (session_id) DO UPDATE SET
        ended_at = @endedAt, holes_played = @holesPlayed, through_par = @throughPar
    `).run({
      sessionId,
      installId: s.installId,
      startedAt: s.startedAt ?? now(),
      endedAt: s.endedAt ?? null,
      environment: s.environment ?? null,
      ball: s.ball ?? null,
      timeOfDay: s.timeOfDay ?? null,
      course: s.course ?? null,
      coursePar: s.coursePar ?? null,
      holesPlayed: s.holesPlayed ?? 0,
      throughPar: s.throughPar ?? 0,
    })

    const insert = db.prepare(`
      INSERT INTO shots (session_id, struck_at, hole, shot_number, club, lie, ball_speed_ms,
                         launch_deg, offline_deg, curve_deg, carry_m, lateral_m, apex_m, score,
                         from_lat, from_lon, to_lat, to_lon, to_green_m, track)
      VALUES (@sessionId, @struckAt, @hole, @shotNumber, @club, @lie, @ballSpeedMs, @launchDeg,
              @offlineDeg, @curveDeg, @carryM, @lateralM, @apexM, @score,
              @fromLat, @fromLon, @toLat, @toLon, @toGreenM, @track)
      ON CONFLICT (session_id, hole, shot_number) DO NOTHING
    `)
    for (const shot of s.shots ?? []) {
      insert.run({
        sessionId,
        struckAt: shot.struckAt ?? now(),
        hole: shot.hole ?? null,
        shotNumber: shot.shotNumber ?? null,
        club: shot.club ?? null,
        lie: shot.lie ?? null,
        ballSpeedMs: shot.ballSpeedMs ?? null,
        launchDeg: shot.launchDeg ?? null,
        offlineDeg: shot.offlineDeg ?? null,
        curveDeg: shot.curveDeg ?? null,
        carryM: shot.carryM ?? null,
        lateralM: shot.lateralM ?? null,
        apexM: shot.apexM ?? null,
        score: shot.score ?? null,
        fromLat: shot.fromLat ?? null,
        fromLon: shot.fromLon ?? null,
        toLat: shot.toLat ?? null,
        toLon: shot.toLon ?? null,
        toGreenM: shot.toGreenM ?? null,
        track: shot.track ? JSON.stringify(shot.track) : null,
      })
    }
  })
  save()
  res.json({ ok: true, sessionId })
})

// ---------------------------------------------------------------- reading it back

const dashboardGuard = requireKey(DASHBOARD_KEY, 'x-dashboard-key')

app.get('/api/stats', dashboardGuard, (req, res) => res.json(stats()))

app.get('/api/sessions', dashboardGuard, (req, res) =>
  res.json(db.prepare(`
    SELECT s.*, COUNT(sh.id) AS shots, ROUND(MAX(sh.carry_m), 1) AS longest_m
    FROM sessions s LEFT JOIN shots sh ON sh.session_id = s.session_id
    GROUP BY s.session_id ORDER BY s.started_at DESC LIMIT 200
  `).all()))

app.get('/api/sessions/:id', dashboardGuard, (req, res) => {
  const session = db.prepare('SELECT * FROM sessions WHERE session_id = ?').get(req.params.id)
  if (!session) return res.status(404).json({ error: 'no such session' })
  const shots = db.prepare('SELECT * FROM shots WHERE session_id = ? ORDER BY hole, shot_number').all(req.params.id)
  res.json({ session, shots: shots.map(s => ({ ...s, track: s.track ? JSON.parse(s.track) : null })) })
})

app.get('/dashboard', dashboardGuard, (req, res) => {
  const sessions = db.prepare(`
    SELECT s.*, COUNT(sh.id) AS shots, ROUND(MAX(sh.carry_m), 1) AS longest_m
    FROM sessions s LEFT JOIN shots sh ON sh.session_id = s.session_id
    GROUP BY s.session_id ORDER BY s.started_at DESC LIMIT 100
  `).all()
  res.type('html').send(renderDashboard(stats(), sessions, req.query.key))
})

app.get('/dashboard/session/:id', dashboardGuard, (req, res) => {
  const session = db.prepare('SELECT * FROM sessions WHERE session_id = ?').get(req.params.id)
  if (!session) return res.status(404).send('No such session')
  const shots = db.prepare('SELECT * FROM shots WHERE session_id = ? ORDER BY hole, shot_number').all(req.params.id)
  res.type('html').send(renderSession(session, shots, req.query.key))
})

function stats() {
  const one = sql => db.prepare(sql).get()
  return {
    installs: one('SELECT COUNT(*) AS n FROM installs').n,
    downloads: one('SELECT COUNT(*) AS n FROM downloads').n,
    sessions: one('SELECT COUNT(*) AS n FROM sessions').n,
    shots: one('SELECT COUNT(*) AS n FROM shots').n,
    longestM: one('SELECT ROUND(MAX(carry_m), 1) AS n FROM shots').n,
    fastestMs: one('SELECT ROUND(MAX(ball_speed_ms), 1) AS n FROM shots').n,
    installedOfDownloads: (() => {
      const d = one('SELECT COUNT(*) AS n FROM downloads').n
      const i = one('SELECT COUNT(*) AS n FROM installs').n
      return d ? Math.round((i / d) * 100) : null
    })(),
  }
}

function renderInstallPage(req, apk) {
  const base = `${req.protocol}://${req.get('host')}`
  const size = apk ? `${(apk.size / 1048576).toFixed(1)} MB` : 'not uploaded yet'
  const built = apk ? new Date(apk.mtime).toISOString().slice(0, 16).replace('T', ' ') : '—'
  const digest = apk ? createHash('sha256').update(String(apk.mtimeMs + apk.size)).digest('hex').slice(0, 12) : ''
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Golf Ball Tracker</title>
<style>
 body{background:#0b0f0c;color:#e8ffe9;font:16px/1.6 system-ui,sans-serif;margin:0;padding:48px 24px;display:flex;justify-content:center}
 main{max-width:32rem}h1{color:#e8ff00;font-size:1.5rem;margin:0 0 .25rem}
 p{color:#9fb3a3}a.button{display:inline-block;background:#e8ff00;color:#101410;font-weight:700;
 padding:14px 22px;border-radius:12px;text-decoration:none;margin:18px 0}
 code{background:#131a14;padding:2px 6px;border-radius:5px}
 ol{color:#9fb3a3}li{margin:.4rem 0}
</style>
<main>
 <h1>Golf Ball Tracker</h1>
 <p>Android · ${size} · built ${built} ${digest ? `· build ${digest}` : ''}</p>
 <a class="button" href="/golf-tracker.apk">Download the APK</a>
 <ol>
  <li>Tap the download. Chrome will warn that the file can harm your device — it says that about
      every APK not from Play.</li>
  <li>Open it. Android will ask to allow installs from this browser: turn it on, then come back.</li>
  <li>Allow the camera when the app first asks.</li>
 </ol>
 <p>Serving from <code>${base}</code></p>
</main>`
}

app.listen(PORT, BIND, () => console.log(`golf tracker server on ${BIND}:${PORT}`))
