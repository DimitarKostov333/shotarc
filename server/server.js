import express from 'express'
import { randomUUID, randomBytes } from 'node:crypto'
import { existsSync, statSync, createReadStream, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname as pathDirname } from 'node:path'
import { openDatabase } from './db.js'
import { renderDashboard, renderSession, renderLanding, renderLogin, renderSignup, renderInstall, renderPrivacy, prettyClub } from './views.js'
import { hashPassword, verifyPassword, makeSessionCookie, clearSessionCookie, currentUser, requirePage, requireApi } from './auth.js'
import { limiter, guard } from './limits.js'
import * as v from './validate.js'

const PORT = Number(process.env.PORT ?? 8080)
const BIND = process.env.BIND ?? '127.0.0.1'
const DATA_DIR = process.env.DATA_DIR ?? './data'
const APK_PATH = process.env.APK_PATH ?? join(DATA_DIR, 'golf-tracker.apk')
const INGEST_KEY = process.env.INGEST_KEY ?? ''
const DASHBOARD_KEY = process.env.DASHBOARD_KEY ?? ''
const ADMIN_USER = process.env.ADMIN_USER ?? ''
// A stable secret keeps sessions alive across restarts. A guessable one lets anyone forge a
// session cookie, so with nothing configured take a fresh random secret each boot instead.
const SESSION_SECRET = process.env.SESSION_SECRET || DASHBOARD_KEY || randomBytes(32).toString('hex')
if (!process.env.SESSION_SECRET && !DASHBOARD_KEY) {
  console.warn('no SESSION_SECRET set — signing sessions with a random one; logins end at restart')
}
const PUBLIC_DIR = pathDirname(fileURLToPath(import.meta.url)) + '/public'

function currentVersion() {
  try { return JSON.parse(readFileSync(join(DATA_DIR, 'version.json'), 'utf8')) }
  catch { return { versionCode: 0, versionName: 'unknown' } }
}

const db = openDatabase(join(DATA_DIR, 'golf.db'))

// Seed the first dashboard account from the environment, so a fresh box has a way in.
if (ADMIN_USER && process.env.ADMIN_PASSWORD) {
  const exists = db.prepare('SELECT 1 FROM users WHERE username = ?').get(ADMIN_USER)
  if (!exists) {
    db.prepare('INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)')
      .run(ADMIN_USER, hashPassword(process.env.ADMIN_PASSWORD), new Date().toISOString())
    console.log(`seeded dashboard account: ${ADMIN_USER}`)
  }
}

// Allowances per address prefix. The phone pushes the whole round after every shot, so ingest
// gets a wide one; the forms get narrow ones because a person only ever needs a few tries.
const MAX_SHOTS = 400
const PAIR_CODE = /^[A-Z0-9]{6}$/
const everything = limiter({ max: 600, windowMs: 60_000 })
const loginTries = limiter({ max: 10, windowMs: 15 * 60_000 })
const signupTries = limiter({ max: 5, windowMs: 60 * 60_000 })
const pairTries = limiter({ max: 10, windowMs: 15 * 60_000 })
const downloadTries = limiter({ max: 30, windowMs: 10 * 60_000 })
const ingestTries = limiter({ max: 240, windowMs: 10 * 60_000 })

const byAddress = req => ipPrefix(req)
const tooMany = (req, res) => res.status(429).type('text/plain').send('Too many requests. Try again shortly.')
const tooManyJson = (req, res) => res.status(429).json({ error: 'too many requests' })

const ingestGuard = guard(ingestTries, byAddress, tooManyJson)

const app = express()
app.disable('x-powered-by')
app.set('trust proxy', true)
app.use(express.json({ limit: '2mb' }))
app.use(express.urlencoded({ extended: false }))
// Static assets are served before the counter, so a page load costs one request against it
// rather than a dozen; they are immutable and cached anyway.
app.use('/assets', express.static(PUBLIC_DIR, { maxAge: '7d', immutable: true }))
app.use(guard(everything, byAddress, tooMany))

// Defence in depth behind the escaping: even if something slipped through, injected markup has
// nowhere to send anything and no way to run. The two inline scripts carry a per-request nonce;
// style-src stays loose because the pages use style attributes throughout.
app.use((req, res, next) => {
  const nonce = randomBytes(16).toString('base64')
  res.locals.nonce = nonce
  res.setHeader('Content-Security-Policy', [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}'`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; '))
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Referrer-Policy', 'same-origin')
  res.setHeader('X-Frame-Options', 'DENY')
  next()
})

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

