# ShotArc — store listing pack

Everything needed to publish to **Google Play** (and, with the same assets,
Samsung Galaxy Store). The upload itself needs your Google account and the
one-time US$25 developer registration — that part is yours; everything below is
prepared.

## Upload artifact
- **app-release.aab** — the signed Android App Bundle. Upload this (Play prefers
  AAB over APK). It is signed with your keystore `/opt/shotarc-release.jks`
  (SHA-256 `57a477…`). Version **1.0.18** (versionCode 18).
- If you enrol in **Play App Signing** (recommended, the default), Google
  re-signs for distribution and your upload key is the keystore you already
  have — keep backing it up.

## Store listing text

**App name (max 30):**
```
ShotArc
```

**Short description (max 80):**
```
Track your golf ball from behind — speed, launch, carry and a shot score.
```

**Full description (max 4000):**
```
ShotArc turns the camera you already carry into a ball tracker. Stand your
phone behind the ball, take your shot, and ShotArc follows the ball off the
face, traces its flight, and reads back the numbers that matter.

No launch monitor. No sensors on the club. Just your phone.

WHAT YOU GET AFTER EVERY SHOT
• Ball speed (km/h)
• Launch angle and start line
• Carry distance in metres
• A score for the quality of the strike
• The shape of the shot — draw, fade, or a slice you’d rather forget

INDOORS OR OUT
Hit off the grass on the course or the range, or into a net in a bay or your
garden. ShotArc adapts to the light — morning, noon, or under floodlights — and
to your ball, whether it’s white, yellow, orange, neon green or red.

PLAY A ROUND
Pick a course and ShotArc walks your ball up each hole, shows the distance to
the green, and keeps your score against par.

SEE IT ALL AFTERWARDS
Every session syncs to your private dashboard at shotarc.co.za: the path of
every shot down each hole, its flight in profile, your longest drive and your
score — yours to revisit.

HONEST ABOUT THE NUMBERS
ShotArc measures the launch from a fifth of a second of flight and models the
rest with real ball physics. It’s a practice aid, not a certified launch
monitor — but it’s the measurement most players never had, from the phone in
their pocket.

Course data © OpenStreetMap contributors.
```

**Category:** Sports
**Tags / keywords:** golf, launch monitor, ball tracking, golf practice, driving range, shot tracer
**Content rating:** Everyone (complete the IARC questionnaire; no objectionable content)
**Contact email:** dim2517@gmail.com
**Website:** https://shotarc.co.za
**Privacy policy:** https://shotarc.co.za/privacy

## Graphics (all included)
- **store_icon.png** — 512×512 app icon.
- **feature.png** — 1024×500 feature graphic.
- **Screenshots** (phone, 1080×2340): 01-where, 02-ball, 03-time, 04-course,
  05-splash. Play needs 2–8; these five are the setup flow. Worth adding a real
  on-course capture once you’ve used it outdoors.

## Data safety form (Play → App content → Data safety)
Answer it like this — it matches what the app actually does:
- **Does your app collect or share user data?** Yes (collects, does not share).
- **Data types collected:**
  - *Device or other IDs* — a random install ID the app generates (not the
    advertising ID, not tied to identity). Purpose: App functionality.
  - *App activity / app info* — your recorded shot stats and scores. Purpose:
    App functionality.
  - *Device model & OS version.* Purpose: App functionality.
- **Personal info, location, contacts, messages, photos/videos, audio:** none
  collected. (The camera is used live on-device; frames are never uploaded or
  stored — declare no photo/video collection.)
- **Is data shared with third parties?** No.
- **Is data encrypted in transit?** Yes (HTTPS).
- **Can users request deletion?** Yes — by email.
- **Permissions:** CAMERA (to see the ball), INTERNET (to sync to the
  dashboard).

## First-release flow on Play (recommended)
1. Play Console → Create app → name ShotArc, free, app (not game… or Game>Sports
   if you prefer), declarations.
2. **Internal testing** track → create release → upload app-release.aab →
   add testers by email → share the opt-in link. Testers install **through
   Play**, so there is **no “unsafe app” warning**.
3. Fill Store listing (text + graphics above), Data safety, Content rating,
   Privacy policy URL.
4. Promote to Closed/Open/Production when you’re ready for a wider audience.

## Samsung Galaxy Store (optional, good for S25 Ultra users)
Register at seller.samsungapps.com (free). You can upload the **APK**
(golf-tracker.apk from the site, or build one) rather than an AAB. Reuse the
same icon, feature graphic, screenshots and description.
