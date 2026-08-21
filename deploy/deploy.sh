#!/usr/bin/env bash
# Ships the ShotArc server and APK to the VPS, alongside the nginx site already running there.
#
#   ./deploy/deploy.sh                 # uses the shotarc-vps ssh host
#   ./deploy/deploy.sh root@1.2.3.4    # or an explicit target
#   ./deploy/deploy.sh --server-only   # site and API only, leaving the published APK alone
#
# The box already runs nginx, so this adds a server block and uses certbot for TLS rather than
# touching ports directly. It never edits the existing site. Node 20+ must be installed
# (see deploy/provision.sh for a one-time setup helper).
set -euo pipefail

SERVER_ONLY=false
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --server-only) SERVER_ONLY=true ;;
    *) ARGS+=("$arg") ;;
  esac
done
HOST=${ARGS[0]:-shotarc-vps}

if [ "$SERVER_ONLY" = false ]; then
  APK=${APK:-app/build/outputs/apk/release/app-release.apk}
  # An unsigned release next to no signed one means assembleRelease ran without the keystore.
  # Falling through to the debug APK would publish a build signed by a different certificate, and
  # Android refuses to update across certificates — everyone already carrying the app would be
  # stuck until they uninstalled it. Stop instead.
  if [ ! -f "$APK" ] && [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
    echo "release APK is unsigned — build it with the keystore:"
    echo "  ./gradlew :app:assembleRelease -PgolfKeystore=... -PgolfKeystorePassword=... \\"
    echo "      -PgolfKeyAlias=... -PgolfKeyPassword=..."
    echo "(or pass APK=<path> to publish a specific file, or --server-only to skip the APK)"
    exit 1
  fi
  [ -f "$APK" ] || APK=app/build/outputs/apk/debug/app-debug.apk
  [ -f "$APK" ] || { echo "no APK built — run ./gradlew assembleRelease or assembleDebug"; exit 1; }
  case "$APK" in
    *app-debug.apk) echo "warning: publishing a DEBUG-signed APK; installs of the release build cannot update to it" ;;
  esac
fi

echo "→ preflight"
ssh "$HOST" 'command -v node >/dev/null || { echo "node not installed — run deploy/provision.sh first"; exit 1; }
  command -v nginx >/dev/null || { echo "nginx not found"; exit 1; }
  id -u shotarc >/dev/null 2>&1 || useradd --system --home /var/lib/shotarc --shell /usr/sbin/nologin shotarc
  mkdir -p /opt/shotarc/server /var/lib/shotarc
  chown -R shotarc:shotarc /var/lib/shotarc'

echo "→ server code"
rsync -az --delete --exclude node_modules --exclude data server/ "$HOST:/opt/shotarc/server/"

if [ "$SERVER_ONLY" = true ]; then
  echo "→ APK left as published (--server-only)"
else
  echo "→ APK ($(du -h "$APK" | cut -f1))"
  rsync -az "$APK" "$HOST:/var/lib/shotarc/golf-tracker.apk"
fi

# publish the version so the site and the app can tell what the latest build is
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
if [ "$SERVER_ONLY" = false ] && [ -n "$AAPT" ]; then
  VINFO=$("$AAPT" dump badging "$APK" 2>/dev/null | grep -oE "versionCode='[0-9]*' versionName='[^']*'")
  VCODE=$(echo "$VINFO" | sed -E "s/.*versionCode='([0-9]*)'.*/\1/")
  VNAME=$(echo "$VINFO" | sed -E "s/.*versionName='([^']*)'.*/\1/")
  printf '{"versionCode":%s,"versionName":"%s"}\n' "${VCODE:-0}" "${VNAME:-unknown}" > /tmp/shotarc-version.json
  rsync -az /tmp/shotarc-version.json "$HOST:/var/lib/shotarc/version.json"
  echo "→ version $VNAME ($VCODE)"
fi

echo "→ systemd unit + nginx site + secrets"
scp -q deploy/shotarc.service "$HOST:/etc/systemd/system/shotarc.service"
# The nginx site is installed once. After that certbot owns it (it adds the TLS block to the same
# file), so re-copying would wipe HTTPS. To change it deliberately, edit on the box or remove it
# first and re-run certbot.
ssh "$HOST" 'test -f /etc/nginx/sites-available/shotarc.conf' \
  && echo "  nginx site already present — left as-is (certbot-managed)" \
  || scp -q deploy/nginx-shotarc.conf "$HOST:/etc/nginx/sites-available/shotarc.conf"
if [ -f deploy/shotarc.env ]; then
  scp -q deploy/shotarc.env "$HOST:/etc/shotarc.env"
  ssh "$HOST" 'chmod 600 /etc/shotarc.env'
fi

echo "→ install deps and (re)start"
ssh "$HOST" 'set -e
  cd /opt/shotarc/server && npm install --omit=dev --no-audit --no-fund
  chown -R shotarc:shotarc /opt/shotarc
  if [ -f /var/lib/shotarc/golf-tracker.apk ]; then chown shotarc:shotarc /var/lib/shotarc/golf-tracker.apk; fi
  ln -sf /etc/nginx/sites-available/shotarc.conf /etc/nginx/sites-enabled/shotarc.conf
  systemctl daemon-reload
  systemctl enable --now shotarc
  systemctl restart shotarc
  nginx -t && systemctl reload nginx
  sleep 1
  echo "--- shotarc service:"; systemctl --no-pager --lines=3 status shotarc | tail -4
  echo "--- local health:"
  curl -sf http://127.0.0.1:8080/api/version && echo
  echo "--- dashboard is behind the login:"
  curl -so /dev/null -w "  /dashboard -> %{http_code}\n" http://127.0.0.1:8080/dashboard'

echo
echo "→ HTTP is live. For HTTPS, once shotarc.co.za points at this box, run:"
echo "    ssh $HOST 'certbot --nginx -d shotarc.co.za -d www.shotarc.co.za --non-interactive --agree-tos -m you@example.com'"