app.get('/', (req, res) => {
  const apk = existsSync(APK_PATH) ? statSync(APK_PATH) : null
  res.type('html').send(renderLanding(req, apk, currentUser(req, SESSION_SECRET), res.locals.nonce))
})

app.get('/install', (req, res) => {
  const apk = existsSync(APK_PATH) ? statSync(APK_PATH) : null
  res.type('html').send(renderInstall(apk, currentVersion(), res.locals.nonce))
})

app.get('/privacy', (req, res) => res.type('html').send(renderPrivacy()))

app.get('/api/version', (req, res) => {
  res.setHeader('Cache-Control', 'no-store')
  res.json({ ...currentVersion(), url: `${req.protocol}://${req.get('host')}/golf-tracker.apk` })
})

// --- dashboard login

app.get('/login', (req, res) => {
  if (currentUser(req, SESSION_SECRET)) return res.redirect(safeNext(req.query.next))
  res.type('html').send(renderLogin({ next: req.query.next, error: null }))
})

app.post('/login', (req, res) => {
  const from = ipPrefix(req)
  const next = safeNext(req.body?.next)
  if (!loginTries.take(from)) {
    res.setHeader('Retry-After', String(loginTries.retryAfter(from)))
    return res.status(429).type('html').send(renderLogin({
      next: req.body?.next, error: 'Too many attempts. Try again in a few minutes.',
    }))
  }

  const username = v.text(req.body?.username, MAX_USERNAME)?.toLowerCase() ?? ''
  const password = typeof req.body?.password === 'string' ? req.body.password : ''
  const row = username
    ? db.prepare('SELECT username, password_hash FROM users WHERE username = ?').get(username)
    : null
  // Hash against a throwaway when there is no such account, so both paths cost the same.
  if (verifyPassword(password, row?.password_hash ?? DUMMY_HASH) && row) {
    loginTries.reset(from)
    res.setHeader('Set-Cookie', makeSessionCookie(row.username, SESSION_SECRET, Date.now(), req.secure))
    return res.redirect(next)
  }
  res.status(401).type('html').send(renderLogin({ next: req.body?.next, error: 'Wrong username or password' }))
})

// Same work whether or not the account exists, so timing cannot be used to find usernames.
const DUMMY_HASH = hashPassword(randomUUID())

// --- new accounts

const USERNAME_RE = /^[a-z0-9][a-z0-9_-]{2,23}$/
const MAX_USERNAME = 24
const MAX_PASSWORD = 200

/** The one reason this sign-up would be refused, or null. */
function signupProblem(username, password) {
  if (!USERNAME_RE.test(username)) {
    return 'Pick a name of 3 to 24 characters: letters, numbers, dash or underscore.'
  }
  if (password.length < 10) return 'Use a password of at least 10 characters.'
  if (password.length > MAX_PASSWORD) return 'That password is longer than 200 characters.'
  if (username && password.toLowerCase().includes(username)) return 'Keep your name out of your password.'
  return null
}

app.get('/signup', (req, res) => {
  if (currentUser(req, SESSION_SECRET)) return res.redirect('/dashboard')
  res.type('html').send(renderSignup({ username: '', error: null }))
})

app.post('/signup', (req, res) => {
  const from = ipPrefix(req)
  const username = v.text(req.body?.username, MAX_USERNAME)?.toLowerCase() ?? ''
  const password = typeof req.body?.password === 'string' ? req.body.password : ''
  const refuse = (status, error) =>
    res.status(status).type('html').send(renderSignup({ username, error }))

  // Every attempt counts here, failed or not — this is the door onto the box.
  if (!signupTries.take(from)) {
    res.setHeader('Retry-After', String(signupTries.retryAfter(from)))
    return refuse(429, 'Too many accounts from here. Try again later.')
  }

  const problem = signupProblem(username, password)
  if (problem) return refuse(400, problem)
  if (db.prepare('SELECT 1 FROM users WHERE username = ?').get(username)) {
    return refuse(409, 'That name is taken.')
  }

  db.prepare('INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)')
    .run(username, hashPassword(password), now())
  res.setHeader('Set-Cookie', makeSessionCookie(username, SESSION_SECRET, Date.now(), req.secure))
  res.redirect('/dashboard')
})

app.post('/logout', (req, res) => {
  res.setHeader('Set-Cookie', clearSessionCookie())
  res.redirect('/')
})

