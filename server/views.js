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

  return page('Golf Ball Tracker — dashboard', `
    <h1>Golf Ball Tracker</h1>
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
