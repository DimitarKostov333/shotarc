# ShotArc

An Android app that watches a golf ball from behind the player, draws its flight over the camera
preview, and estimates ball speed, launch angle, start direction and a shot score. It works
outdoors on grass and indoors off a mat into a net.

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and an Android SDK with platform 36. `local.properties` points at the SDK.

For a build people can install from the web, sign it and ship it to the server:

```bash
keytool -genkeypair -v -keystore shotarc.jks -alias shotarc -keyalg RSA -keysize 2048 -validity 10000
# put golfKeystore / golfKeystorePassword / golfKeyAlias / golfKeyPassword in ~/.gradle/gradle.properties
./gradlew assembleRelease
./deploy/deploy.sh root@<vps-ip>
```

See [server/README.md](server/README.md) for what the VPS needs.

## Playing a round

Choosing **Outdoors** adds a fourth question: which course. The courses come from OpenStreetMap,
built into the app by `tools/build_courses.py`, and picking one shows its card — every hole with
its par and length — before you start.

During the round the app keeps the ball moving up the hole. The camera measures how each shot
left; where it comes down is modelled from that, so the hole panel always shows the distance to
the green, the shots taken and where you stand against par. **Holed** finishes the hole and moves
to the next tee.

The landing point is *modelled, not observed* — the camera sees a fifth of a second of flight and
nothing after it. A ballistic model with drag and the lift from backspin takes it from there, and
since spin cannot be seen from behind the ball, the club selection supplies the typical figure.
Checked against published TrackMan tour averages, the carries land within 2% for four of five
clubs and 8% for the fifth. Air is taken as sea level, so on the Highveld the model reads short by
roughly the 6–8% that thinner air gives you.

## Course data

`tools/build_courses.py` turns OpenStreetMap into the bundled `courses.json`, either from one
Overpass query for a region or from a Geofabrik extract for a whole country:

```bash
# a region, one query
curl -s -X POST --data-binary @holes.overpass https://overpass-api.de/api/interpreter -o holes.json
python3 tools/build_courses.py --overpass holes.json courses.json app/src/main/assets/courses.json

# a country, offline and re-runnable
wget https://download.geofabrik.de/africa/south-africa-latest.osm.pbf
python3 tools/build_courses.py --pbf south-africa-latest.osm.pbf app/src/main/assets/courses.json
```

Holes are `golf=hole` ways; par comes from the `par` tag, and from the hole's length where no
mapper filled it in (under 230 m a par 3, up to 430 m a par 4, longer a par 5 — those holes are
marked with an asterisk on the card). A hole belongs to the smallest course polygon containing it,
because club polygons enclose the individual courses; where the hole numbers repeat inside one
polygon the loops are walked apart green-to-next-tee.

**Coverage is the limit, not the code.** Gauteng has 225 mapped hole ways, which comes out as 10
playable courses, 8 of them a full 18. Par is tagged on 80% of them. Anything missing is missing
from OSM, and the fix is to draw it there — FairwayMapper takes about ten minutes a course and the
work helps everyone.

Course data is © OpenStreetMap contributors under ODbL. The extract shipped inside the APK is a
derived database, so that share-alike applies to `courses.json` itself; the app's own code is
unaffected, and the attribution shown on the course card is the visible half of the obligation.

## Using it

The app opens with three questions, asked once at the start of a session:

1. **Where are you playing** — outdoors or indoors. Indoor light is dimmer and casts colour, so
   both the brightness floor and the colour window open up, and the exposure is pushed less dark.
2. **What colour is the ball** — white (default), yellow, orange, neon green or red. This is the
   single most important answer: it is what the detector looks for.
3. **What time of day is it** — morning, noon or night. Low sun and floodlight dim the ball and
   drag its colour around, so this moves the brightness floor and widens the colour window.

Back steps through the questions again.

Then, on the camera screen:

1. Put the phone on a tripod a couple of metres behind the ball, lens pointing down the target
   line, ball in the lower half of the frame.
2. The ball must be sitting on grass — real turf or a hitting mat. The app will not arm until it
   sees green around the ball, which is what keeps it off every other yellow thing in the room.
3. Wait for the status to change to **Ball locked — hit it**. A green ring marks the ball.
4. Hit. The path is drawn as the ball flies; numbers appear when it leaves the frame, gets too
   small to see, or stops receding because it hit a net.
5. Pick the **club** and the **lie** you are hitting from. Both change how the shot is graded.
6. **Reset** clears the result. **Sensitivity** widens or narrows the colour threshold; **Debug**
   draws every ball-coloured blob the detector currently sees, which is the fastest way to find a
   setting that holds the ball and ignores everything else.

