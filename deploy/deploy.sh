#!/usr/bin/env bash
# Ships the ShotArc server and APK to the VPS, alongside the nginx site already running there.
#
#   ./deploy/deploy.sh                 # uses the shotarc-vps ssh host
#   ./deploy/deploy.sh root@1.2.3.4    # or an explicit target
#
# The box already runs nginx, so this adds a server block and uses certbot for TLS rather than
# touching ports directly. It never edits the existing site. Node 20+ must be installed
# (see deploy/provision.sh for a one-time setup helper).
set -euo pipefail

HOST=${1:-shotarc-vps}
APK=${APK:-app/build/outputs/apk/release/app-release.apk}
[ -f "$APK" ] || APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "no APK built — run ./gradlew assembleRelease or assembleDebug"; exit 1; }

echo "→ preflight"
ssh "$HOST" 'command -v node >/dev/null || { echo "node not installed — run deploy/provision.sh first"; exit 1; }
  command -v nginx >/dev/null || { echo "nginx not found"; exit 1; }
  id -u shotarc >/dev/null 2>&1 || useradd --system --home /var/lib/shotarc --shell /usr/sbin/nologin shotarc
  mkdir -p /opt/shotarc/server /var/lib/shotarc
  chown -R shotarc:shotarc /var/lib/shotarc'

echo "→ server code"
rsync -az --delete --exclude node_modules --exclude data server/ "$HOST:/opt/shotarc/server/"

echo "→ APK ($(du -h "$APK" | cut -f1))"
rsync -az "$APK" "$HOST:/var/lib/shotarc/golf-tracker.apk"

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
  chown -R shotarc:shotarc /opt/shotarc /var/lib/shotarc/golf-tracker.apk
  ln -sf /etc/nginx/sites-available/shotarc.conf /etc/nginx/sites-enabled/shotarc.conf
  systemctl daemon-reload
  systemctl enable --now shotarc
  systemctl restart shotarc
  nginx -t && systemctl reload nginx
  sleep 1
  echo "--- shotarc service:"; systemctl --no-pager --lines=3 status shotarc | tail -4
  echo "--- local health:"; curl -sf http://127.0.0.1:8080/api/stats && echo'

echo
echo "→ HTTP is live. For HTTPS, once shotarc.co.za points at this box, run:"
echo "    ssh $HOST 'certbot --nginx -d shotarc.co.za -d www.shotarc.co.za --non-interactive --agree-tos -m you@example.com'"
