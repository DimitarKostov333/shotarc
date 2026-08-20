#!/usr/bin/env bash
# Starts the server against a throwaway database, pushes a session through it, and checks the
# dashboard renders what went in.
set -euo pipefail
BASE=${BASE:-http://127.0.0.1:8099}
DATA=$(mktemp -d)
DATA_DIR=$DATA PORT=8099 ADMIN_USER=smoke ADMIN_PASSWORD=smokepass node server.js & PID=$!
trap 'kill $PID 2>/dev/null; rm -rf $DATA' EXIT
for _ in $(seq 30); do curl -sf $BASE/ >/dev/null 2>&1 && break; sleep 0.2; done
JAR=$(mktemp)
curl -s -c $JAR -o /dev/null -d "username=smoke&password=smokepass" $BASE/login

curl -sf -X POST $BASE/api/install -H 'content-type: application/json' \
  -d '{"installId":"test-install","appVersion":"1.0","device":"SM-S938B","android":"15"}' > /dev/null

curl -sf -X POST $BASE/api/sessions -H 'content-type: application/json' -d '{
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
for needle in "Blue Course" "Longest carry" "221" ; do
  echo "$html" | grep -q "$needle" || { echo "dashboard missing '$needle'"; exit 1; }
done
detail=$(curl -sf -b $JAR $BASE/dashboard/session/test-session)
for needle in "Flight profile" "Shot paths" "DRIVER" "<svg" ; do
  echo "$detail" | grep -q "$needle" || { echo "session page missing '$needle'"; exit 1; }
done
landing=$(curl -sf $BASE/)
for needle in "See every shot" "/assets/scene.js" "Flight profile"; do
  echo "$landing" | grep -q "$needle" || { echo "landing missing '$needle'"; exit 1; }
done
for a in site.css scene.js three.min.js; do
  curl -sf -o /dev/null $BASE/assets/$a || { echo "asset $a not served"; exit 1; }
done
# unauthenticated dashboard must redirect to the login page
code=$(curl -s -o /dev/null -w "%{http_code}" $BASE/dashboard)
[ "$code" = "302" ] || { echo "dashboard should redirect to login when logged out, got $code"; exit 1; }
rm -f $JAR
echo "OK — landing, assets, ingest, login, dashboard and session view all respond"
