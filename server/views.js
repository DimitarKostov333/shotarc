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
const ptile = (n, l) => `<div class="ptile"><div class="n">${n}</div><div class="l">${l}</div></div>`
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
      ${tile(stats.fastestMs ? Math.round(stats.fastestMs * 3.6) + ' km/h' : '—', 'Fastest ball')}
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
      <td>${s.ball_speed_ms ? Math.round(s.ball_speed_ms * 3.6) + ' km/h' : '—'}</td>
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
      ${tile(fastest?.ball_speed_ms ? Math.round(fastest.ball_speed_ms * 3.6) + ' km/h' : '—', 'Fastest ball')}
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
  const size = apk ? `${(apk.size / 1048576).toFixed(1)} MB` : ''
  const dashLink = user ? '/dashboard' : '/login?next=/dashboard'
  const dashLabel = user ? 'Enter the dashboard' : 'Members'
  const sample = sampleSession()

  const preview = `
    <div class="ptiles">
      ${ptile('245', 'km/h ball speed')}
      ${ptile('258 m', 'longest carry')}
      ${ptile('87', 'best strike')}
      ${ptile('+3', 'through 6')}
    </div>
    <div class="pcharts">
      <figure class="pchart">${planView(sample)}</figure>
      <figure class="pchart">${trajectoryView(sample)}</figure>
    </div>`

  return `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShotArc — read the flight of every shot</title>
<meta name="description" content="Stand your phone behind the ball. ShotArc traces the flight and gives you ball speed, launch, carry and a score — then your whole round on the dashboard.">
<link rel="icon" type="image/svg+xml" href="/assets/mark.svg?v=${assetV()}">
<link rel="stylesheet" href="/assets/site.css?v=${assetV()}">
</head><body>

<header class="hero">
  <canvas id="scene"></canvas>
  <div class="hero-veil"></div>

  <div class="top"><div class="wrap">
    <a class="mark" href="/"><img class="mark-ico" src="/assets/mark.svg?v=${assetV()}" alt="" width="30" height="30">ShotArc<span class="dot">.</span></a>
    <button class="menu-btn" id="menuBtn" aria-label="Menu" aria-expanded="false"><span></span><span></span><span></span></button>
    <nav class="nav" id="nav">
      <a href="#craft">The App</a>
      <a href="#dashboard">The Dashboard</a>
      <a class="members" href="${dashLink}">${user ? 'Dashboard' : 'Members'}</a>
    </nav>
  </div></div>

  <div class="hud"><div class="wrap"><div class="hud-card" id="hud">
    <div class="hud-status" id="hud-status">Ball locked</div>
    <div class="hud-row"><span class="k">Ball speed</span><span class="v" id="hud-speed">—</span></div>
    <div class="hud-row"><span class="k">Launch</span><span class="v" id="hud-launch">—</span></div>
    <div class="hud-row"><span class="k">Carry</span><span class="v" id="hud-carry">—</span></div>
    <div class="hud-score" id="hud-score"></div>
  </div></div></div>

  <div class="hero-body"><div class="wrap">
    <h1>Read the flight of <em>every</em> shot.</h1>
    <p class="lead">Stand your phone behind the ball. ShotArc follows it off the face, traces the
      line, and reads back ball speed, launch, carry and the quality of the strike.</p>
    <div class="hero-cta">
      <a class="btn on-dark" href="/install">Download the app${size ? ` · ${size}` : ''}</a>
      <a class="tlink on-dark" href="${dashLink}">${user ? 'Enter the dashboard' : 'Members entrance'} <span class="arrow">→</span></a>
    </div>
  </div></div>
</header>

<section class="statement"><div class="wrap">
  <p class="eyebrow">No gadgets</p>
  <h2>No launch monitor. No sensors on the club. <em>Just the camera in your bag.</em></h2>
  <p class="lead">The measurement most players never had access to, from the phone already in your pocket —
    on the range at dawn, in the bay in winter, or standing on your own back garden.</p>
</div></section>

<section id="craft" style="background:var(--paper-2)"><div class="wrap">
  <p class="eyebrow">How it plays</p>
  <h2>Three unhurried steps.</h2>
  <div class="steps">
    <div class="step"><div class="num">01</div><div class="body">
      <h3>Set the phone behind</h3>
      <p>On the ground or a tripod, a couple of paces behind the ball and down the target line. Nothing to pair, nothing to charge.</p>
    </div></div>
    <div class="step"><div class="num">02</div><div class="body">
      <h3>Play your shot</h3>
      <p>ShotArc finds the ball at rest on the grass, then follows it into flight and draws the arc as it climbs — indoors into a net, or out to the horizon.</p>
    </div></div>
    <div class="step"><div class="num">03</div><div class="body">
      <h3>Read the number</h3>
      <p>Ball speed, launch, start line, carry and a score for the strike. On a course, where it comes to rest and your standing against par.</p>
    </div></div>
  </div>
  <div class="measures">
    <span>Ball speed</span><span>Launch angle</span><span>Start line</span><span>Carry</span><span>Shot score</span>
  </div>
</div></section>

<section id="dashboard" class="dash"><div class="wrap">
  <p class="eyebrow">Afterwards</p>
  <h2>Every round, kept.</h2>
  <p class="lead measure">Each session syncs to a private dashboard: the shape of every shot down each
    hole, its flight in profile, your longest of the day and your score. Yours to revisit.</p>
  <div class="plate">
    <div class="plate-bar"><i></i><i></i><i></i><span class="u">shotarc.co.za / dashboard</span></div>
    <div class="plate-body">${preview}</div>
  </div>
  <p class="caption">A sample round. Your dashboard opens with the Members link.</p>
</div></section>

<section class="closing"><div class="wrap">
  <p class="eyebrow" style="color:var(--brass-lite)">Begin</p>
  <h2>Take the measure of your game.</h2>
  <p class="lead">Free to use. Android, sideloaded from here. The camera is all it needs.</p>
  <div class="hero-cta">
    <a class="btn on-dark" href="/install">Download the app${size ? ` · ${size}` : ''}</a>
    <a class="tlink on-dark" href="${dashLink}">${user ? 'Enter the dashboard' : 'Members entrance'} <span class="arrow">→</span></a>
  </div>
</div></section>

<footer class="foot"><div class="wrap">
  <a class="mark" href="/" style="color:var(--cream)"><img class="mark-ico" src="/assets/mark.svg?v=${assetV()}" alt="" width="26" height="26">ShotArc<span style="color:var(--brass-lite)">.</span></a>
  <p class="fine">Course data © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>, ODbL.
    Flight and landing are modelled from the measured launch, not observed after it leaves frame.</p>
</div></footer>

<script>
(function(){var b=document.getElementById('menuBtn'),n=document.getElementById('nav');if(!b||!n)return;
 b.addEventListener('click',function(){var o=n.classList.toggle('open');b.classList.toggle('open',o);b.setAttribute('aria-expanded',o);});
 n.querySelectorAll('a').forEach(function(a){a.addEventListener('click',function(){n.classList.remove('open');b.classList.remove('open');b.setAttribute('aria-expanded','false');});});
})();
</script>
<script src="/assets/three.min.js?v=${assetV()}"></script>
<script src="/assets/scene.js?v=${assetV()}"></script>
</body></html>`
}

