import { statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const PUBLIC_DIR = dirname(fileURLToPath(import.meta.url)) + '/public'
let _assetV = null
/** A stamp that changes whenever a static asset changes, so cached copies are busted on deploy. */
function assetV() {
  if (_assetV) return _assetV
  try {
    const m = ['site.css', 'fonts.css', 'photo_course.jpg'].map(f => statSync(`${PUBLIC_DIR}/${f}`).mtimeMs)
    _assetV = Math.max(...m).toString(36).slice(-7)
  } catch { _assetV = '1' }
  return _assetV
}

const escape = s => String(s ?? '').replace(/[<>&"]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]))

/**
 * SQLite stores whatever it is handed, so a column declared INTEGER can hold markup. Anything
 * from the database that is printed as a number goes through here first: a real number, or a dash.
 */
const figure = (value, dash = '—') => (Number.isFinite(Number(value)) && value !== null && value !== '' ? Number(value) : dash)
const rounded = (value, unit = '', dash = '—') => {
  const n = figure(value, null)
  return n === null ? dash : `${Math.round(n)}${unit}`
}
const decimal = (value, places = 1, dash = '—') => {
  const n = figure(value, null)
  return n === null ? dash : n.toFixed(places)
}
/** Ball speed is stored in metres per second and always read in km/h. */
const kmh = (metresPerSecond, unit = '', dash = '—') => {
  const n = figure(metresPerSecond, null)
  return n === null ? dash : `${Math.round(n * 3.6)}${unit}`
}
const v = () => assetV()

const head = (title, description = '') => `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(title)}</title>
${description ? `<meta name="description" content="${escape(description)}">` : ''}
<link rel="icon" type="image/svg+xml" href="/assets/mark.svg?v=${v()}">
<link rel="stylesheet" href="/assets/site.css?v=${v()}">
</head>`

const mark = (size = '') =>
  `<a class="mark ${size}" href="/"><img src="/assets/mark.svg?v=${v()}" alt="" width="42" height="42">ShotArc<span class="dot">.</span></a>`

/** The colour fields the frosted panels blur against — without them the glass reads flat grey. */
const blooms = spots => `<div class="blooms">${spots.map(([side, off, top, size, yellow]) =>
  `<div class="bloom${yellow ? ' y' : ''}" style="${side}:${off};top:${top}px;width:${size}px;height:${size}px"></div>`).join('')}</div>`

function parClass(value) {
  const v = figure(value, null)
  if (v === null) return ['level', '—']
  if (v > 0) return ['plus', `+${v}`]
  if (v < 0) return ['minus', `${v}`]
  return ['level', 'level']
}

// ---------------------------------------------------------------- charts

/** Plan view: every shot of the round, tee to landing, in metres from the first tee. */
function planView(shots, cls = '') {
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

  const lines = placed.map(s => {
    const [x1, y1] = project(s.from_lat, s.from_lon)
    const [x2, y2] = project(s.to_lat, s.to_lon)
    return `<line pathLength="1" x1="${sx(x1).toFixed(1)}" y1="${sy(y1).toFixed(1)}" x2="${sx(x2).toFixed(1)}" y2="${sy(y2).toFixed(1)}"/>`
  }).join('')

  const marks = placed.map((s, i) => {
    const [x2, y2] = project(s.to_lat, s.to_lon)
    return `<circle cx="${sx(x2).toFixed(1)}" cy="${sy(y2).toFixed(1)}" r="4" fill="#E8FF00"/>
      <text x="${(sx(x2) + 8).toFixed(1)}" y="${(sy(y2) - 7).toFixed(1)}" fill="#8ea394"
        style="font:400 11px 'Roboto Mono',monospace">${figure(s.hole, '')}.${figure(s.shot_number, i + 1)}</text>`
  }).join('')

  const tees = placed.filter(s => s.shot_number === 1).map(s => {
    const [x, y] = project(s.from_lat, s.from_lon)
    return `<rect x="${(sx(x) - 4).toFixed(1)}" y="${(sy(y) - 4).toFixed(1)}" width="8" height="8" fill="#7ee787"/>`
  }).join('')

  return `<figure class="${cls}"><figcaption>Shot paths, north up</figcaption>
    <svg viewBox="0 0 ${w} ${h}" role="img" aria-label="Shot paths, north up">
      <g class="draw" stroke="#E8FF00" stroke-width="2" stroke-linecap="round"
         opacity=".85">${lines}</g>${marks}${tees}</svg></figure>`
}

/** Side view: the modelled flight of each shot, height against distance. */
function trajectoryView(shots, cls = '') {
  const flown = shots.filter(s => s.carry_m > 0)
  if (!flown.length) return ''
  const w = 900, h = 300, padL = 46, padB = 34
  const maxX = Math.max(...flown.map(s => s.carry_m)) * 1.05
  const maxY = Math.max(...flown.map(s => s.apex_m ?? 0), 10) * 1.25
  const sx = d => padL + (d / maxX) * (w - padL - 12)
  const sy = m => h - padB - (m / maxY) * (h - padB - 14)

  const curves = flown.map(s => {
    const track = s.track
    if (Array.isArray(track) && track.length > 1) {
      return `<path pathLength="1" d="${track.map((p, i) => `${i ? 'L' : 'M'}${sx(p[0]).toFixed(1)},${sy(p[1]).toFixed(1)}`).join('')}"/>`
    }
    const apex = s.apex_m ?? s.carry_m / 8
    return `<path pathLength="1" d="M${sx(0)},${sy(0)} Q${sx(s.carry_m * 0.55).toFixed(1)},${sy(apex * 1.85).toFixed(1)} ${sx(s.carry_m).toFixed(1)},${sy(0)}"/>`
  }).join('')

  const grid = [0, 0.25, 0.5, 0.75, 1].map(f => {
    const metres = Math.round(maxX * f)
    return `<line x1="${sx(metres)}" y1="${h - padB}" x2="${sx(metres)}" y2="14" stroke="#1f2b22"/>
      <text x="${sx(metres)}" y="${h - 12}" fill="#63745f" text-anchor="middle"
        style="font:400 11px 'Roboto Mono',monospace">${metres} m</text>`
  }).join('')

  return `<figure class="${cls}"><figcaption>Flight profile — ${flown.length} shots</figcaption>
    <svg viewBox="0 0 ${w} ${h}" role="img" aria-label="Flight profile, height against distance">
      ${grid}
      <line x1="${padL}" y1="${h - padB}" x2="${w - 12}" y2="${h - padB}" stroke="#2c3b30"/>
      <text x="6" y="24" fill="#63745f" style="font:400 11px 'Roboto Mono',monospace">${Math.round(maxY)} m</text>
      <g class="draw late" fill="none" stroke="#E8FF00" stroke-width="1.8"
         opacity=".75">${curves}</g></svg></figure>`
}

// ---------------------------------------------------------------- landing

/** The live readout, the pointer parallax and the mobile menu. Nothing else on the page needs JS. */
const LANDING_JS = `
(function(){
 var reduce=matchMedia('(prefers-reduced-motion:reduce)').matches;
 function frameAt(t){
  if(t<1800)return{s:'searching',status:'SEARCHING FOR THE BALL',v:['—','—','—'],
    frames:'0 SAMPLES',fill:0,sweep:t/1800};
  if(t<3600){var n=Math.min(8,Math.floor((t-1800)/225)+1);
    return{s:'locked',status:'BALL LOCKED — HIT IT',v:['—','—','—'],
      frames:n+'/8 STILL FRAMES',fill:n/8,sweep:1};}
  if(t<5400){var p=(t-3600)/1800,e=1-Math.pow(1-p,3);
    return{s:'tracking',status:'TRACKING…',
      v:[Math.round(e*245)+' km/h',(e*12.2).toFixed(1)+'°',Math.round(e*249)+' m'],
      frames:Math.round(e*38)+' SAMPLES',fill:e,sweep:1};}
  return{s:'captured',status:'SHOT CAPTURED',v:['245 km/h','12.2°','249 m'],
    frames:'38 SAMPLES',fill:1,sweep:1};
 }
 function write(n,t){if(n&&n.textContent!==t)n.textContent=t}
 document.querySelectorAll('[data-readout]').forEach(function(el){
  var status=el.querySelector('[data-v=status]'),sweep=el.querySelector('[data-v=sweep]'),
      fill=el.querySelector('[data-v=fill]'),frames=el.querySelector('[data-v=frames]'),
      vals=[el.querySelector('[data-v=speed]'),el.querySelector('[data-v=launch]'),el.querySelector('[data-v=carry]')];
  write(el.querySelector('[data-v=score]'),'87');
  write(el.querySelector('[data-v=verdict]'),'Excellent · 0.19 s of flight');
  function apply(f){
   if(el.dataset.state!==f.s)el.dataset.state=f.s;
   write(status,f.status);write(frames,f.frames);
   for(var i=0;i<3;i++)write(vals[i],f.v[i]);
   sweep.style.width=(f.sweep*100).toFixed(1)+'%';
   fill.style.width=(f.fill*100).toFixed(1)+'%';
  }
  if(reduce){apply(frameAt(8000));return}
  var t0=performance.now();
  el._raf=requestAnimationFrame(function tick(now){
   apply(frameAt((now-t0)%9000));el._raf=requestAnimationFrame(tick);
  });
 });

 var hero=document.querySelector('[data-plx-scene]');
 if(hero&&!reduce&&!matchMedia('(hover:none)').matches){
  var layers=[].map.call(hero.querySelectorAll('[data-plx]'),function(n){
   return{n:n,d:parseFloat(n.getAttribute('data-plx')),x:0,y:0,tx:0,ty:0}});
  var raf=null;
  function run(){
   var moving=false;
   layers.forEach(function(l){
    l.x+=(l.tx-l.x)*0.09;l.y+=(l.ty-l.y)*0.09;
    if(Math.abs(l.tx-l.x)>0.05||Math.abs(l.ty-l.y)>0.05)moving=true;
    l.n.style.transform='translate3d('+l.x.toFixed(2)+'px,'+l.y.toFixed(2)+'px,0)';
   });
   raf=moving?requestAnimationFrame(run):null;
  }
  function kick(){if(!raf)raf=requestAnimationFrame(run)}
  hero.addEventListener('pointermove',function(e){
   var r=hero.getBoundingClientRect();
   var dx=e.clientX-(r.left+r.width/2),dy=e.clientY-(r.top+r.height/2);
   layers.forEach(function(l){l.tx=dx*l.d;l.ty=dy*l.d});kick();
  });
  hero.addEventListener('pointerleave',function(){
   layers.forEach(function(l){l.tx=0;l.ty=0});kick();
  });
 }

 var btn=document.getElementById('menuBtn'),sheet=document.getElementById('sheet');
 if(btn&&sheet){
  btn.addEventListener('click',function(){
   var open=!sheet.classList.contains('open');
   sheet.classList.toggle('open',open);
   btn.setAttribute('aria-expanded',open);
   hero.classList.toggle('dimmed',open);
  });
  sheet.addEventListener('click',function(e){
   if(e.target.closest('a')){sheet.classList.remove('open');
    btn.setAttribute('aria-expanded','false');hero.classList.remove('dimmed');}
  });
 }
})();`

const readout = () => `<div class="readout" data-readout data-state="searching" aria-hidden="true">
  <div class="sweep" data-v="sweep"></div>
  <div class="head"><div class="dot"></div><div class="status" data-v="status">SEARCHING FOR THE BALL</div></div>
  <div class="r"><span class="k">Ball speed</span><span class="v" data-v="speed">—</span></div>
  <div class="r"><span class="k">Launch</span><span class="v" data-v="launch">—</span></div>
  <div class="r"><span class="k">Carry</span><span class="v" data-v="carry">—</span></div>
  <div class="grade"><div class="score" data-v="score"></div><div class="verdict" data-v="verdict"></div></div>
  <div class="meter"><div class="track"><div class="fill" data-v="fill"></div></div>
    <div class="frames" data-v="frames">0 SAMPLES</div></div>
</div>`

const VALUES = [
  ['BALL SPEED', '245', ' km/h', true],
  ['LAUNCH ANGLE', '12.2°', '', false],
  ['START LINE', '0.6°', ' left', false],
  ['CARRY', '249', ' m', false],
  ['SHOT SCORE', '87', ' /100', false],
]

const STEPS = [
  ['01', 'Set the phone behind',
    'On the ground or a tripod, a couple of paces behind the ball and down the target line. Nothing to pair, nothing to charge.',
    '≈ 2 M BEHIND · LENS DOWN THE LINE'],
  ['02', 'Play your shot',
    'ShotArc finds the ball at rest on the grass, then follows it into flight and draws the arc as it climbs — indoors into a net, or out to the horizon.',
    'LOCKED IN 8 FRAMES · GRASS CHECKED'],
  ['03', 'Read the number',
    'Ball speed, launch, start line, carry and a score for the strike. On a course, where it comes to rest and your standing against par.',
    '40% LINE · 30% LAUNCH · 30% SPEED'],
]

const TOLERANCES = [
  ['Carry, versus TrackMan tour averages', 'within 2%', 0.09],
  ['Recovered ball speed', 'within 5.3%', 0.20],
  ['Launch angle', 'within 2°', 0.11],
  ['Air assumed', 'sea level', null],
  ['On the Highveld it therefore reads', '6–8% short', 0.34],
  ['Courses mapped, all in Gauteng', '10', null],
]

/** The live camera HUD as the app draws it, for the in-the-hand preview. */
const hudScreen = () => `<div class="hud-shot">
  <img src="/assets/photo_course.jpg?v=${v()}" alt="">
  <div class="veil"></div>
  <svg viewBox="0 0 396 812" aria-hidden="true">
    <line x1="198" y1="300" x2="198" y2="812" stroke="rgba(255,255,255,.18)" stroke-dasharray="6 10"/>
    <circle cx="198" cy="572" r="34" fill="none" stroke="#78FFA0" stroke-width="2"/>
    <circle cx="198" cy="572" r="46" fill="none" stroke="rgba(120,255,160,.3)" stroke-width="1"/>
  </svg>
  <div class="panel">
    <div class="lock"><i></i>Ball locked — hit it</div>
    <div class="cond"><span class="ok">✓ GRASS</span><span class="ok">✓ STILL 8/8</span><span class="fps">118 fps</span></div>
  </div>
  <div class="below">
    <div class="cue"><div class="tag">CUE</div><p>Ball is high in frame — tilt the phone down a touch.</p></div>
    <div class="chips">
      <div><div class="k">CLUB</div><div class="v">Driver</div></div>
      <div><div class="k">LIE</div><div class="v">Fairway</div></div>
    </div>
  </div>
</div>`

/** The shot result as the app draws it: white is measured, yellow is modelled. */
const resultScreen = () => `<div class="res">
  <div class="top"><div class="where">HOLE 1 · PAR 4</div><div class="setup">Driver · Fairway</div></div>
  <div class="arc"><div class="tex"></div>
    <svg viewBox="0 0 396 300" aria-hidden="true">
      <line x1="0" y1="252" x2="396" y2="252" stroke="rgba(255,255,255,.35)"/>
      <path d="M36 252 Q170 24 316 252" fill="none" stroke="rgba(232,255,0,.22)" stroke-width="11" stroke-linecap="round"/>
      <path d="M36 252 Q170 24 316 252" fill="none" stroke="#E8FF00" stroke-width="3" stroke-linecap="round"/>
      <path d="M36 252 Q52 225 68 204" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round"/>
      <circle cx="36" cy="252" r="4.5" fill="#fff"/><circle cx="316" cy="252" r="6" fill="#E8FF00"/>
      <text x="184" y="70" fill="#fff" style="font:500 12px 'Roboto Mono',monospace">APEX 31 m</text>
      <text x="316" y="272" text-anchor="middle" fill="#E8FF00" style="font:500 12px 'Roboto Mono',monospace">249 m</text>
    </svg></div>
  <div class="verdict"><div class="n">87</div>
    <div><div class="w">Excellent</div><div class="o">OUT OF 100</div></div></div>
  <div class="grid">
    <div><div class="k">BALL SPEED</div><div class="v">245 <span>km/h</span></div></div>
    <div><div class="k">LAUNCH</div><div class="v">12.2°</div></div>
    <div><div class="k">START</div><div class="v">0.6° <span>left</span></div></div>
    <div><div class="k">CARRY</div><div class="v">249 <span>m</span></div></div>
  </div>
  <div class="fill"></div>
  <div class="prov">38 samples · 0.19 s of flight · landing modelled</div>
</div>`

const phone = inner => `<div class="slot"><div class="phone">
  <div class="sbar">9:30<div class="punch"></div>
    <svg class="icons" width="44" height="12" viewBox="0 0 44 12" fill="#fff" aria-hidden="true">
      <path d="M0 9.5 A7 7 0 0 1 12 9.5 L6 12Z"/><path d="M18 12V2l10 10Z"/>
      <rect x="32" y="2.5" width="11" height="7" rx="1.5"/><rect x="43.2" y="4.5" width="1" height="3" rx=".5"/>
    </svg></div>
  <div class="screenbody">${inner}</div>
  <div class="navbar"><i></i></div>
</div></div>`

/** Miniature redraws of the same two screens, for the mobile swipe rail. */
const railScreens = () => `
<div><div class="mini">
  <img src="/assets/photo_course.jpg?v=${v()}" alt="" style="position:absolute;inset:0;width:100%;height:100%;object-fit:cover">
  <div style="position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,.6),rgba(0,0,0,0) 30%,rgba(0,0,0,.7))"></div>
  <svg viewBox="0 0 170 340" style="position:absolute;inset:0;width:100%;height:100%" aria-hidden="true">
    <circle cx="85" cy="215" r="20" fill="none" stroke="#78FFA0" stroke-width="1.6"/>
    <circle cx="85" cy="215" r="28" fill="none" stroke="rgba(120,255,160,.3)"/></svg>
  <div style="position:absolute;left:8px;right:8px;top:8px;background:rgba(0,0,0,.55);border:1px solid rgba(255,255,255,.12);border-radius:10px;padding:8px 9px">
    <div style="display:flex;align-items:center;gap:6px"><i style="width:6px;height:6px;border-radius:50%;background:#78FFA0"></i><span style="font:500 10.5px/1.2 Roboto,sans-serif;color:#fff">Ball locked — hit it</span></div>
    <div style="font:500 7.5px/1 'Roboto Mono',monospace;letter-spacing:.08em;color:#78FFA0;padding-top:6px">✓ GRASS · ✓ STILL 8/8</div></div>
  <div style="position:absolute;left:8px;right:8px;bottom:8px;background:rgba(232,255,0,.92);border-radius:10px;padding:8px 9px;font:500 9.5px/1.35 Roboto,sans-serif;color:#0B100C">Ball is high in frame — tilt down a touch.</div>
</div><div class="k">BEFORE THE STRIKE</div></div>
<div><div class="mini">
  <div style="background:linear-gradient(180deg,#0F6E56,#0b3d31 70%,#0B100C);padding-bottom:6px">
    <svg viewBox="0 0 170 130" style="display:block;width:100%" aria-hidden="true">
      <line x1="0" y1="110" x2="170" y2="110" stroke="rgba(255,255,255,.3)"/>
      <path d="M16 110 Q74 12 136 110" fill="none" stroke="rgba(232,255,0,.25)" stroke-width="7" stroke-linecap="round"/>
      <path d="M16 110 Q74 12 136 110" fill="none" stroke="#E8FF00" stroke-width="2" stroke-linecap="round"/>
      <path d="M16 110 Q23 98 30 89" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round"/>
      <circle cx="136" cy="110" r="3.6" fill="#E8FF00"/></svg></div>
  <div style="padding:12px">
    <div style="display:flex;align-items:flex-end;gap:8px">
      <span style="font:600 40px/.85 var(--serif);color:#E8FF00">87</span>
      <span style="font:400 12px/1 var(--serif);color:#fff;padding-bottom:3px">Excellent</span></div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:1px;background:rgba(255,255,255,.12);margin-top:12px">
      ${[['SPEED', '245'], ['LAUNCH', '12.2°'], ['START', '0.6°L'], ['CARRY', '249 m']].map(([k, n]) =>
        `<div style="background:#0B100C;padding:8px"><div style="font:500 7px/1 var(--mono);letter-spacing:.12em;color:rgba(255,255,255,.5)">${k}</div><div style="font:500 13px/1.2 var(--mono);color:#fff;padding-top:3px">${n}</div></div>`).join('')}
    </div></div>
</div><div class="k">AFTER THE STRIKE</div></div>`

/** Marketing landing at /: the live readout, the app in the hand, and what the model does not measure. */
export function renderLanding(req, apk, user, nonce) {
  const size = apk ? `${(apk.size / 1048576).toFixed(1)} MB` : ''
  const dl = `Download the app${size ? ` · ${size}` : ''}`
  const dashLink = user ? '/dashboard' : '/login?next=/dashboard'
  const dashLabel = user ? 'Enter the dashboard' : 'Members entrance'
  const sample = sampleSession()

  const cta = `<div class="hero-cta">
    <a class="btn" href="/install">${dl}</a>
    <a class="tlink" href="${dashLink}">${dashLabel} →</a></div>`

  return `${head('ShotArc — read the flight of every shot',
    'Stand your phone behind the ball. ShotArc traces the flight and gives you ball speed, launch, carry and a score — then your whole round on the dashboard.')}
<body>
${blooms([['left', '-8%', 520, 900], ['right', '-10%', 1500, 820, true],
          ['left', '22%', 2900, 1000], ['right', '6%', 4200, 760, true]])}

<header class="hero" data-plx-scene>
  <div class="hero-tex" data-plx="0.012"></div>
  <div class="hero-bloom" data-plx="0.05"></div>

  <div class="topbar" data-plx="-0.007">
    ${mark()}
    <nav class="nav">
      <a href="#app">The App</a>
      <a href="#numbers">The Numbers</a>
      <a href="#dashboard">The Dashboard</a>
      <a class="members" href="${dashLink}">${user ? 'Dashboard' : 'Members'}</a>
    </nav>
    <button class="menu-btn" id="menuBtn" aria-label="Menu" aria-expanded="false" aria-controls="sheet">
      <span></span><span></span><span></span></button>
  </div>

  <div class="sheet" id="sheet">
    <a class="row" href="#app">The App<span class="ch">→</span></a>
    <a class="row" href="#numbers">The Numbers<span class="ch">→</span></a>
    <a class="row" href="#dashboard">The Dashboard<span class="ch">→</span></a>
    <a class="row" href="${dashLink}">Members<span class="tag">${user ? 'DASHBOARD' : 'SIGN IN'}</span></a>
    <div class="foot-cta"><a class="btn" href="/install">Download${size ? ` · ${size}` : ''}</a>
      <div class="fnote">ANDROID · NO SENSORS · NO PAIRING</div></div>
  </div>

  <div class="hero-copy-plx" data-plx="-0.016"><div class="hero-copy">
    <div class="hero-text">
      <p class="eyebrow">A camera launch monitor</p>
      <h1>Read the flight of <em>every</em> shot.</h1>
      <p class="lead">Stand your phone behind the ball. ShotArc follows it off the face, traces the
        line, and reads back ball speed, launch, carry and the quality of the strike.</p>
      ${cta}
    </div>
    <div class="hero-spec">
      <div class="top">ANDROID · NO SENSORS · NO PAIRING</div>
      <div class="fig">120 fps · 720p<br>42.67 mm reference<br>sea-level air</div>
    </div>
  </div></div>

  <div class="readout-plx" data-plx="0.03">${readout()}</div>
</header>

<section class="statement reveal">
  <p class="eyebrow">No gadgets</p>
  <h2>No launch monitor. No sensors on the club. <em>Just the camera in your bag.</em></h2>
  <p class="lead">The measurement most players never had access to, from the phone already in your
    pocket — on the range at dawn, in the bay in winter, or standing in your own back garden.</p>
</section>

<section class="values" id="numbers">
  <div class="plate-5 glass reveal">
    ${VALUES.map(([label, n, unit, measured], i) =>
      `<div class="cell" style="--i:${i}">
        <div class="dl">${label}</div>
        <div class="dv${measured ? ' m' : ''}">${n}${unit ? `<span class="u">${unit}</span>` : ''}</div>
      </div>`).join('')}
  </div>
</section>

<section class="steps-sec">
  <p class="eyebrow">How it plays</p>
  <h2 class="reveal">Three unhurried steps.</h2>
  <div class="steps reveal">
    ${STEPS.map(([num, title, body, note]) => `<div class="step glass">
      <div class="num">${num}</div><h3>${title}</h3>
      <p class="body">${body}</p><div class="fnote">${note}</div></div>`).join('')}
  </div>
</section>

<section class="hand" id="app">
  <div class="sec-head">
    <div><p class="eyebrow">In the hand</p><h2>What you actually look at.</h2></div>
    <p class="body">Two screens, and that is the whole app: the one you set up behind the ball, and
      the one you read after you have hit it.</p>
  </div>
  <div class="screens">
    <div class="screen">${phone(hudScreen())}
      <div class="cap"><div class="k">Before the strike</div>
        <p>The app arms itself only when the ball is still and ringed by grass, and says why in plain words.</p></div>
    </div>
    <div class="screen">${phone(resultScreen())}
      <div class="cap"><div class="k">After the strike</div>
        <p>White is what the camera saw; yellow is the model carrying it on. Score, speed, launch, start line and carry.</p></div>
    </div>
  </div>
  <div class="rail">${railScreens()}</div>
  <div class="rail-hint">Swipe →</div>
  <p class="caption">Android, sideloaded from this site. Nothing to pair, nothing to charge.</p>
</section>

<section class="dashprev" id="dashboard">
  <div class="sec-head">
    <div><p class="eyebrow">Afterwards</p><h2>Every round, kept.</h2></div>
    <p class="body">Each session syncs to a private dashboard: the shape of every shot down each hole,
      its flight in profile, your longest of the day and your score against par. Yours to revisit.</p>
  </div>
  <div class="plate glass reveal">
    <div class="plate-bar"><i></i><i></i><i></i><span class="u">shotarc.co.za / dashboard</span></div>
    <div class="plate-body">
      <div class="ptiles">
        <div class="ptile"><div class="n">245</div><div class="l">KM/H BALL SPEED</div></div>
        <div class="ptile"><div class="n">258 m</div><div class="l">LONGEST CARRY</div></div>
        <div class="ptile"><div class="n">87</div><div class="l">BEST STRIKE</div></div>
        <div class="ptile"><div class="n">+3</div><div class="l">THROUGH 6</div></div>
      </div>
      <div class="pcharts">${planView(sample, 'pchart-plan')}${trajectoryView(sample)}</div>
    </div>
  </div>
  <p class="caption">A sample round. Your dashboard opens with the Members link.</p>
</section>

<section class="honesty reveal">
  <div class="stripes"></div>
  <div class="in">
    <div>
      <p class="eyebrow">Honestly</p>
      <h2>The camera sees a fifth of a second. The rest is modelled — and we say so.</h2>
      <p class="lead">Speed, launch and start line are measured. Where the ball comes down is a
        ballistic model with drag and spin lift.</p>
    </div>
    <div>
      <div class="tol">
        ${TOLERANCES.map(([label, value, to], i) => `<div class="row${i === 0 ? ' lead-row' : ''}">
          <div class="line"><span class="k">${label}</span><span class="v">${value}</span></div>
          ${to === null ? '' : `<div class="bar"><i style="--to:${to}"></i></div>`}
        </div>`).join('')}
      </div>
      <p class="caption">Bars show how tight each figure is — the narrower the bar, the closer the
        model sat to the measured value.</p>
    </div>
  </div>
</section>

<section class="closing reveal">
  <p class="eyebrow">Begin</p>
  <h2>Take the measure of your game.</h2>
  <p class="lead">Android, sideloaded from here. The camera is all it needs.</p>
  ${cta}
</section>

<footer class="foot">
  ${mark('xs')}
  <p class="fine">Course data © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>,
    ODbL. Flight and landing are modelled from the measured launch, not observed after it leaves frame.</p>
  <div class="links"><a href="/privacy">Privacy</a><a href="/install">Get the app</a><a href="${dashLink}">Members</a></div>
</footer>

<div class="sticky">
  <a class="btn" href="/install">Download${size ? ` · ${size}` : ''}</a>
  <a class="tlink" href="${dashLink}">Members</a>
</div>

<script nonce="${escape(nonce)}">${LANDING_JS}</script>
</body></html>`
}

// ---------------------------------------------------------------- install

/** The get-the-app page the download buttons point to — always a visible navigation. */
export function renderInstall(apk, version, nonce) {
  const size = apk ? `${(apk.size / 1048576).toFixed(1)} MB` : null
  const ver = version?.versionName && version.versionName !== 'unknown' ? ` · v${escape(version.versionName)}` : ''
  return `${head('ShotArc — get the app')}
<body>
${blooms([['left', '-6%', 260, 820], ['right', '-8%', 900, 700, true]])}
<main class="install">
  ${mark('sm')}
  <div class="cols">
    <div>
      <p class="eyebrow">Android · ${size ?? 'coming soon'}${ver}</p>
      <h1>Get the app.</h1>
      <p class="lead">Not on the Play Store — you install it straight from here, which takes two taps
        and one permission. Android will warn you; that warning is expected and shown here exactly
        as it appears.</p>
      <a class="btn" id="dl" href="/golf-tracker.apk" download="shotarc.apk">Download ShotArc${size ? ` · ${size}` : ''}</a>
      <p class="fallback">If nothing happens, <a href="/golf-tracker.apk" download="shotarc.apk">tap here</a>.
        On a laptop? <b>Send yourself this link</b> — an APK cannot install on a desktop.</p>
      <div class="stepsl">
        <div class="r"><div class="n">01</div><div>
          <div class="t">Open the downloaded file</div>
          <div class="d">It lands in Downloads as <code>shotarc.apk</code>.</div></div></div>
        <div class="r"><div class="n">02</div><div>
          <div class="t">Allow this install, once</div>
          <div class="d">Android asks whether your browser may install apps. Turn it on, continue,
            turn it back off after if you like.</div></div></div>
        <div class="r"><div class="n">03</div><div>
          <div class="t">Allow the camera</div>
          <div class="d">The only permission it asks for. No location, no account, no ads.</div></div></div>
      </div>
      <a class="back" href="/">← Back to shotarc.co.za</a>
    </div>
    <div>
      <div class="kicker">WHAT YOU WILL SEE</div>
      <div class="chrome" role="img" aria-label="Chrome's download warning: this type of file can harm your device. Do you want to keep shotarc.apk anyway? Cancel or Keep.">
        <div class="top"><div class="ring">!</div>
          <div><div class="t">This type of file can harm your device</div>
            <div class="s">Do you want to keep shotarc.apk anyway?</div></div></div>
        <div class="acts"><span>Cancel</span><span>Keep</span></div>
      </div>
      <p class="note">Chrome flags every APK that did not come from the Play Store — including this
        one. Tap <b>Keep</b>. Nothing about the warning is specific to ShotArc.</p>
      <div class="perms glass">
        <div class="kicker">WHAT IT ASKS OF YOUR PHONE</div>
        <div class="r"><span>Camera</span><span class="v req">required</span></div>
        <div class="r"><span>Location</span><span class="v">never</span></div>
        <div class="r"><span>Sign-in inside the app</span><span class="v">none</span></div>
        <div class="r"><span>Video kept or uploaded</span><span class="v">no</span></div>
        <p class="fine">Frames are processed on the phone in real time. Only the numbers leave it —
          <a href="/privacy">the privacy page</a> says exactly which. To see those numbers on a
          dashboard you make an account here and type its pairing code into the app once; skip that
          and the app still works, it just keeps everything to itself.</p>
      </div>
    </div>
  </div>
</main>
<script nonce="${escape(nonce)}">
(function(){var d=document.getElementById('dl');d.addEventListener('click',function(){
 var t=d.textContent;d.textContent='Starting download…';setTimeout(function(){d.textContent=t},2500)})})();
</script>
</body></html>`
}

// ---------------------------------------------------------------- members

/** A count, grouped in threes. Anything that is not a number becomes a dash, never markup. */
const num = n => {
  const value = figure(n, null)
  return value === null ? '—' : String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ' ')
}

export function renderDashboard({ stats, sessions, insight, phones, pairCode, site, user }) {
  const rows = sessions.map(s => {
    const [cls, label] = parClass(s.holes_played ? s.through_par : null)
    return `<tr>
      <td class="when"><a href="/dashboard/session/${encodeURIComponent(s.session_id)}">${escape(s.started_at.slice(0, 16).replace('T', ' '))}</a></td>
      <td class="course">${escape(s.course ?? 'Practice')}</td>
      <td>${escape([s.environment, s.ball, s.time_of_day].filter(Boolean).join(' · '))}</td>
      <td class="num">${figure(s.shots, 0)}</td>
      <td class="num">${rounded(s.longest_m, ' m')}</td>
      <td class="num">${figure(s.holes_played, 0)}</td>
      <td class="par ${cls}">${label}</td>
    </tr>`
  }).join('')

  const cells = [
    [num(stats.sessions), 'SESSIONS'],
    [num(stats.shots), 'SHOTS TRACKED'],
    [stats.longestM ? `${Math.round(stats.longestM)} m` : '—', 'LONGEST CARRY'],
    [stats.fastestMs ? num(Math.round(stats.fastestMs * 3.6)) : '—', 'KM/H FASTEST BALL'],
    [stats.bestScore ?? '—', 'BEST STRIKE'],
    [stats.avgScore ?? '—', 'AVERAGE STRIKE'],
    [num(stats.holes), 'HOLES PLAYED'],
  ]

  return `${head('ShotArc — dashboard')}
<body>
${blooms([['left', '-6%', 200, 800], ['right', '-8%', 1100, 700, true]])}
<main class="members-page">
  <div class="mhead">
    <div style="display:flex;align-items:center">${mark('sm')}<span class="tag">MEMBERS</span></div>
    <div class="who">
      ${user ? `<span class="u">${escape(user)}</span>` : ''}
      <form method="post" action="/logout" style="margin:0"><button class="ghost">Log out</button></form>
    </div>
  </div>
  <div class="mtitle">
    <h1>Every shot the camera registered.</h1>
    <div class="aside">Your own rounds, and nobody else's.<br>Uploaded from your phone after each shot.</div>
  </div>
  <div class="mstats">
    ${cells.map(([n, l]) => `<div class="cell"><div class="n">${n}</div><div class="l">${l}</div></div>`).join('')}
  </div>
  ${site ? siteRow(site) : ''}
  ${phones.length ? `<div class="mcols">
    <div>
      <div class="kicker">SESSIONS</div>
      ${sessions.length ? `<div class="wrap-x"><table>
        <thead><tr><th>WHEN</th><th>COURSE</th><th>SETUP</th><th>SHOTS</th><th>LONGEST</th><th>HOLES</th>
          <th style="text-align:right">VS PAR</th></tr></thead><tbody>${rows}</tbody></table></div>`
        : '<p class="empty">This phone has not uploaded a round yet.</p>'}
    </div>
    <div>
      <div class="side glass">${consistency(insight)}</div>
      ${phoneList(phones, pairCode)}
    </div>
  </div>` : connectPanel(pairCode)}
</main>
</body></html>`
}

/** Downloads and installs are about the site, not anyone's golf. Only the admin account sees them. */
function siteRow(site) {
  const figures = [
    [num(site.downloads), 'APK downloads'],
    [num(site.installs), 'installs seen'],
    [site.installedOfDownloads === null ? '—' : `${site.installedOfDownloads}%`, 'ran after downloading'],
  ]
  return `<div class="site-row">
    <span class="kicker">ACROSS THE SITE</span>
    ${figures.map(([n, l]) => `<span><b>${n}</b> ${l}</span>`).join('')}
  </div>`
}

/** Nothing can appear here until a phone has been paired, so say so and hand over the code. */
function connectPanel(code) {
  return `<div class="connect glass">
    <div class="kicker">FIRST, CONNECT YOUR PHONE</div>
    <h2>Nothing here yet.</h2>
    <p class="lead">Rounds show up once the app on your phone knows it is yours. Open ShotArc,
      go to <b>Connect to your account</b>, and type this code.</p>
    <div class="code">${escape(code)}</div>
    <p class="caption">Good for fifteen minutes, and for one phone. Reload this page for a fresh one.</p>
    <div class="steps-thin">
      <div><span class="n">01</span> Install the app from <a href="/install">the download page</a>.</div>
      <div><span class="n">02</span> Open it and enter the code above.</div>
      <div><span class="n">03</span> Play. Every shot lands here.</div>
    </div>
  </div>`
}

function phoneList(phones, code) {
  return `<div class="side glass phones">
    <div class="kicker">YOUR PHONES</div>
    ${phones.map(p => `<div class="r">
      <span>${escape(p.device ?? 'Unknown phone')}</span>
      <span class="v">${escape((p.last_seen ?? '').slice(0, 10))}</span>
    </div>`).join('')}
    <p class="caption">Adding another? Enter <b>${escape(code)}</b> in the app — fifteen minutes, one phone.</p>
  </div>`
}

/** The side panel: how the strike score has moved, and what the shots have in common. */
function consistency(insight) {
  const { from, to, since, delta, club, miss } = insight ?? {}
  const scores = (insight?.scores ?? []).map(n => figure(n, null)).filter(n => n !== null)
  const w = 420, h = 190
  let chart = '<p class="caption" style="margin-top:16px">Not enough scored shots yet.</p>'
  if (scores.length > 1) {
    const step = w / (scores.length - 1)
    const y = s => h - 34 - (Math.max(0, Math.min(100, s)) / 100) * (h - 60)
    const pts = scores.map((s, i) => `${(i * step).toFixed(0)},${y(s).toFixed(0)}`).join(' ')
    chart = `<svg viewBox="0 0 ${w} ${h}" role="img" aria-label="Strike score over the last ${scores.length} shots">
      <line x1="0" y1="${y(90).toFixed(0)}" x2="${w}" y2="${y(90).toFixed(0)}" stroke="rgba(236,230,214,.12)" stroke-dasharray="2 5"/>
      <line x1="0" y1="${y(60).toFixed(0)}" x2="${w}" y2="${y(60).toFixed(0)}" stroke="rgba(236,230,214,.12)" stroke-dasharray="2 5"/>
      <polyline class="draw" pathLength="1" points="${pts}" fill="none" stroke="#E8FF00" stroke-width="2"/>
      <text x="0" y="${(y(90) - 8).toFixed(0)}" fill="#63745f" style="font:400 11px 'Roboto Mono',monospace">90</text>
      <text x="0" y="${(y(60) - 8).toFixed(0)}" fill="#63745f" style="font:400 11px 'Roboto Mono',monospace">60</text>
      <text x="0" y="${h - 14}" fill="#63745f" style="font:400 11px 'Roboto Mono',monospace">${escape(from ?? '')}</text>
      <text x="${w}" y="${h - 14}" text-anchor="end" fill="#63745f" style="font:400 11px 'Roboto Mono',monospace">${escape(to ?? '')}</text>
    </svg>`
  }

  const read = delta === null || delta === undefined || !since
    ? ''
    : `<p class="read">Average strike score is ${delta >= 0 ? 'up' : 'down'}
        <b>${Math.abs(delta)} point${Math.abs(delta) === 1 ? '' : 's'}</b> since ${escape(since)}.</p>`

  const summary = [['Most-played club', club], ['Typical miss', miss]].filter(([, val]) => val)

  const heading = scores.length
    ? `CONSISTENCY · LAST ${scores.length} SHOT${scores.length === 1 ? '' : 'S'}`
    : 'CONSISTENCY'

  return `<div class="kicker">${heading}</div>
    ${chart}${read}
    ${summary.length ? `<div class="rows">${summary.map(([k, val]) =>
      `<div class="r"><span>${k}</span><span class="v">${escape(val)}</span></div>`).join('')}</div>` : ''}`
}

/** The pine panel beside both member forms: an arc that draws itself, and no figures. */
const memberAside = () => `<div class="right">
    <div class="stripes"></div>
    <svg viewBox="0 0 620 500" aria-hidden="true">
      <line x1="40" y1="400" x2="580" y2="400" stroke="rgba(255,255,255,.25)"/>
      <path d="M90 400 Q330 90 540 330" fill="none" stroke="rgba(255,255,255,.25)" stroke-width="14" stroke-linecap="round"/>
      <path class="draw" pathLength="1" style="animation-duration:8s" d="M90 400 Q330 90 540 330"
        fill="none" stroke="#E8FF00" stroke-width="4" stroke-linecap="round"/>
      <circle cx="90" cy="400" r="7" fill="#fff"/>
    </svg>
    <div style="position:relative">
      <div class="k">MEASURED ON THE PHONE · KEPT ON YOUR DASHBOARD</div>
      <div class="t">Everything you have hit, kept exactly as it happened.</div>
    </div>
  </div>`

/** Public page: it must not carry a single figure from behind the login. */
export function renderLogin({ next, error }) {
  const nextField = next ? `<input type="hidden" name="next" value="${escape(next)}">` : ''

  return `${head('ShotArc — members')}
<body class="signin">
  <div class="left">
    <p class="eyebrow">ShotArc</p>
    <h1>Members.</h1>
    <p class="lead">Sign in to your own rounds. Your phone uploads them; nobody else sees them.</p>
    <form method="post" action="/login">
      ${nextField}
      <label for="u">Username</label>
      <input id="u" name="username" autocomplete="username" autofocus>
      <label for="p">Password</label>
      <input id="p" name="password" type="password" autocomplete="current-password">
      ${error ? `<div class="err">${escape(error)}</div>` : ''}
      <button class="btn" type="submit">Sign in</button>
      <p class="alt">No account yet? <a href="/signup">Create one</a>.</p>
      <a class="back" href="/">← Back to shotarc.co.za</a>
    </form>
  </div>
  ${memberAside()}
</body></html>`
}

export function renderSignup({ username, error }) {
  return `${head('ShotArc — create an account')}
<body class="signin">
  <div class="left">
    <p class="eyebrow">ShotArc</p>
    <h1>Join.</h1>
    <p class="lead">An account holds the rounds your phone sends up. No email, no card — a name and
      a password, and a code to pair the phone.</p>
    <form method="post" action="/signup">
      <label for="u">Choose a username</label>
      <input id="u" name="username" autocomplete="username" autofocus
        value="${escape(username ?? '')}" maxlength="24">
      <label for="p">Choose a password</label>
      <input id="p" name="password" type="password" autocomplete="new-password" minlength="10">
      <p class="hint">Three to 24 characters for the name. At least ten for the password.</p>
      ${error ? `<div class="err">${escape(error)}</div>` : ''}
      <button class="btn" type="submit">Create account</button>
      <p class="alt">Already a member? <a href="/login">Sign in</a>.</p>
      <a class="back" href="/">← Back to shotarc.co.za</a>
    </form>
  </div>
  ${memberAside()}
</body></html>`
}

// ---------------------------------------------------------------- session detail

const tile = (n, l) => `<div class="cell"><div class="n">${n ?? '—'}</div><div class="l">${l}</div></div>`

export function renderSession(session, shots) {
  const parsed = shots.map(s => ({ ...s, track: s.track ? JSON.parse(s.track) : null }))
  const longest = parsed.reduce((best, s) => (s.carry_m > (best?.carry_m ?? 0) ? s : best), null)
  const fastest = parsed.reduce((best, s) => (s.ball_speed_ms > (best?.ball_speed_ms ?? 0) ? s : best), null)
  const [cls, label] = parClass(session.holes_played ? session.through_par : null)

  const rows = parsed.map(s => `<tr>
      <td class="num">${figure(s.hole)}.${figure(s.shot_number, '')}</td>
      <td class="course">${escape(prettyClub(s.club))}</td>
      <td>${escape(s.lie ?? '')}</td>
      <td class="num">${kmh(s.ball_speed_ms, ' km/h')}</td>
      <td class="num">${decimal(s.launch_deg)}°</td>
      <td class="num">${decimal(s.offline_deg)}°</td>
      <td class="num">${rounded(s.carry_m, ' m')}</td>
      <td class="num">${rounded(s.apex_m, ' m')}</td>
      <td class="num">${rounded(s.to_green_m, ' m')}</td>
      <td class="num">${figure(s.score)}</td>
    </tr>`).join('')

  return `${head(`Session — ${session.course ?? 'Practice'}`)}
<body>
${blooms([['left', '-6%', 240, 800], ['right', '-8%', 1200, 700, true]])}
<main class="members-page">
  <div class="mhead">
    <div style="display:flex;align-items:center">${mark('sm')}<span class="tag">MEMBERS</span></div>
    <div class="who"><a class="ghost" href="/dashboard">All sessions</a></div>
  </div>
  <div class="mtitle">
    <h1>${escape(session.course ?? 'Practice session')}</h1>
    <div class="aside">${escape(session.started_at.slice(0, 16).replace('T', ' '))}<br>
      ${escape([session.environment, session.ball && `${session.ball} ball`, session.time_of_day].filter(Boolean).join(' · '))}</div>
  </div>
  <div class="mstats">
    ${tile(parsed.length, 'SHOTS THIS SESSION')}
    ${tile(rounded(longest?.carry_m, ' m'), 'LONGEST SHOT')}
    ${tile(kmh(fastest?.ball_speed_ms), 'KM/H FASTEST BALL')}
    ${tile(figure(session.holes_played, 0), 'HOLES FINISHED')}
    ${tile(`<span class="${cls}">${label}</span>`, 'AGAINST PAR')}
    ${tile(figure(session.course_par), 'COURSE PAR')}
  </div>
  <div class="pcharts" style="margin-top:34px">${planView(parsed)}${trajectoryView(parsed)}</div>
  <div style="margin-top:34px">
    <div class="kicker">EVERY SHOT</div>
    ${parsed.length ? `<div class="wrap-x"><table>
      <thead><tr><th>SHOT</th><th>CLUB</th><th>LIE</th><th>BALL SPEED</th><th>LAUNCH</th><th>OFFLINE</th>
        <th>CARRY</th><th>APEX</th><th>TO GREEN</th><th>SCORE</th></tr></thead>
      <tbody>${rows}</tbody></table></div>` : '<p class="empty">No shots in this session.</p>'}
  </div>
</main>
</body></html>`
}

/** DRIVER, MID_IRON → Driver, Mid iron. */
export function prettyClub(club) {
  if (!club) return ''
  const words = String(club).toLowerCase().split('_')
  return words.map((w, i) => (i ? w : w.charAt(0).toUpperCase() + w.slice(1))).join(' ')
}

// ---------------------------------------------------------------- privacy

/** Privacy policy — required for the Play listing; states what the app actually does with data. */
export function renderPrivacy() {
  const updated = '21 August 2026'
  return `${head('ShotArc — privacy')}
<body class="doc"><article class="in">
  <h1>Privacy</h1>
  <p class="upd">ShotArc · updated ${updated}</p>

  <p>ShotArc is a personal golf-shot tracker. This policy explains, plainly, what it does and does
     not do with your data.</p>

  <h2>The camera</h2>
  <p>ShotArc uses your phone's camera to watch the ball. The video is processed <strong>on your
     device, in real time</strong>, only to find and follow the ball. Camera frames are
     <strong>never uploaded, saved, or shared</strong> — nothing leaves the phone but the numbers
     described below.</p>

  <h2>What is collected</h2>
  <ul>
    <li>A random <strong>install identifier</strong> generated on your device the first time the app
        runs. It is not an advertising identifier, and it carries no name, email or phone number.
        If you pair the phone with a ShotArc account — by typing a code from your dashboard into
        the app — that identifier is linked to the username you chose, so your rounds can be shown
        back to you. Until you do that it is linked to nothing.</li>
    <li>For an account: the <strong>username and password</strong> you pick. The password is stored
        only as a salted scrypt hash, never as text. No email address is asked for or kept.</li>
    <li>Your <strong>device model and Android version</strong>, to understand what the app runs on.</li>
    <li><strong>Shot data you record</strong>: ball speed, launch angle, start line, carry, apex,
        score, the modelled shot path, and — if you pick a course — the hole and your score against
        par. Course choice is something you select in the app; ShotArc does not read your GPS
        location.</li>
  </ul>

  <h2>How it is used</h2>
  <p>Only to show your own rounds back to you on the ShotArc dashboard. Each account sees the
     rounds from the phones paired to it and nothing else. That is the entire purpose.</p>

  <h2>Where it goes</h2>
  <p>Shot data is sent to and stored on ShotArc's own server at shotarc.co.za. It is <strong>not
     sold</strong>, and <strong>not shared</strong> with any third party. There are no advertising
     or analytics SDKs in the app.</p>

  <h2>Keeping or deleting your data</h2>
  <p>You can ask for your data to be deleted at any time by emailing the address below; it will be
     removed from the server.</p>

  <h2>Children</h2>
  <p>ShotArc is a general-audience sports app and is not directed at children under 13.</p>

  <h2>Changes</h2>
  <p>If this policy changes, the date at the top will be updated.</p>

  <h2>Contact</h2>
  <p>Questions or deletion requests: <a href="mailto:dim2517@gmail.com">dim2517@gmail.com</a>.</p>

  <a class="back" href="/">← shotarc.co.za</a>
</article></body></html>`
}

// ---------------------------------------------------------------- sample data

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
        track: Array.from({ length: 9 }, (_, k) => { const f = k / 8; return [Math.round(carry * f), Math.round(apex * 4 * f * (1 - f))] }),
      })
      tlat = toLat; tlon = toLon
    })
    lat += 0.0006; lon += 0.0042      // next tee: a walk east, so the round reads landscape
  })
  return shots
}
