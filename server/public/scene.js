/* ShotArc hero: a looping 3D golf shot down a real-looking hole, seen from behind the ball. */
(function () {
  'use strict';
  var canvas = document.getElementById('scene');
  if (!canvas || typeof THREE === 'undefined') return;
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  var renderer;
  try { renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true }); }
  catch (e) { canvas.style.display = 'none'; return; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

  var HORIZON = 0xE7D8B4;
  var scene = new THREE.Scene();
  scene.background = new THREE.Color(HORIZON);
  scene.fog = new THREE.Fog(HORIZON, 45, 185);

  var camera = new THREE.PerspectiveCamera(55, 1, 0.1, 500);
  camera.position.set(0, 1.7, 9);
  camera.lookAt(0, 3.2, -60);

  scene.add(new THREE.HemisphereLight(0xf3e6c8, 0x33461f, 1.0));
  var sun = new THREE.DirectionalLight(0xffdca0, 1.85);
  sun.position.set(-22, 13, 8);
  scene.add(sun);

  // sky dome: soft blue up, warm haze at the horizon
  scene.add(new THREE.Mesh(
    new THREE.SphereGeometry(300, 24, 16),
    new THREE.ShaderMaterial({
      side: THREE.BackSide,
      uniforms: { top: { value: new THREE.Color(0x6E93AE) }, bot: { value: new THREE.Color(HORIZON) } },
      vertexShader: 'varying float h;void main(){h=normalize(position).y;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
      fragmentShader: 'varying float h;uniform vec3 top;uniform vec3 bot;void main(){gl_FragColor=vec4(mix(bot,top,clamp(h*0.9+0.15,0.0,1.0)),1.0);}'
    })
  ));

  function stripeTexture() {
    var c = document.createElement('canvas'); c.width = 4; c.height = 256;
    var g = c.getContext('2d');
    for (var i = 0; i < 256; i++) { g.fillStyle = (Math.floor(i / 14) % 2) ? '#2b7a41' : '#328a49'; g.fillRect(0, i, 4, 1); }
    var t = new THREE.CanvasTexture(c);
    t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(10, 60);
    return t;
  }

  // rough on both sides (darker, matte)
  var rough = new THREE.Mesh(
    new THREE.PlaneGeometry(600, 600),
    new THREE.MeshStandardMaterial({ color: 0x1c5027, roughness: 1 })
  );
  rough.rotation.x = -Math.PI / 2; rough.position.set(0, -0.02, -260);
  scene.add(rough);

  // the fairway: a mown strip running to the horizon
  var fairway = new THREE.Mesh(
    new THREE.PlaneGeometry(16, 560),
    new THREE.MeshStandardMaterial({ map: stripeTexture(), roughness: 0.95 })
  );
  fairway.rotation.x = -Math.PI / 2; fairway.position.set(0, 0, -260);
  scene.add(fairway);

  // a putting green with a flag, out where a good drive finishes
  var green = new THREE.Mesh(
    new THREE.CircleGeometry(7, 40),
    new THREE.MeshStandardMaterial({ color: 0x57b25b, roughness: 0.7 })
  );
  green.rotation.x = -Math.PI / 2; green.position.set(3, 0.01, -132);
  scene.add(green);
  var pole = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, 5, 6),
    new THREE.MeshStandardMaterial({ color: 0xf0f0f0 }));
  pole.position.set(3, 2.5, -132); scene.add(pole);
  var flag = new THREE.Mesh(new THREE.PlaneGeometry(2.2, 1.1),
    new THREE.MeshStandardMaterial({ color: 0xe23b3b, side: THREE.DoubleSide }));
  flag.position.set(4.05, 4.3, -132); scene.add(flag);

  // a fairway bunker for texture
  var bunker = new THREE.Mesh(new THREE.CircleGeometry(3.4, 24),
    new THREE.MeshStandardMaterial({ color: 0xdac48a, roughness: 1 }));
  bunker.rotation.x = -Math.PI / 2; bunker.position.set(-7, 0.02, -74); bunker.scale.set(1.5, 1, 1);
  scene.add(bunker);

  // --- soft rolling hills on the horizon (kept clean — no fussy trees)
  var hillMat = new THREE.MeshStandardMaterial({ color: 0x24502f, roughness: 1 });
  var farMat = new THREE.MeshStandardMaterial({ color: 0x2b5836, roughness: 1 });
  function hill(x, z, w, h, d, mat) {
    var m = new THREE.Mesh(new THREE.SphereGeometry(1, 28, 18), mat);
    m.scale.set(w, h, d); m.position.set(x, -h * 0.58, z);
    return m;
  }
  var hills = new THREE.Group();
  hills.add(hill(-58, -168, 70, 22, 40, hillMat));
  hills.add(hill(28, -182, 96, 30, 46, farMat));
  hills.add(hill(104, -164, 66, 20, 36, hillMat));
  hills.add(hill(-120, -178, 88, 26, 42, farMat));
  hills.add(hill(-10, -150, 54, 15, 30, hillMat));
  scene.add(hills);

  // --- ball, arc, shots (as before)
  var ball = new THREE.Mesh(new THREE.SphereGeometry(0.42, 32, 24),
    new THREE.MeshStandardMaterial({ color: 0xFBF6EA, roughness: 0.4, emissive: 0x1a1710 }));
  scene.add(ball);
  var arcMat = new THREE.MeshBasicMaterial({ color: 0xE7C572 });
  var glowMat = new THREE.MeshBasicMaterial({ color: 0xE7C572, transparent: true, opacity: 0.20 });
  var arcMesh = null, glowMesh = null, arcCount = 0;

  var shots = [
    { mph: 152, launch: 13.2, carry: 258, apex: 32, lateral: -9, score: 87, shape: 'Draw' },
    { mph: 118, launch: 18.6, carry: 172, apex: 30, lateral: 0, score: 91, shape: 'Straight' },
    { mph: 145, launch: 12.1, carry: 241, apex: 27, lateral: 13, score: 79, shape: 'Fade' }
  ];
  var U = 0.235;

  function buildCurve(shot) {
    var pts = [], n = 40;
    for (var i = 0; i <= n; i++) {
      var u = i / n;
      var height = shot.apex * 4 * u * (1 - u);
      var lat = shot.lateral * Math.pow(u, 1.6);
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
    arcMesh = new THREE.Mesh(tube, arcMat); glowMesh = new THREE.Mesh(glow, glowMat);
    arcCount = tube.index.count;
    tube.setDrawRange(0, 0); glow.setDrawRange(0, 0);
    scene.add(glowMesh); scene.add(arcMesh);
    setHud(shot, 0, 'lock');
  }

  var hud = document.getElementById('hud');
  var elStatus = document.getElementById('hud-status'), elSpeed = document.getElementById('hud-speed');
  var elLaunch = document.getElementById('hud-launch'), elCarry = document.getElementById('hud-carry');
  var elScore = document.getElementById('hud-score');
  function setHud(shot, prog, phase) {
    if (phase === 'lock') {
      elStatus.textContent = 'Ball locked'; elStatus.style.color = '#BBCBB2';
      elSpeed.textContent = elLaunch.textContent = elCarry.textContent = '—'; elScore.textContent = '';
      hud.classList.add('show');
    } else if (phase === 'flight') {
      elStatus.textContent = 'Tracking'; elStatus.style.color = '#D8B36A';
      elSpeed.textContent = Math.round(shot.mph * prog) + ' mph';
      elLaunch.textContent = (shot.launch * prog).toFixed(1) + '°';
      elCarry.textContent = Math.round(shot.carry * prog) + ' m'; elScore.textContent = '';
    } else {
      elStatus.textContent = shot.shape + ' · captured'; elStatus.style.color = '#BBCBB2';
      elSpeed.textContent = shot.mph + ' mph'; elLaunch.textContent = shot.launch.toFixed(1) + '°';
      elCarry.textContent = shot.carry + ' m';
      elScore.textContent = shot.score + ' · ' + grade(shot.score);
    }
  }
  function grade(s) { return s >= 85 ? 'Excellent' : s >= 70 ? 'Good' : s >= 55 ? 'Fair' : 'Loose'; }

  var ADDRESS = 1.3, FLIGHT = 2.3, HOLD = 2.0, TOTAL = ADDRESS + FLIGHT + HOLD;
  var t0 = null;
  function resize() {
    var w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== w || canvas.height !== h) renderer.setSize(w, h, false);
    camera.aspect = w / h; camera.updateProjectionMatrix();
  }

  function frame(now) {
    if (t0 === null) t0 = now;
    var local = (now - t0) / 1000, cycle = local % TOTAL, idx = Math.floor(local / TOTAL) % shots.length;
    if (idx !== shotIndex) { shotIndex = idx; loadShot(idx); }
    var shot = shots[idx];
    resize();
    if (cycle < ADDRESS) { ball.position.copy(curve.getPointAt(0)); setHud(shot, 0, 'lock'); }
    else if (cycle < ADDRESS + FLIGHT) {
      var p = (cycle - ADDRESS) / FLIGHT, e = p * p * (3 - 2 * p);
      ball.position.copy(curve.getPointAt(e));
      var draw = Math.floor(arcCount * e);
      arcMesh.geometry.setDrawRange(0, draw); glowMesh.geometry.setDrawRange(0, draw);
      setHud(shot, e, 'flight');
    } else {
      ball.position.copy(curve.getPointAt(1));
      arcMesh.geometry.setDrawRange(0, arcCount); glowMesh.geometry.setDrawRange(0, arcCount);
      setHud(shot, 1, 'done');
    }
    flag.rotation.y = Math.sin(local * 2) * 0.3;                 // flag flutter
    camera.position.x = Math.sin(local * 0.22) * 0.7;
    camera.lookAt(0, 3.2, -60);
    renderer.render(scene, camera);
    if (!reduce) requestAnimationFrame(frame);
  }

  if (reduce) {
    shotIndex = 0; loadShot(0); ball.position.copy(curve.getPointAt(1));
    arcMesh.geometry.setDrawRange(0, arcCount); glowMesh.geometry.setDrawRange(0, arcCount);
    setHud(shots[0], 1, 'done'); resize(); renderer.render(scene, camera);
  } else { requestAnimationFrame(frame); }
  window.addEventListener('resize', function () { resize(); if (reduce) renderer.render(scene, camera); });
})();