### Ball colour

Yellow, orange and red are unmistakable — no other colour on a course or in a garage sits near
them. Neon green is close enough to grass to need care, and is separated mostly by being far
brighter.

**White is the hard one, and it is the default.** A white ball has no colour at all: Cb and Cr both
sit at 128, exactly where concrete, paint, cloud and a white shirt sit. It is found by being bright
and colourless rather than by being any particular hue, which works well against grass and badly
against a bright overcast sky. If white is giving trouble, a yellow ball will track better in every
condition — that is a limit of the physics, not of the tuning.

### Club and lie

The club sets the launch window and ball speed the shot is graded against — a 25° launch at 40 m/s
is a fine short iron and a poor drive, and the app scores it accordingly. It is also used as a
sanity check: a speed no wedge could produce is reported as a wrong club rather than a shot.

The lie moves the same two expectations. Thick rough costs ball speed and throws the ball up
higher and less predictably, so it is graded more kindly on speed and more loosely on launch. A
tight lie off a putting green wants a flatter, cleaner strike.

### Shots that are not shots

A ball that never leaves the ground is a duffed shot, not a shot. If it comes out flat and stays
at the height it started, it is not registered and is reported as rolled — on any surface. Its
trace is still drawn, so you can see what happened.

### Indoors

Nothing to switch on — the same flow works in a garage or a bay, and the numbers are usually
*better* than outdoors because the ball is close and therefore large in frame. Three things are
handled for you:

- **The colour of the room.** Tungsten drags yellow towards orange and LED towards green, far
  enough that a fixed threshold can miss the ball completely. Once the ball is locked, the app
  rebuilds its colour window around what this particular light does to this particular ball. If
  the ball is not picked up at all to begin with, push **Sensitivity** up until it locks; the app
  narrows back in by itself from there.
- **The net.** A ball stopped by netting is still sitting there in plain view. The app ends the
  shot once the ball stops getting smaller, and measures only the part of the path where it was
  still flying away, so the netting cannot drag the speed down. Those shots are reported as cut
  short.
- **The short window.** A net three metres away is reached in about seven hundredths of a second,
  so the app asks the camera for the highest frame rate it offers (up to 120 fps) and starts
  tracking on the first frame the ball is seen off the tee rather than waiting to confirm.

A slightly darker exposure is requested on both cameras, which shortens the shutter and cuts the
motion blur that indoor light otherwise produces.

## How it works

**Finding the ball.** Every frame is scanned in YCbCr on the chroma planes, which are already half
resolution. A pixel counts as ball if it falls inside a circle in the Cb/Cr plane around the colour
in play and clears a brightness floor. The two numbers move together per colour: yellow sits at
(6, 133) where grass is at (96, 95) and a red flag at (85, 255), so it gets a wide window and a
high floor; red is unmistakable in colour but dark, so it gets a low floor; white sits at the
neutral point with everything else neutral, so it gets a tight window and the highest floor of all.
Surviving pixels are grouped into blobs by flood fill and filtered on area, fill ratio and aspect.

**Finding the grass.** The same pass marks grass, which is the mirror image of the yellow test:
neither red nor blue, so Cr below Cb and both under 128. Each blob then records how much of the
ring around it is grass. Only a blob ringed by grass can arm the tracker, which is what tells a
ball on a mat from a yellow bag on the floor two metres away — indoors that bag is often the
larger of the two.

**Measuring the ball.** A ball in flight smears into a streak, so its length is meaningless but
its width is not. Each blob is modelled as a stadium — a rectangle capped with half discs — which
recovers the same radius from a round ball and a smeared one:
`area = πr² + 2r(major − 2r)`. A fixed 0.375-chroma-pixel bias, measured against rendered discs,
accounts for the half cell of yellow a blob picks up around its edge.

That estimate is unbiased on average but lands within about a tenth of itself on any given frame,
depending on where the ball happens to sit inside the half-resolution chroma grid. For a round
blob big enough to be worth it, the radius is re-measured against the **full-resolution luma
plane** — four times the pixels, and the spread drops from about 10% to about 1%. This matters
enormously for the ball at address, because every distance in the shot is anchored to that one
number.

**Following it.** A small state machine: lock onto a stationary ball for 8 frames, call it a
launch when the tee goes empty for 2 frames, then associate blobs frame to frame against a
constant-velocity prediction. The gate is deliberately wide for the first re-acquisition — a
60 m/s strike clears a third of the frame in the two frames it takes to notice the tee is empty —
and tight afterwards.

**Calibrating at address.** A ball that is still, on the grass, and seen for eight frames is the
best-measured thing in the whole shot. The app takes two things from it: its radius, averaged
across those frames and refined against the luma plane, which fixes the launch distance; and its
colour in this light, which becomes the window used to track it in flight.

