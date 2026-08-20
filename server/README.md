# ShotArc server

Serves three things from one small Node process: the APK people install, the API the phones
upload rounds to, and the dashboard you read afterwards. Data lives in one SQLite file.

## The box it deploys to

`mainsite.strategicbw.com` (156.155.250.79) already runs **nginx** with another site on it, so
ShotArc slots in beside it rather than taking over:

- The Node app listens on `127.0.0.1:8080` only — it is never exposed directly.
- A new nginx server block answers for `shotarc.co.za` alone and proxies to it. The existing
  site's config is never touched; nginx returns its usual 404 for the default host, so adding a
  named block cannot collide with it.
- TLS comes from certbot's nginx plugin, not from replacing nginx.

## Deploy

```bash
./deploy/provision.sh              # one time: installs Node 20 and certbot (leaves nginx alone)
./gradlew assembleRelease          # or assembleDebug for a sideload build
./deploy/deploy.sh                 # copies server + APK, adds the nginx site, starts everything
```

`deploy.sh` is idempotent — re-run it for every update. It refuses to run if Node or nginx is
missing rather than guessing.

## Turning on HTTPS

Point `shotarc.co.za` and `www.shotarc.co.za` at 156.155.250.79 with an A record first, then:

```bash
ssh shotarc-vps 'certbot --nginx -d shotarc.co.za -d www.shotarc.co.za \
  --non-interactive --agree-tos -m you@example.com'
```

certbot rewrites the server block with the certificate and the :443 listener, and renews it on a
timer. Until DNS resolves, the site works over plain HTTP on the domain but a certificate cannot
be issued.

## Secrets

Put them in `/etc/shotarc.env` on the server (read by the unit, never in git):

```
INGEST_KEY=<long random string>       # phones must send it as X-Ingest-Key
DASHBOARD_KEY=<another long string>   # dashboard needs ?key=… to open
```

Leave either empty and that side is open to anyone who knows the URL. The ingest key has to match
`golfIngestKey` in `gradle.properties` when the APK is built, or uploads will be rejected.

## Endpoints

| Path | Who calls it | Does |
|---|---|---|
| `/` | player's browser | install page with the download button |
| `/golf-tracker.apk` | player's browser | the APK, and counts the download |
| `/api/install` | app, first launch | records an anonymous install id |
| `/api/sessions` | app, after each shot | upserts the session and its shots |
| `/dashboard` | you | stat tiles and every session |
| `/dashboard/session/:id` | you | one round: shot paths, flight profiles, shot table |
| `/api/stats`, `/api/sessions`, `/api/sessions/:id` | you | the same data as JSON |

## Downloads versus installs

They are different numbers and the dashboard shows both. A download is a GET on the APK. An
install is the app announcing itself on first launch with a random id it generates locally — no
account, no advertising id, nothing that identifies a person. Someone who downloads and never
opens the app counts once in the first column and never in the second, which is exactly the gap
worth watching.

## Test it without a VPS

```bash
cd server && npm install && ./smoke.sh
```

Starts the server on a scratch database, pushes a session through it, and checks the install page,
the dashboard and a session view all render what went in.
