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

  var HORIZON = 0x9fc7d8;
  var scene = new THREE.Scene();
  scene.background = new THREE.Color(HORIZON);
  scene.fog = new THREE.Fog(HORIZON, 60, 210);

  var camera = new THREE.PerspectiveCamera(55, 1, 0.1, 500);
  camera.position.set(0, 1.7, 9);
  camera.lookAt(0, 3.2, -60);

  scene.add(new THREE.HemisphereLight(0xdff1ff, 0x2c4a24, 1.05));
  var sun = new THREE.DirectionalLight(0xfff4e0, 1.7);
  sun.position.set(-16, 26, 10);
  scene.add(sun);

  // sky dome: soft blue up, warm haze at the horizon
  scene.add(new THREE.Mesh(
    new THREE.SphereGeometry(300, 24, 16),
    new THREE.ShaderMaterial({
      side: THREE.BackSide,
      uniforms: { top: { value: new THREE.Color(0x3f7fb8) }, bot: { value: new THREE.Color(HORIZON) } },
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

  // --- trees, lining the hole and massing on the horizon
  var trunkMat = new THREE.MeshStandardMaterial({ color: 0x5b3f2a, roughness: 1 });
  var leafMats = [0x1f5a2e, 0x246b34, 0x2c7d3c, 0x35502a].map(function (c) {
    return new THREE.MeshStandardMaterial({ color: c, roughness: 1 });
  });
  var coneGeo = new THREE.ConeGeometry(1, 1, 7);
  var trunkGeo = new THREE.CylinderGeometry(0.12, 0.16, 1, 5);

  function tree(x, z, s) {
    var g = new THREE.Group();
    var t = new THREE.Mesh(trunkGeo, trunkMat); t.scale.set(s, s * 1.4, s); t.position.y = s * 0.7;
    g.add(t);
    var lm = leafMats[(Math.random() * leafMats.length) | 0];
    for (var i = 0; i < 3; i++) {
      var c = new THREE.Mesh(coneGeo, lm);
      var w = s * (2.2 - i * 0.5);
      c.scale.set(w, s * (2.4 - i * 0.4), w);
      c.position.y = s * (1.4 + i * 1.15);
      g.add(c);
    }
    g.position.set(x, 0, z);
    g.rotation.y = Math.random() * Math.PI;
    return g;
  }

  var forest = new THREE.Group();
  // side lines down the hole
  for (var z = -8; z > -175; z -= 5.5 + Math.random() * 3) {
    var jx = 1.6 * Math.random();
    forest.add(tree(-10 - jx - Math.random() * 4, z, 1.2 + Math.random() * 1.1));
    forest.add(tree(10 + jx + Math.random() * 4, z, 1.2 + Math.random() * 1.1));
  }
  // a denser band across the back — the treeline horizon
  for (var x2 = -95; x2 < 95; x2 += 4 + Math.random() * 3) {
    forest.add(tree(x2, -180 - Math.random() * 22, 2 + Math.random() * 2.2));
  }
  scene.add(forest);

  // --- ball, arc, shots (as before)
  var ball = new THREE.Mesh(new THREE.SphereGeometry(0.42, 32, 24),
    new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.35, emissive: 0x222222 }));
  scene.add(ball);
  var arcMat = new THREE.MeshBasicMaterial({ color: 0xe8ff00 });
  var glowMat = new THREE.MeshBasicMaterial({ color: 0xe8ff00, transparent: true, opacity: 0.22 });
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
      elStatus.textContent = 'Ball locked — hit it'; elStatus.style.color = '#7ee787';
      elSpeed.textContent = elLaunch.textContent = elCarry.textContent = '—'; elScore.textContent = '';
      hud.classList.add('show');
    } else if (phase === 'flight') {
      elStatus.textContent = 'Tracking…'; elStatus.style.color = '#e8ff00';
      elSpeed.textContent = Math.round(shot.mph * prog) + ' mph';
      elLaunch.textContent = (shot.launch * prog).toFixed(1) + '°';
      elCarry.textContent = Math.round(shot.carry * prog) + ' m'; elScore.textContent = '';
    } else {
      elStatus.textContent = 'Shot captured · ' + shot.shape; elStatus.style.color = '#7ee787';
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