// only allow same-site relative redirects after login
function safeNext(next) {
  return typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') ? next : '/dashboard'
}

app.get('/golf-tracker.apk', guard(downloadTries, byAddress, tooMany), (req, res) => {
  if (!existsSync(APK_PATH)) return res.status(404).send('No build uploaded yet')
  // One address counts once an hour. A download manager fetching in parallel, a retried install
  // or a bot in a loop would otherwise all read as separate people.
  const since = new Date(Date.now() - 60 * 60_000).toISOString()
  const already = db.prepare('SELECT 1 FROM downloads WHERE ip_prefix = ? AND at > ?')
    .get(ipPrefix(req), since)
  if (!already) {
    db.prepare('INSERT INTO downloads (at, user_agent, ip_prefix) VALUES (?, ?, ?)')
      .run(now(), v.text(req.get('user-agent'), 200), ipPrefix(req))
  }
  res.setHeader('Content-Type', 'application/vnd.android.package-archive')
  res.setHeader('Content-Disposition', 'attachment; filename="golf-tracker.apk"')
  res.setHeader('Cache-Control', 'no-store, must-revalidate')
  createReadStream(APK_PATH).pipe(res)
})

// ---------------------------------------------------------------- ingest from the phone

app.post('/api/install', ingestGuard, requireKey(INGEST_KEY, 'x-ingest-key'), (req, res) => {
  const id = v.identifier(req.body?.installId)
  if (!id) return res.status(400).json({ error: 'installId required' })
  db.prepare(`
    INSERT INTO installs (install_id, first_seen, last_seen, app_version, device, android)
    VALUES (@id, @at, @at, @appVersion, @device, @android)
    ON CONFLICT (install_id) DO UPDATE SET
      last_seen = @at, app_version = @appVersion, device = @device, android = @android
  `).run({
    id,
    at: now(),
    appVersion: v.text(req.body?.appVersion, 32),
    device: v.text(req.body?.device, 64),
    android: v.text(req.body?.android, 32),
  })
  res.json({ ok: true })
})

app.post('/api/sessions', ingestGuard, requireKey(INGEST_KEY, 'x-ingest-key'), (req, res) => {
  const s = req.body ?? {}
  const installId = v.identifier(s.installId)
  if (!installId) return res.status(400).json({ error: 'installId required' })
  const sessionId = v.identifier(s.sessionId) ?? randomUUID()
  const startedAt = v.timestamp(s.startedAt, now())
  const shots = Array.isArray(s.shots) ? s.shots.slice(0, MAX_SHOTS) : []

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
      installId,
      startedAt,
      endedAt: v.timestamp(s.endedAt, null),
      environment: v.text(s.environment, 32),
      ball: v.text(s.ball, 32),
      timeOfDay: v.text(s.timeOfDay, 32),
      course: v.text(s.course, 120),
      coursePar: v.integer(s.coursePar, 0, 200),
      holesPlayed: v.integer(s.holesPlayed, 0, 72) ?? 0,
      throughPar: v.integer(s.throughPar, -200, 200) ?? 0,
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
    for (const shot of shots) {
      if (!shot || typeof shot !== 'object') continue
      const path = v.track(shot.track)
      insert.run({
        sessionId,
        struckAt: v.timestamp(shot.struckAt, startedAt),
        hole: v.integer(shot.hole, 1, 72),
        shotNumber: v.integer(shot.shotNumber, 1, 60),
        club: v.text(shot.club, 32),
        lie: v.text(shot.lie, 32),
        ballSpeedMs: v.number(shot.ballSpeedMs, 0, 200),
        launchDeg: v.number(shot.launchDeg, -90, 90),
        offlineDeg: v.number(shot.offlineDeg, -90, 90),
        curveDeg: v.number(shot.curveDeg, -90, 90),
        carryM: v.number(shot.carryM, 0, 1000),
        lateralM: v.number(shot.lateralM, -1000, 1000),
        apexM: v.number(shot.apexM, 0, 500),
        score: v.integer(shot.score, 0, 100),
        fromLat: v.number(shot.fromLat, -90, 90),
        fromLon: v.number(shot.fromLon, -180, 180),
        toLat: v.number(shot.toLat, -90, 90),
        toLon: v.number(shot.toLon, -180, 180),
        toGreenM: v.number(shot.toGreenM, 0, 1000),
        track: path ? JSON.stringify(path) : null,
      })
    }
  })
  save()
  res.json({ ok: true, sessionId })
})

