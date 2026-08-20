#!/usr/bin/env bash
# One-time setup for a fresh Ubuntu box that already runs nginx: installs Node 20 and certbot.
# Safe to re-run. Does not touch the existing nginx configuration.
#
#   ./deploy/provision.sh          # uses the shotarc-vps ssh host
set -euo pipefail
HOST=${1:-shotarc-vps}
ssh "$HOST" 'set -e
  if ! command -v node >/dev/null || [ "$(node -v | cut -dv -f2 | cut -d. -f1)" -lt 20 ]; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y nodejs
  fi
  command -v certbot >/dev/null || apt-get install -y certbot python3-certbot-nginx
  node -v; nginx -v; certbot --version'