**Reconstructing the shot.** From behind, nearly all of the ball's motion is straight away from
the lens and barely moves in the frame, so depth cannot come from pixel motion. It comes from the
ball shrinking. A golf ball is 42.67 mm across, so `z = f·D/(2r)`, and `1/(2r)` is linear in time
while the ball flies away at constant speed. The launch distance is pinned to the calibrated
address radius and only the rate of recession is fitted, weighted by r² — a radius is measured to
a roughly constant *fraction* of itself, so a two-pixel ball late in flight says far less than an
eight-pixel one just off the tee. The lateral and vertical velocities follow from the image
positions scaled by that distance. `f` comes from the camera's reported focal length and sensor
size, falling back to an assumed 65° horizontal field of view (results are flagged as rough when
it does).

**Score** out of 100, weighted 40% start direction (falls off over 10° offline), 30% launch angle
(a Gaussian centred on the club's ideal, shifted and widened by the lie), 30% ball speed against
what that club can produce from that surface.

## Accuracy, honestly

Across five simulated shots — driver, wedge, pushed and pulled strikes outdoors, and a 42 m/s
strike into a net three metres away indoors — two of them rendered as camera frames and run
through the whole pipeline, recovered ball speed lands within 5.3% and launch angle within about
2°. Real footage will be worse: the model assumes the ball starts on the camera axis plane,
ignores drag over the measurement window, and takes impact to be the last frame the ball sat on
the tee — up to one frame of timing error.

Hard limits worth knowing:

- Depth resolution is set by the ball's apparent size, so a ball roughly 2 px wide is the end of
  useful tracking — around 15 m from the phone at 720p. Everything is measured from the first
  fifth of a second of flight, which is where the numbers come from anyway.
- Curve is only reported when the ball stayed in view long enough to turn.
- Apex is the height reached *in view*, and is marked as such when the ball was still climbing.
- Bright yellow objects behind the ball (a yellow flag, a jacket) can steal the track once it is
  airborne. The grass check only guards the lock-on, not the flight. Debug mode shows this
  immediately.
- Indoors the ball is often gone in five or six frames, which is enough for speed and launch angle
  but not for curve. Shape is only reported when the ball stayed in view long enough to turn.

The app asks for the highest frame rate the camera offers, up to 120 fps, and analyses at 720p.
That is deliberate: temporal samples matter more than pixels here, because everything is measured
from the first fifth of a second of flight, where the ball is still large. On a Galaxy S25 Ultra
that means 720p at the fastest rate the sensor will give, rather than 1080p at 30.

## Continuous integration

Every push runs the tests and builds a debug APK; a `v*` tag builds a signed release APK and
attaches it to a GitHub Release; a manual workflow deploys to the VPS. See
[.github/README.md](.github/README.md) for the workflows and the secrets they need.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

41 JVM tests. The detector is fed synthetic frames in real course colours — grass, a red flag, an
orange marker, sky, a grey stone, a mat under cool LED — and checked for both hits and false
positives, including a motion-blurred streak and a ball three pixels wide. Every ball colour is
found on grass; with four balls of different colours in one frame, each setting picks up only its
own. A white ball is separated from a grey stone by brightness alone, and a white ball in poor
light is found only once the session is set to night.

The tracker is fed physically simulated shots, outdoor and indoor, and checked against the launch
conditions it should recover, including that a net does not drag the speed down, that a ball off
the grass never arms it, and that a ball rolling along the ground is refused while a six-degree
stinger is not. Scoring is checked separately: the same strike graded as a short iron and as a
driver, and the same strike graded from rough and from fairway.

Two tests render whole shots as YUV frames and run detector and tracker together: a drive on
grass, and a garage bay — grey floor, green mat, a net three metres out, and a yellow bag on the
floor that is larger than the ball and would win the lock without the grass check. One more feeds
the detector an NV21-style interleaved-chroma frame through the real `ImageProxy` adapter, which
is the indexing most likely to be silently wrong.

## Layout

```
app/src/main/java/com/golfapp/tracker/
  Session.kt           where, which ball, what light — and what each does to the thresholds
  ShotSetup.kt         clubs, lies, and how a shot is scored against them
  BallDetector.kt      ball and grass thresholding, blob growing, radius estimation
  ShotTracker.kt       address calibration, launch detection, association, 3D reconstruction
  TrackOverlayView.kt  path and HUD drawing, image-to-view mapping
  SetupActivity.kt     the three start-of-session questions
  MainActivity.kt      CameraX wiring, camera intrinsics, club and lie pickers, results panel
```
