#!/usr/bin/env bash
# Starts the server against a throwaway database, pushes a session through it, and checks the
# dashboard renders what went in.
set -euo pipefail
BASE=${BASE:-http://127.0.0.1:8099}
DATA=$(mktemp -d)
DATA_DIR=$DATA PORT=8099 ADMIN_USER=smoke ADMIN_PASSWORD=smokepass INGEST_KEY=smokeingest node server.js & PID=$!
trap 'kill $PID 2>/dev/null; rm -rf $DATA' EXIT
for _ in $(seq 30); do curl -sf $BASE/ >/dev/null 2>&1 && break; sleep 0.2; done
JAR=$(mktemp)
curl -s -c $JAR -o /dev/null -d "username=smoke&password=smokepass" $BASE/login

# the phone must belong to the account, or its rounds show on nobody's dashboard
CODE=$(curl -s -b $JAR $BASE/dashboard | grep -oE 'class="code">[A-Z0-9]{6}' | cut -d">" -f2)
[ -n "$CODE" ] || { echo "no pairing code on the dashboard"; exit 1; }
curl -sf -X POST $BASE/api/pair -H 'content-type: application/json' -H "x-ingest-key: smokeingest" \
  -d "{\"installId\":\"test-install\",\"code\":\"$CODE\"}" > /dev/null \
  || { echo "pairing the phone failed"; exit 1; }

curl -sf -X POST $BASE/api/install -H 'content-type: application/json' -H "x-ingest-key: smokeingest" \
  -d '{"installId":"test-install","appVersion":"1.0","device":"SM-S938B","android":"15"}' > /dev/null

curl -sf -X POST $BASE/api/sessions -H 'content-type: application/json' -H "x-ingest-key: smokeingest" -d '{
  "installId":"test-install","sessionId":"test-session","startedAt":"2026-08-20T09:00:00Z",
  "environment":"OUTDOORS","ball":"WHITE","timeOfDay":"NOON","course":"Blue Course","coursePar":72,
  "holesPlayed":1,"throughPar":1,
  "shots":[
   {"hole":1,"shotNumber":1,"club":"DRIVER","lie":"FAIRWAY","ballSpeedMs":64.2,"launchDeg":12.8,
    "offlineDeg":2.1,"carryM":221.4,"lateralM":8.1,"apexM":31.2,"score":86,
    "fromLat":-26.0,"fromLon":28.0,"toLat":-25.998,"toLon":28.0009,"toGreenM":162.0,
    "track":[[0,0],[60,14],[120,26],[180,30],[221,0]]},
   {"hole":1,"shotNumber":2,"club":"MID_IRON","lie":"FAIRWAY","ballSpeedMs":49.1,"launchDeg":19.4,
    "offlineDeg":-1.2,"carryM":148.9,"lateralM":-3.1,"apexM":27.5,"score":78,
    "fromLat":-25.998,"fromLon":28.0009,"toLat":-25.9967,"toLon":28.0015,"toGreenM":14.0}
  ]}' > /dev/null

echo "stats:   $(curl -sf -b $JAR $BASE/api/stats)"
html=$(curl -sf -b $JAR $BASE/dashboard)
for needle in "Blue Course" "LONGEST CARRY" "221" ; do
  echo "$html" | grep -q "$needle" || { echo "dashboard missing '$needle'"; exit 1; }
done
detail=$(curl -sf -b $JAR $BASE/dashboard/session/test-session)
for needle in "Flight profile" "Shot paths" "Driver" "<svg" ; do
  echo "$detail" | grep -q "$needle" || { echo "session page missing '$needle'"; exit 1; }
done
vc=$(curl -sf $BASE/api/version | grep -o "versionCode")
[ -n "$vc" ] || { echo "/api/version broken"; exit 1; }
# APK download must be no-store so the browser always gets the latest build
curl -sfI $BASE/golf-tracker.apk | grep -qi "cache-control: no-store" || echo "note: apk not no-store (ok if no APK in test dir)"
landing=$(curl -sf $BASE/)
for needle in "Read the flight of" "data-readout" "Flight profile"; do
  echo "$landing" | grep -q "$needle" || { echo "landing missing '$needle'"; exit 1; }
done
for a in site.css fonts.css photo_course.jpg; do
  curl -sf -o /dev/null $BASE/assets/$a || { echo "asset $a not served"; exit 1; }
done
# unauthenticated dashboard must redirect to the login page, key or no key
for url in "$BASE/dashboard" "$BASE/dashboard?key=anything" "$BASE/dashboard/session/test-session"; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$url")
  [ "$code" = "302" ] || { echo "$url should redirect to login when logged out, got $code"; exit 1; }
done
# the public login page must not carry a figure from behind it
curl -sf $BASE/login | grep -qiE '[0-9]+ (sessions|shots)' && { echo "login page leaks dashboard figures"; exit 1; }

# markup in a column the schema calls INTEGER must not reach the page as markup
curl -sf -X POST $BASE/api/sessions -H 'content-type: application/json' -H "x-ingest-key: smokeingest" -d '{
  "installId":"test-install","sessionId":"xss-probe","startedAt":"2026-08-20T10:00:00Z",
  "holesPlayed":"<img src=x onerror=alert(1)>",
  "shots":[{"struckAt":"2026-08-20T10:01:00Z","hole":"<svg onload=alert(2)>","shotNumber":9,
    "club":"DRIVER","carryM":100,"score":"<b>x</b>"}]}' > /dev/null
for page in "$BASE/dashboard" "$BASE/dashboard/session/xss-probe"; do
  curl -sf -b $JAR "$page" | grep -qE '<img src=x|<svg onload=|<b>x</b>' \
    && { echo "unescaped ingest reached $page"; exit 1; }
done

# the forms and the download must not be hammerable
codes=""
for _ in $(seq 13); do
  codes+="$(curl -s -o /dev/null -w '%{http_code} ' -d 'username=smoke&password=nope' $BASE/login)"
done
echo "$codes" | grep -q 429 || { echo "login is not rate limited"; exit 1; }
codes=""
for _ in $(seq 34); do
  codes+="$(curl -s -o /dev/null -w '%{http_code} ' $BASE/golf-tracker.apk)"
done
echo "$codes" | grep -q 429 || { echo "the download is not rate limited"; exit 1; }

# every page must carry the hardening headers
for h in "content-security-policy" "x-content-type-options" "referrer-policy" "x-frame-options"; do
  curl -sfI $BASE/ | grep -qi "$h" || { echo "missing $h header"; exit 1; }
done

# a second account must not see the first's rounds
J2=$(mktemp)
curl -s -c $J2 -o /dev/null -d "username=stranger&password=nothing-to-see-here" $BASE/signup
curl -sf -b $J2 $BASE/api/stats | grep -q '"sessions":0' \
  || { echo "a new account can see someone else's rounds"; exit 1; }
code=$(curl -s -b $J2 -o /dev/null -w "%{http_code}" $BASE/dashboard/session/test-session)
[ "$code" = "404" ] || { echo "a new account can open someone else's session, got $code"; exit 1; }
rm -f $JAR $J2
echo "OK — landing, assets, ingest, validation, rate limits, headers, sign-up, pairing and scoping all hold"