// A phone joins an account by sending back a code from that account's dashboard. Codes are
// single-use and short-lived, and the endpoint is behind the same ingest key as the uploads.
const PAIR_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'   // no O/0, no I/1
const PAIR_TTL = 15 * 60_000

function pairCodeFor(username) {
  const since = new Date(Date.now() - PAIR_TTL).toISOString()
  const live = db.prepare(`
    SELECT code FROM pair_codes WHERE username = ? AND used_at IS NULL AND created_at > ?
    ORDER BY created_at DESC LIMIT 1
  `).get(username, since)
  if (live) return live.code
  // 256 is a whole number of alphabets, so the modulo keeps every character equally likely.
  const code = Array.from(randomBytes(6), b => PAIR_ALPHABET[b % PAIR_ALPHABET.length]).join('')
  db.prepare('INSERT INTO pair_codes (code, username, created_at) VALUES (?, ?, ?)').run(code, username, now())
  return code
}

app.post('/api/pair', requireKey(INGEST_KEY, 'x-ingest-key'), (req, res) => {
  const installId = v.identifier(req.body?.installId)
  const code = (v.text(req.body?.code, 12) ?? '').toUpperCase()
  if (!installId || !PAIR_CODE.test(code)) return res.status(400).json({ error: 'installId and code required' })

  const from = ipPrefix(req)
  const at = Date.now()
  if (!pairTries.take(from, at)) {
    res.setHeader('Retry-After', String(pairTries.retryAfter(from, at)))
    return res.status(429).json({ error: 'too many attempts' })
  }

  const claim = db.prepare(`
    SELECT username FROM pair_codes WHERE code = ? AND used_at IS NULL AND created_at > ?
  `).get(code, new Date(at - PAIR_TTL).toISOString())
  if (!claim) return res.status(404).json({ error: 'unknown or expired code' })
  pairTries.reset(from)

  db.transaction(() => {
    db.prepare('UPDATE pair_codes SET used_at = ?, used_by = ? WHERE code = ?').run(now(), installId, code)
    db.prepare(`
      INSERT INTO installs (install_id, first_seen, last_seen, owner) VALUES (@id, @at, @at, @owner)
      ON CONFLICT (install_id) DO UPDATE SET owner = @owner, last_seen = @at
    `).run({ id: installId, at: now(), owner: claim.username })
  })()
  res.json({ ok: true, account: claim.username })
})

// ---------------------------------------------------------------- reading it back

const dashboardApi = requireApi({ secret: SESSION_SECRET, dashboardKey: DASHBOARD_KEY, keyActsAs: ADMIN_USER })
const dashboardPage = requirePage(SESSION_SECRET)

// Rounds belong to the account that the phone which uploaded them is paired to. Every read below
// joins through installs so one account can never see another's.
const OWNED = 'JOIN installs i ON i.install_id = s.install_id AND i.owner = @user'

function sessionsFor(user, limit) {
  return db.prepare(`
    SELECT s.*, COUNT(sh.id) AS shots, ROUND(MAX(sh.carry_m), 1) AS longest_m
    FROM sessions s ${OWNED} LEFT JOIN shots sh ON sh.session_id = s.session_id
    GROUP BY s.session_id ORDER BY s.started_at DESC LIMIT @limit
  `).all({ user, limit })
}

function sessionFor(user, id) {
  return db.prepare(`SELECT s.* FROM sessions s ${OWNED} WHERE s.session_id = @id`).get({ user, id })
}

function phonesOf(user) {
  return db.prepare(`
    SELECT install_id, device, android, app_version, first_seen, last_seen
    FROM installs WHERE owner = ? ORDER BY last_seen DESC
  `).all(user)
}

app.get('/api/stats', dashboardApi, (req, res) => res.json(stats(req.viewer)))

app.get('/api/sessions', dashboardApi, (req, res) => res.json(sessionsFor(req.viewer, 200)))

app.get('/api/sessions/:id', dashboardApi, (req, res) => {
  const session = sessionFor(req.viewer, req.params.id)
  if (!session) return res.status(404).json({ error: 'no such session' })
  const shots = db.prepare('SELECT * FROM shots WHERE session_id = ? ORDER BY hole, shot_number').all(req.params.id)
  res.json({ session, shots: shots.map(s => ({ ...s, track: s.track ? JSON.parse(s.track) : null })) })
})

