import { statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const PUBLIC_DIR = dirname(fileURLToPath(import.meta.url)) + '/public'
let _assetV = null
/** A stamp that changes whenever a static asset changes, so cached copies are busted on deploy. */
function assetV() {
  if (_assetV) return _assetV
  try {
    const m = ['scene.js', 'site.css', 'three.min.js'].map(f => statSync(`${PUBLIC_DIR}/${f}`).mtimeMs)
    _assetV = Math.max(...m).toString(36).slice(-7)
  } catch { _assetV = '1' }
  return _assetV
}

const CSS = `
:root{color-scheme:dark}
*{box-sizing:border-box}
body{background:#0b0f0c;color:#e8ffe9;font:15px/1.55 system-ui,-apple-system,Segoe UI,sans-serif;margin:0;padding:32px 20px}
main{max-width:70rem;margin:0 auto}
a{color:#e8ff00}
h1{font-size:1.5rem;margin:0 0 4px}
h2{font-size:1.05rem;margin:34px 0 12px;color:#c8ffcb}
.sub{color:#8ea394;margin:0 0 26px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(9.5rem,1fr));gap:12px}
.tile{background:#121a14;border:1px solid #1f2b22;border-radius:14px;padding:14px 16px}
.tile .n{font-size:1.7rem;font-weight:700;color:#e8ff00;line-height:1.2}
.tile .l{color:#8ea394;font-size:.82rem;text-transform:uppercase;letter-spacing:.05em}
table{width:100%;border-collapse:collapse;font-size:.92rem}
th{text-align:left;color:#8ea394;font-weight:600;border-bottom:1px solid #1f2b22;padding:8px 10px}
td{border-bottom:1px solid #151f18;padding:8px 10px}
tr:hover td{background:#101710}
.wrap{overflow-x:auto}
.empty{color:#8ea394;background:#121a14;border:1px dashed #24312a;border-radius:14px;padding:26px;text-align:center}
figure{margin:0 0 18px;background:#121a14;border:1px solid #1f2b22;border-radius:14px;padding:14px}
figcaption{color:#8ea394;font-size:.85rem;margin-bottom:8px}
.plus{color:#ff9f6b}.minus{color:#7ee787}.level{color:#8ea394}
footer{color:#63745f;font-size:.8rem;margin-top:40px}
`

const escape = s => String(s ?? '').replace(/[<>&"]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]))
const page = (title, body) =>
  `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(title)}</title><style>${CSS}</style><main>${body}
<footer>Course data © OpenStreetMap contributors, ODbL. Landing points are modelled from the
measured launch, not observed.</footer></main>`

const q = key => (key ? `?key=${encodeURIComponent(key)}` : '')
const tile = (n, l) => `<div class="tile"><div class="n">${n ?? '—'}</div><div class="l">${l}</div></div>`

function parClass(v) {
  if (v === null || v === undefined) return ['level', '—']
  if (v > 0) return ['plus', `+${v}`]
  if (v < 0) return ['minus', `${v}`]
  return ['level', 'level']
}

export function renderDashboard(stats, sessions, key) {
  const rows = sessions.map(s => {
    const [cls, label] = parClass(s.holes_played ? s.through_par : null)
    return `<tr>
      <td><a href="/dashboard/session/${encodeURIComponent(s.session_id)}${q(key)}">${escape(s.started_at.slice(0, 16).replace('T', ' '))}</a></td>
      <td>${escape(s.course ?? 'Practice')}</td>
      <td>${escape(s.environment ?? '')} · ${escape(s.ball ?? '')} · ${escape(s.time_of_day ?? '')}</td>
      <td>${s.shots}</td>
      <td>${s.longest_m ? `${Math.round(s.longest_m)} m` : '—'}</td>
      <td>${s.holes_played || 0}</td>
      <td class="${cls}">${label}</td>
    </tr>`
  }).join('')

  return page('ShotArc — dashboard', `
    <div style="display:flex;justify-content:space-between;align-items:baseline;gap:12px;flex-wrap:wrap">
      <h1>ShotArc</h1>
      <form method="post" action="/logout" style="margin:0"><button
        style="background:none;border:1px solid #24312a;color:#8ea394;border-radius:9px;padding:6px 12px;cursor:pointer">Log out</button></form>
    </div>
    <p class="sub">Installs, rounds and every shot the camera registered.</p>
    <div class="tiles">
      ${tile(stats.downloads, 'APK downloads')}
      ${tile(stats.installs, 'Installs seen')}
      ${tile(stats.installedOfDownloads === null ? '—' : stats.installedOfDownloads + '%', 'Downloads that ran')}
      ${tile(stats.sessions, 'Sessions')}
      ${tile(stats.shots, 'Shots tracked')}
      ${tile(stats.longestM ? Math.round(stats.longestM) + ' m' : '—', 'Longest carry')}
      ${tile(stats.fastestMs ? Math.round(stats.fastestMs * 2.2369) + ' mph' : '—', 'Fastest ball')}
    </div>
    <h2>Sessions</h2>
    ${sessions.length ? `<div class="wrap"><table>
      <tr><th>When</th><th>Course</th><th>Setup</th><th>Shots</th><th>Longest</th><th>Holes</th><th>vs par</th></tr>
      ${rows}</table></div>` : '<p class="empty">No sessions uploaded yet.</p>'}`)
}

/** Plan view: every shot of the round, tee to landing, in metres from the first tee. */
function planView(shots) {
  const placed = shots.filter(s => s.from_lat !== null && s.to_lat !== null)
  if (!placed.length) return ''
  const originLat = placed[0].from_lat
  const originLon = placed[0].from_lon
  const mPerDegLat = 111320
  const mPerDegLon = 111320 * Math.cos((originLat * Math.PI) / 180)
  const project = (lat, lon) => [(lon - originLon) * mPerDegLon, (lat - originLat) * mPerDegLat]

  const points = placed.flatMap(s => [project(s.from_lat, s.from_lon), project(s.to_lat, s.to_lon)])
  const xs = points.map(p => p[0])
  const ys = points.map(p => p[1])
  const pad = 30
  const minX = Math.min(...xs) - pad, maxX = Math.max(...xs) + pad
  const minY = Math.min(...ys) - pad, maxY = Math.max(...ys) + pad
  const w = 900, h = 420
  const scale = Math.min(w / (maxX - minX), h / (maxY - minY))
  const sx = x => (x - minX) * scale
  const sy = y => h - (y - minY) * scale     // north up

  const lines = placed.map((s, i) => {
    const [x1, y1] = project(s.from_lat, s.from_lon)
    const [x2, y2] = project(s.to_lat, s.to_lon)
    return `<line x1="${sx(x1).toFixed(1)}" y1="${sy(y1).toFixed(1)}" x2="${sx(x2).toFixed(1)}" y2="${sy(y2).toFixed(1)}"
      stroke="#e8ff00" stroke-width="2" stroke-linecap="round" opacity="0.85"/>
      <circle cx="${sx(x2).toFixed(1)}" cy="${sy(y2).toFixed(1)}" r="4" fill="#e8ff00"/>
      <text x="${(sx(x2) + 7).toFixed(1)}" y="${(sy(y2) - 6).toFixed(1)}" fill="#8ea394" font-size="11">${s.hole ?? ''}.${s.shot_number ?? i + 1}</text>`
  }).join('')

  const tees = placed.filter(s => s.shot_number === 1).map(s => {
    const [x, y] = project(s.from_lat, s.from_lon)
    return `<rect x="${(sx(x) - 4).toFixed(1)}" y="${(sy(y) - 4).toFixed(1)}" width="8" height="8" fill="#7ee787"/>`
  }).join('')

  return `<figure><figcaption>Shot paths, north up — squares are tees, dots are where each shot came down</figcaption>
    <svg viewBox="0 0 ${w} ${h}" width="100%" role="img">${lines}${tees}</svg></figure>`
}

/** Side view: the modelled flight of each shot, height against distance. */
function trajectoryView(shots) {
  const flown = shots.filter(s => s.carry_m > 0)
  if (!flown.length) return ''
  const w = 900, h = 260, padL = 40, padB = 26
  const maxX = Math.max(...flown.map(s => s.carry_m)) * 1.05
  const maxY = Math.max(...flown.map(s => s.apex_m ?? 0), 10) * 1.25
  const sx = d => padL + (d / maxX) * (w - padL - 12)
  const sy = m => h - padB - (m / maxY) * (h - padB - 14)

  const curves = flown.map(s => {
    const track = s.track
    let d
    if (Array.isArray(track) && track.length > 1) {
      d = track.map((p, i) => `${i ? 'L' : 'M'}${sx(p[0]).toFixed(1)},${sy(p[1]).toFixed(1)}`).join('')
    } else {
      const apex = s.apex_m ?? s.carry_m / 8
      d = `M${sx(0)},${sy(0)} Q${sx(s.carry_m * 0.55).toFixed(1)},${sy(apex * 1.85).toFixed(1)} ${sx(s.carry_m).toFixed(1)},${sy(0)}`
    }
    return `<path d="${d}" fill="none" stroke="#e8ff00" stroke-width="1.8" opacity="0.75"/>`
  }).join('')

  const grid = [0, 0.25, 0.5, 0.75, 1].map(f => {
    const metres = Math.round(maxX * f)
    return `<line x1="${sx(metres)}" y1="${h - padB}" x2="${sx(metres)}" y2="14" stroke="#1f2b22"/>
      <text x="${sx(metres)}" y="${h - 8}" fill="#63745f" font-size="11" text-anchor="middle">${metres} m</text>`
  }).join('')

  return `<figure><figcaption>Flight profile — height against distance, ${flown.length} shots</figcaption>
    <svg viewBox="0 0 ${w} ${h}" width="100%" role="img">
      ${grid}
      <line x1="${padL}" y1="${h - padB}" x2="${w - 12}" y2="${h - padB}" stroke="#2c3b30"/>
      <text x="6" y="20" fill="#63745f" font-size="11">${Math.round(maxY)} m</text>
      ${curves}</svg></figure>`
}

export function renderSession(session, shots, key) {
  const parsed = shots.map(s => ({ ...s, track: s.track ? JSON.parse(s.track) : null }))
  const longest = parsed.reduce((best, s) => (s.carry_m > (best?.carry_m ?? 0) ? s : best), null)
  const fastest = parsed.reduce((best, s) => (s.ball_speed_ms > (best?.ball_speed_ms ?? 0) ? s : best), null)
  const [cls, label] = parClass(session.holes_played ? session.through_par : null)

  const rows = parsed.map(s => `<tr>
      <td>${s.hole ?? '—'}.${s.shot_number ?? ''}</td>
      <td>${escape(s.club ?? '')}</td>
      <td>${escape(s.lie ?? '')}</td>
      <td>${s.ball_speed_ms ? Math.round(s.ball_speed_ms * 2.2369) + ' mph' : '—'}</td>
      <td>${s.launch_deg?.toFixed(1) ?? '—'}°</td>
      <td>${s.offline_deg?.toFixed(1) ?? '—'}°</td>
      <td>${s.carry_m ? Math.round(s.carry_m) + ' m' : '—'}</td>
      <td>${s.apex_m ? Math.round(s.apex_m) + ' m' : '—'}</td>
      <td>${s.to_green_m ? Math.round(s.to_green_m) + ' m' : '—'}</td>
      <td>${s.score ?? '—'}</td>
    </tr>`).join('')

  return page(`Session — ${session.course ?? 'Practice'}`, `
    <h1>${escape(session.course ?? 'Practice session')}</h1>
    <p class="sub">${escape(session.started_at.slice(0, 16).replace('T', ' '))} ·
      ${escape(session.environment ?? '')} · ${escape(session.ball ?? '')} ball ·
      ${escape(session.time_of_day ?? '')} · <a href="/dashboard${q(key)}">all sessions</a></p>
    <div class="tiles">
      ${tile(parsed.length, 'Shots this session')}
      ${tile(longest?.carry_m ? Math.round(longest.carry_m) + ' m' : '—', 'Longest shot')}
      ${tile(fastest?.ball_speed_ms ? Math.round(fastest.ball_speed_ms * 2.2369) + ' mph' : '—', 'Fastest ball')}
      ${tile(session.holes_played || 0, 'Holes finished')}
      ${tile(`<span class="${cls}">${label}</span>`, 'Against par')}
      ${tile(session.course_par ?? '—', 'Course par')}
    </div>
    <h2>The round</h2>
    ${planView(parsed)}
    ${trajectoryView(parsed)}
    <h2>Every shot</h2>
    ${parsed.length ? `<div class="wrap"><table>
      <tr><th>Shot</th><th>Club</th><th>Lie</th><th>Ball speed</th><th>Launch</th><th>Offline</th>
          <th>Carry</th><th>Apex</th><th>To green</th><th>Score</th></tr>
      ${rows}</table></div>` : '<p class="empty">No shots in this session.</p>'}`)
}


/** Marketing landing at /, with the 3D shot preview, a live dashboard preview, and the CTAs. */
export function renderLanding(req, apk, user) {
  const size = apk ? `${(apk.size / 1048576).toFixed(1)} MB` : 'coming soon'
  const dashLink = user ? '/dashboard' : '/login?next=/dashboard'
  const dashLabel = user ? 'Open dashboard' : 'Log in'

  const sample = sampleSession()
  const preview = `
    <div class="tiles">
      ${tile('152 mph', 'Ball speed')}
      ${tile('258 m', 'Longest carry')}
      ${tile('87', 'Best shot')}
      ${tile('+3', 'vs par')}
    </div>
    <div class="charts">
      <figure class="chart">${planView(sample)}</figure>
      <figure class="chart">${trajectoryView(sample)}</figure>
    </div>`

  return `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShotArc — track every shot</title>
<meta name="description" content="Track your golf ball from behind, see its flight, speed, launch and score — and your whole round on the dashboard.">
<link rel="stylesheet" href="/assets/site.css?v=${assetV()}">
</head><body>
<header class="hero">
  <canvas id="scene"></canvas>
  <div class="hero-grad"></div>
  <div class="hud"><div class="wrap"><div class="hud-card" id="hud">
    <div class="hud-status" id="hud-status">Ball locked — hit it</div>
    <div class="hud-row"><span class="k">Ball speed</span><span class="v" id="hud-speed">—</span></div>
    <div class="hud-row"><span class="k">Launch</span><span class="v" id="hud-launch">—</span></div>
    <div class="hud-row"><span class="k">Carry</span><span class="v" id="hud-carry">—</span></div>
    <div class="hud-score" id="hud-score"></div>
  </div></div></div>
  <div class="brand"><div class="wrap">
    <b>ShotArc</b>
    <nav><a href="#how">How it works</a><a href="#dashboard">Dashboard</a><a href="${dashLink}">${dashLabel}</a></nav>
  </div></div>
  <div class="hero-content"><div class="wrap">
    <h1>See every shot <span class="y">fly.</span></h1>
    <p class="lede">Stand your phone behind the ball. ShotArc tracks it off the face, draws the
      arc, and tells you the speed, launch, carry and a score for the strike — indoors or out.</p>
    <div class="cta">
      <a class="btn" href="/install">↓ Download the app<span class="meta" style="color:#0c1005cc">&nbsp;· ${size}</span></a>
      <a class="btn ghost" href="${dashLink}">${dashLabel}</a>
    </div>
    <p class="meta" style="margin-top:12px">Android · sideload · ${apk ? 'free' : ''}</p>
  </div></div>
</header>

<section id="how"><div class="wrap">
  <h2>Three things, no gadgets</h2>
  <p class="sub">No launch monitor, no sensors on the club. Just the camera you already carry.</p>
  <div class="steps">
    <div class="step"><div class="n">1</div><h3>Set up behind</h3>
      <p>Prop the phone on the ground or a tripod, a couple of metres behind the ball, down the target line.</p></div>
    <div class="step"><div class="n">2</div><h3>Swing</h3>
      <p>ShotArc locks onto the ball on the grass, then follows it into flight and traces the arc live.</p></div>
    <div class="step"><div class="n">3</div><h3>Read the shot</h3>
      <p>Ball speed, launch angle, start line, carry and a score — plus, on a course, where it lands and your tally against par.</p></div>
  </div>
</div></section>

<section id="dashboard" class="dash"><div class="wrap">
  <h2>Your whole round, afterwards</h2>
  <p class="sub">Every session syncs to your dashboard: shot paths down each hole, flight profiles,
    longest drive, fastest ball and your score. Log in to see yours.</p>
  <div class="frame">
    <div class="frame-bar"><span class="dot"></span><span class="dot"></span><span class="dot"></span>
      <span class="url">shotarc.co.za/dashboard</span></div>
    <div class="frame-body">${preview}</div>
  </div>
  <div class="dash-cta">
    <a class="btn" href="${dashLink}">${dashLabel}</a>
    <a class="btn ghost" href="/install">↓ Download the app</a>
  </div>
</div></section>

<section class="install"><div class="wrap">
  <h2>Installing it</h2>
  <ol>
    <li>Tap <b>Download the app</b>. Chrome warns about any APK that is not from Play — that is expected.</li>
    <li>Open the downloaded file. Android asks to allow installs from your browser this once; turn it on and continue.</li>
    <li>Allow the camera when ShotArc first asks. That is all it needs.</li>
  </ol>
</div></section>

<footer><div class="wrap">
  © ShotArc · Course data © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>, ODbL.
  Flight and landing are modelled from the measured launch.
</div></footer>

<script src="/assets/three.min.js?v=${assetV()}"></script>
<script src="/assets/scene.js?v=${assetV()}"></script>
</body></html>`
}

export function renderLogin({ next, error }) {
  const nextField = next ? `<input type="hidden" name="next" value="${escape(next)}">` : ''
  return `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShotArc — log in</title><link rel="stylesheet" href="/assets/site.css?v=${assetV()}">
<style>
 body{display:flex;min-height:100svh;align-items:center;justify-content:center;padding:24px}
 .card{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:30px;width:min(24rem,100%)}
 .card b{color:var(--accent);font-size:1.1rem}
 label{display:block;color:var(--muted);font-size:.85rem;margin:16px 0 6px}
 input{width:100%;background:#0e150f;border:1px solid var(--line);border-radius:10px;
   padding:12px 14px;color:var(--ink);font-size:1rem}
 button{width:100%;margin-top:20px;background:var(--accent);color:#0c1005;font-weight:700;
   border:0;border-radius:11px;padding:13px;font-size:1rem;cursor:pointer}
 .err{color:var(--warn);font-size:.9rem;margin-top:14px}
 .back{display:block;text-align:center;margin-top:16px;color:#8ea394;font-size:.9rem}
</style></head><body>
<form class="card" method="post" action="/login">
  <b>ShotArc</b>
  <p style="color:var(--muted);margin:.4rem 0 0">Log in to your dashboard.</p>
  ${nextField}
  <label>Username</label><input name="username" autocomplete="username" autofocus>
  <label>Password</label><input name="password" type="password" autocomplete="current-password">
  ${error ? `<div class="err">${escape(error)}</div>` : ''}
  <button type="submit">Log in</button>
  <a class="back" href="/">← Back to shotarc.co.za</a>
</form></body></html>`
}

/** A believable six-hole sample for the landing's dashboard preview (not real player data). */
function sampleSession() {
  const base = { from_lat: -26.05, from_lon: 28.03 }
  const shots = []
  const holes = [
    [[64, 12.8, 2.1, 'DRIVER', 221, 31], [49, 19.4, -1.2, 'MID_IRON', 149, 27]],
    [[62, 14.1, -5.4, 'DRIVER', 205, 29], [44, 22, 1.1, 'SHORT_IRON', 121, 25], [31, 29, 0.4, 'WEDGE', 88, 19]],
    [[46, 23.8, 0.8, 'SHORT_IRON', 131, 26]],
    [[66, 11.9, 1.0, 'DRIVER', 238, 30], [47, 20.5, -2.0, 'MID_IRON', 141, 26]],
    [[59, 15.2, 7.8, 'DRIVER', 198, 28], [52, 17, -3.1, 'LONG_IRON', 165, 27]],
    [[67, 12.2, -0.6, 'DRIVER', 249, 31], [62, 11.4, 2.4, 'FAIRWAY_WOOD', 210, 24], [34, 30.5, -0.9, 'WEDGE', 90, 20]],
  ]
  let lat = base.from_lat, lon = base.from_lon
  holes.forEach((hole, h) => {
    let tlat = lat, tlon = lon
    hole.forEach(([speed, launch, offline, club, carry, apex], i) => {
      const toLat = tlat + carry / 111320 * 0.9
      const toLon = tlon + (offline * carry / 60) / (111320 * Math.cos(tlat * Math.PI / 180))
      shots.push({
        hole: h + 1, shot_number: i + 1, club,
        ball_speed_ms: speed, launch_deg: launch, offline_deg: offline,
        carry_m: carry, apex_m: apex, score: Math.max(40, Math.round(95 - Math.abs(offline) * 5)),
        from_lat: tlat, from_lon: tlon, to_lat: toLat, to_lon: toLon, to_green_m: 0,
        track: Array.from({ length: 9 }, (_, k) => { const f = k / 8; return [Math.round(carry * f), Math.round(apex * 4 * f * (1 - f)) / 1] }),
      })
      tlat = toLat; tlon = toLon
    })
    lat += 0.004; lon += 0.0025
  })
  return shots
}


/** The get-the-app page the download buttons point to — always a visible navigation. */
export function renderInstall(apk) {
  const size = apk ? `${(apk.size / 1048576).toFixed(1)} MB` : 'coming soon'
  return `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShotArc — get the app</title><link rel="stylesheet" href="/assets/site.css?v=${assetV()}">
<style>
 body{display:flex;min-height:100svh;align-items:center;justify-content:center;padding:28px}
 .card{background:var(--panel);border:1px solid var(--line);border-radius:20px;padding:34px;width:min(30rem,100%)}
 .card b{color:var(--accent);font-size:1.15rem}
 h1{font-size:1.6rem;margin:.4rem 0 .2rem}
 .dl{display:block;text-align:center;background:var(--accent);color:#0c1005;font-weight:800;
   font-size:1.1rem;padding:16px;border-radius:13px;margin:20px 0 6px}
 .meta{color:var(--muted);font-size:.85rem;text-align:center}
 ol{color:var(--muted);padding-left:20px;margin-top:22px}
 li{margin:.55rem 0}
 .note{background:#0e150f;border:1px solid var(--line);border-radius:11px;padding:12px 14px;
   color:#cfe3d4;font-size:.9rem;margin-top:18px}
 .back{display:block;text-align:center;margin-top:20px;color:#8ea394;font-size:.9rem}
</style></head><body>
<main class="card">
  <b>ShotArc</b>
  <h1>Get the app</h1>
  <p class="meta" style="text-align:left">Android · ${size}</p>
  <a class="dl" id="dl" href="/golf-tracker.apk" download="shotarc.apk">↓ Download the APK</a>
  <p class="meta">If the download does not start, <a href="/golf-tracker.apk" download="shotarc.apk">tap here</a>.</p>
  <ol>
    <li>When it finishes, open the downloaded file.</li>
    <li>Android asks to allow installs from your browser this once — turn it on and continue.</li>
    <li>Allow the camera when ShotArc first asks. That is all it needs.</li>
  </ol>
  <div class="note">Chrome warns about any APK that is not from the Play Store — that warning is
    expected here. Open it on an Android phone; a laptop cannot install it.</div>
  <a class="back" href="/">← Back to shotarc.co.za</a>
</main>
<script>
  // nudge the download and give visible feedback even where the browser UI is quiet
  var dl = document.getElementById('dl');
  dl.addEventListener('click', function(){
    var t = dl.textContent; dl.textContent = 'Starting download…';
    setTimeout(function(){ dl.textContent = t; }, 2500);
  });
</script>
</body></html>`
}