export function renderLogin({ next, error }) {
  const nextField = next ? `<input type="hidden" name="next" value="${escape(next)}">` : ''
  return `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShotArc — members</title><link rel="icon" type="image/svg+xml" href="/assets/mark.svg?v=${assetV()}">
<link rel="stylesheet" href="/assets/site.css?v=${assetV()}">
<style>
 body{background:var(--pine);color:var(--cream);display:flex;min-height:100svh;align-items:center;justify-content:center;padding:24px}
 .card{width:min(25rem,100%)}
 .card .eyebrow{color:var(--brass-lite)}
 .card h1{color:var(--cream);font-size:2.4rem;margin:0 0 6px}
 .card p.sub{color:#c3ccbe;margin:0 0 26px}
 label{display:block;color:var(--cream-soft);font-size:.72rem;letter-spacing:.16em;text-transform:uppercase;margin:18px 0 8px}
 input{width:100%;background:rgba(236,230,214,.06);border:1px solid var(--line-cream);border-radius:3px;
   padding:13px 15px;color:var(--cream);font-size:1rem;font-family:var(--sans)}
 input:focus{outline:none;border-color:var(--brass-lite)}
 .btn{width:100%;justify-content:center;margin-top:26px}
 .err{color:#e2a08f;font-size:.9rem;margin-top:16px}
 .back{display:block;text-align:center;margin-top:20px;color:var(--cream-soft);font-size:.85rem}
 .back:hover{color:var(--cream)}
</style></head><body>
<form class="card" method="post" action="/login">
  <p class="eyebrow">ShotArc</p>
  <h1 class="serif">Members</h1>
  <p class="sub">Sign in to your dashboard.</p>
  ${nextField}
  <label>Username</label><input name="username" autocomplete="username" autofocus>
  <label>Password</label><input name="password" type="password" autocomplete="current-password">
  ${error ? `<div class="err">${escape(error)}</div>` : ''}
  <button class="btn on-dark" type="submit">Sign in</button>
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
<title>ShotArc — get the app</title><link rel="icon" type="image/svg+xml" href="/assets/mark.svg?v=${assetV()}">
<link rel="stylesheet" href="/assets/site.css?v=${assetV()}">
<style>
 body{background:var(--pine);color:var(--cream);display:flex;min-height:100svh;align-items:center;justify-content:center;padding:28px}
 .card{width:min(31rem,100%)}
 .card .eyebrow{color:var(--brass-lite)}
 .card h1{color:var(--cream);font-size:2.6rem;margin:0 0 6px}
 .dl{display:flex;justify-content:center;margin:26px 0 8px;width:100%}
 .dl .btn{width:100%;justify-content:center;font-size:1.05rem;padding:17px}
 .hint{color:var(--cream-soft);font-size:.86rem;text-align:center}
 .hint a{color:var(--brass-lite);border-bottom:1px solid var(--line-cream)}
 ol{color:#c3ccbe;padding-left:20px;margin-top:26px;line-height:1.7}
 ol b{color:var(--cream)}
 .note{border-left:2px solid var(--brass);padding:12px 16px;color:#c3ccbe;font-size:.9rem;margin-top:22px;background:rgba(236,230,214,.04)}
 .back{display:block;text-align:center;margin-top:22px;color:var(--cream-soft);font-size:.85rem}
 .back:hover{color:var(--cream)}
</style></head><body>
<main class="card">
  <p class="eyebrow">Android · ${size}</p>
  <h1 class="serif">Get the app</h1>
  <div class="dl"><a class="btn on-dark" id="dl" href="/golf-tracker.apk" download="shotarc.apk">Download the APK</a></div>
  <p class="hint">If it does not begin, <a href="/golf-tracker.apk" download="shotarc.apk">tap here</a>.</p>
  <ol>
    <li>When it finishes, <b>open the downloaded file</b>.</li>
    <li>Android asks to allow installs from your browser this once — turn it on and continue.</li>
    <li>Allow the camera when ShotArc first asks. That is all it needs.</li>
  </ol>
  <div class="note">Chrome flags any APK that is not from the Play Store — that warning is expected
    here. Open it on an Android phone; a laptop cannot install it.</div>
  <a class="back" href="/">← Back to shotarc.co.za</a>
</main>
<script>
  var dl=document.getElementById('dl');
  dl.addEventListener('click',function(){var t=dl.textContent;dl.textContent='Starting download…';setTimeout(function(){dl.textContent=t;},2500);});
</script>
</body></html>`
}
