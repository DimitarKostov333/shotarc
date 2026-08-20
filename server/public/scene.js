/* ShotArc hero: a looping 3D golf shot seen from behind the ball, the way the app sees it. */
(function () {
  'use strict';
  var canvas = document.getElementById('scene');
  if (!canvas || typeof THREE === 'undefined') return;

  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  var renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, alpha: false });
  } catch (e) { canvas.style.display = 'none'; return; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

  var scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0b1410);
  scene.fog = new THREE.Fog(0x0b1410, 40, 150);

  var camera = new THREE.PerspectiveCamera(52, 1, 0.1, 400);
  camera.position.set(0, 2.4, 8);
  camera.lookAt(0, 5, -50);

  // light
  scene.add(new THREE.HemisphereLight(0xbfe6ff, 0x14301c, 1.05));
  var sun = new THREE.DirectionalLight(0xffffff, 1.6);
  sun.position.set(-12, 22, 6);
  scene.add(sun);

  // sky dome (simple vertical gradient)
  var sky = new THREE.Mesh(
    new THREE.SphereGeometry(220, 24, 16),
    new THREE.ShaderMaterial({
      side: THREE.BackSide,
      uniforms: { top: { value: new THREE.Color(0x0a1a2a) }, bot: { value: new THREE.Color(0x123021) } },
      vertexShader: 'varying float h; void main(){ h = normalize(position).y; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
      fragmentShader: 'varying float h; uniform vec3 top; uniform vec3 bot; void main(){ gl_FragColor = vec4(mix(bot, top, clamp(h*0.5+0.4,0.0,1.0)), 1.0); }'
    })
  );
  scene.add(sky);

  // fairway with mowing stripes, running into the distance
  var tex = (function () {
    var c = document.createElement('canvas'); c.width = 8; c.height = 256;
    var g = c.getContext('2d');
    for (var i = 0; i < 256; i++) { g.fillStyle = (Math.floor(i / 16) % 2) ? '#1f5a30' : '#256a38'; g.fillRect(0, i, 8, 1); }
    var t = new THREE.CanvasTexture(c);
    t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(40, 40);
    return t;
  })();
  var ground = new THREE.Mesh(
    new THREE.PlaneGeometry(600, 600),
    new THREE.MeshStandardMaterial({ map: tex, roughness: 1, metalness: 0 })
  );
  ground.rotation.x = -Math.PI / 2;
  ground.position.z = -260;
  scene.add(ground);

  // ball + a soft glow sprite
  var ball = new THREE.Mesh(
    new THREE.SphereGeometry(0.42, 32, 24),
    new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.35, emissive: 0x222222 })
  );
  scene.add(ball);

  // the traced arc, revealed as the ball flies
  var arcMat = new THREE.MeshBasicMaterial({ color: 0xe8ff00 });
  var glowMat = new THREE.MeshBasicMaterial({ color: 0xe8ff00, transparent: true, opacity: 0.22 });
  var arcMesh = null, glowMesh = null, arcCount = 0;

  // three shots to cycle through: [ballSpeedMph, launchDeg, carryM, apexM, lateralM(+right), score, statusScore]
  var shots = [
    { mph: 152, launch: 13.2, carry: 258, apex: 32, lateral: -9, score: 87, shape: 'Draw' },
    { mph: 118, launch: 18.6, carry: 172, apex: 30, lateral: 0, score: 91, shape: 'Straight' },
    { mph: 145, launch: 12.1, carry: 241, apex: 27, lateral: 13, score: 79, shape: 'Fade' }
  ];
  var U = 0.235;            // metres -> scene units (258 m ~ 60 units deep)

  function buildCurve(shot) {
    var pts = [];
    var n = 40;
    for (var i = 0; i <= n; i++) {
      var u = i / n;
      var height = shot.apex * 4 * u * (1 - u);           // parabola, apex at mid
      var lat = shot.lateral * Math.pow(u, 1.6);          // curve bends late, like real shape
      pts.push(new THREE.Vector3(lat * U, Math.max(0, height) * U * 0.9 + 0.42, -shot.carry * u * U));
    }
    return new THREE.CatmullRomCurve3(pts);
  }

  var curve = null, shotIndex = -1;
  function loadShot(idx) {
    var shot = shots[idx];
    curve = buildCurve(shot);
    if (arcMesh) { scene.remove(arcMesh); arcMesh.geometry.dispose(); }
    if (glowMesh) { scene.remove(glowMesh); glowMesh.geometry.dispose(); }
    var tube = new THREE.TubeGeometry(curve, 120, 0.16, 8, false);
    var glow = new THREE.TubeGeometry(curve, 120, 0.42, 8, false);
    arcMesh = new THREE.Mesh(tube, arcMat);
    glowMesh = new THREE.Mesh(glow, glowMat);
    arcCount = tube.index.count;
    tube.setDrawRange(0, 0); glow.setDrawRange(0, 0);
    scene.add(glowMesh); scene.add(arcMesh);
    setHud(shot, 0, 'lock');
  }

  // HUD
  var hud = document.getElementById('hud');
  var elStatus = document.getElementById('hud-status');
  var elSpeed = document.getElementById('hud-speed');
  var elLaunch = document.getElementById('hud-launch');
  var elCarry = document.getElementById('hud-carry');
  var elScore = document.getElementById('hud-score');
  function setHud(shot, prog, phase) {
    if (phase === 'lock') {
      elStatus.textContent = 'Ball locked — hit it'; elStatus.style.color = '#7ee787';
      elSpeed.textContent = elLaunch.textContent = elCarry.textContent = '—'; elScore.textContent = '';
      hud.classList.add('show');
    } else if (phase === 'flight') {
      elStatus.textContent = 'Tracking…'; elStatus.style.color = '#e8ff00';
      elSpeed.textContent = Math.round(shot.mph * prog) + ' mph';
      elLaunch.textContent = (shot.launch * prog).toFixed(1) + '°';
      elCarry.textContent = Math.round(shot.carry * prog) + ' m';
      elScore.textContent = '';
    } else {
      elStatus.textContent = 'Shot captured · ' + shot.shape; elStatus.style.color = '#7ee787';
      elSpeed.textContent = shot.mph + ' mph';
      elLaunch.textContent = shot.launch.toFixed(1) + '°';
      elCarry.textContent = shot.carry + ' m';
      elScore.textContent = shot.score + ' · ' + grade(shot.score);
    }
  }
  function grade(s) { return s >= 85 ? 'Excellent' : s >= 70 ? 'Good' : s >= 55 ? 'Fair' : 'Loose'; }

  // timeline
  var ADDRESS = 1.3, FLIGHT = 2.3, HOLD = 2.0, TOTAL = ADDRESS + FLIGHT + HOLD;
  var t0 = null;

  function resize() {
    var w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== w || canvas.height !== h) renderer.setSize(w, h, false);
    camera.aspect = w / h; camera.updateProjectionMatrix();
  }

  function frame(now) {
    if (t0 === null) t0 = now;
    var local = ((now - t0) / 1000);
    var cycle = local % TOTAL;
    var idx = Math.floor(local / TOTAL) % shots.length;
    if (idx !== shotIndex) { shotIndex = idx; loadShot(idx); }
    var shot = shots[idx];

    resize();

    if (cycle < ADDRESS) {
      ball.position.copy(curve.getPointAt(0));
      setHud(shot, 0, 'lock');
    } else if (cycle < ADDRESS + FLIGHT) {
      var p = (cycle - ADDRESS) / FLIGHT;
      var e = p * p * (3 - 2 * p);                 // smoothstep
      ball.position.copy(curve.getPointAt(e));
      var draw = Math.floor(arcCount * e);
      arcMesh.geometry.setDrawRange(0, draw);
      glowMesh.geometry.setDrawRange(0, draw);
      setHud(shot, e, 'flight');
    } else {
      ball.position.copy(curve.getPointAt(1));
      arcMesh.geometry.setDrawRange(0, arcCount);
      glowMesh.geometry.setDrawRange(0, arcCount);
      setHud(shot, 1, 'done');
    }

    // gentle camera drift for life
    camera.position.x = Math.sin(local * 0.25) * 0.6;
    camera.lookAt(0, 5, -50);

    renderer.render(scene, camera);
    if (!reduce) requestAnimationFrame(frame);
  }

  if (reduce) {
    // one static, fully-drawn shot
    shotIndex = 0; loadShot(0);
    ball.position.copy(curve.getPointAt(1));
    arcMesh.geometry.setDrawRange(0, arcCount); glowMesh.geometry.setDrawRange(0, arcCount);
    setHud(shots[0], 1, 'done');
    resize(); renderer.render(scene, camera);
  } else {
    requestAnimationFrame(frame);
  }
  window.addEventListener('resize', function () { resize(); if (reduce) renderer.render(scene, camera); });
})();