app.get('/dashboard', dashboardPage, (req, res) => {
  const user = currentUser(req, SESSION_SECRET)
  res.type('html').send(renderDashboard({
    stats: stats(user),
    sessions: sessionsFor(user, 100),
    insight: insight(user),
    phones: phonesOf(user),
    pairCode: pairCodeFor(user),
    site: user === ADMIN_USER ? siteStats() : null,
    user,
  }))
})

app.get('/dashboard/session/:id', dashboardPage, (req, res) => {
  const user = currentUser(req, SESSION_SECRET)
  const session = sessionFor(user, req.params.id)
  if (!session) return res.status(404).send('No such session')
  const shots = db.prepare('SELECT * FROM shots WHERE session_id = ? ORDER BY hole, shot_number').all(req.params.id)
  res.type('html').send(renderSession(session, shots))
})

/** The player's own golf, which is all the dashboard tiles show. */
function stats(user) {
  const own = `FROM sessions s ${OWNED}`
  const ownShots = `FROM shots sh JOIN sessions s ON s.session_id = sh.session_id ${OWNED}`
  const one = sql => db.prepare(sql).get({ user })
  return {
    sessions: one(`SELECT COUNT(*) AS n ${own}`).n,
    shots: one(`SELECT COUNT(*) AS n ${ownShots}`).n,
    longestM: one(`SELECT ROUND(MAX(sh.carry_m), 1) AS n ${ownShots}`).n,
    fastestMs: one(`SELECT ROUND(MAX(sh.ball_speed_ms), 1) AS n ${ownShots}`).n,
    bestScore: one(`SELECT MAX(sh.score) AS n ${ownShots}`).n,
    avgScore: one(`SELECT ROUND(AVG(sh.score)) AS n ${ownShots}`).n,
    holes: one(`SELECT COALESCE(SUM(s.holes_played), 0) AS n ${own}`).n,
  }
}

/** Downloads and installs are about the site, not about anyone's golf — the admin's row only. */
function siteStats() {
  const one = sql => db.prepare(sql).get()
  const downloads = one('SELECT COUNT(*) AS n FROM downloads').n
  const installs = one('SELECT COUNT(*) AS n FROM installs').n
  return {
    downloads,
    installs,
    installedOfDownloads: downloads ? Math.round((installs / downloads) * 100) : null,
  }
}

/** What the side panel reads: how this account's strike score has moved, and what its shots share. */
function insight(user) {
  const ownShots = `FROM shots sh JOIN sessions s ON s.session_id = sh.session_id ${OWNED}`
  const recent = db.prepare(`
    SELECT sh.score AS score, sh.struck_at AS struck_at ${ownShots} AND sh.score IS NOT NULL
    ORDER BY sh.struck_at DESC LIMIT 90
  `).all({ user }).reverse()
  const scores = recent.map(r => r.score)
  const mean = xs => xs.reduce((a, b) => a + b, 0) / xs.length
  const third = Math.floor(scores.length / 3)

  const top = db.prepare(`
    SELECT sh.club AS club, COUNT(*) AS n ${ownShots} AND sh.club IS NOT NULL AND sh.club <> ''
    GROUP BY sh.club ORDER BY n DESC LIMIT 1
  `).get({ user })
  const clubbed = db.prepare(`
    SELECT COUNT(*) AS n ${ownShots} AND sh.club IS NOT NULL AND sh.club <> ''
  `).get({ user }).n
  const off = db.prepare(`
    SELECT AVG(sh.offline_deg) AS a, COUNT(*) AS n ${ownShots} AND sh.offline_deg IS NOT NULL
  `).get({ user })

  return {
    scores,
    from: recent.length ? monthOf(recent[0].struck_at, 'short') : null,
    to: recent.length ? monthOf(recent[recent.length - 1].struck_at, 'short') : null,
    since: recent.length ? monthOf(recent[0].struck_at, 'long') : null,
    delta: third >= 3 ? Math.round(mean(scores.slice(-third)) - mean(scores.slice(0, third))) : null,
    club: top ? `${prettyClub(top.club)} · ${Math.round((top.n / clubbed) * 100)}%` : null,
    miss: off.n ? `${Math.abs(off.a).toFixed(1)}\u00b0 ${off.a < 0 ? 'left' : 'right'}` : null,
  }
}

function monthOf(iso, style) {
  const d = new Date(iso)
  const name = Number.isNaN(d.getTime()) ? null : d.toLocaleString('en', { month: style })
  return style === 'short' ? name?.toUpperCase() ?? null : name
}

app.listen(PORT, BIND, () => console.log(`golf tracker server on ${BIND}:${PORT}`))
