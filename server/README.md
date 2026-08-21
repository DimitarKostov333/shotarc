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

**After certbot has run, it owns `shotarc.conf`.** `deploy.sh` therefore installs that file only
once and never overwrites it, so redeploys keep HTTPS. To change the nginx config later, edit it
on the box (or delete it and re-run certbot) — do not just re-copy the repo version, which has no
TLS block.

## Secrets

Put them in `/etc/shotarc.env` on the server (read by the unit, never in git):

```
INGEST_KEY=<long random string>       # phones must send it as X-Ingest-Key
DASHBOARD_KEY=<another long string>   # JSON API only, as the X-Dashboard-Key header
ADMIN_USER=<name>                     # seeds the first dashboard account on a fresh box
ADMIN_PASSWORD=<password>
```

The `/dashboard` pages open for a signed-in account and nothing else — there is no key that
skips the login. `DASHBOARD_KEY` is for scripts hitting the JSON endpoints, sent as the
`X-Dashboard-Key` header; it resolves to the `ADMIN_USER` account and is scoped like any other,
so it sees that account's rounds and no one else's. Leave it empty and those endpoints need a
session cookie too.

Leave `INGEST_KEY` empty and uploads are open to anyone who knows the URL. It has to match
`golfIngestKey` in `gradle.properties` when the APK is built, or uploads will be rejected.

## Endpoints

| Path | Who calls it | Does |
|---|---|---|
| `/` | player's browser | install page with the download button |
| `/golf-tracker.apk` | player's browser | the APK, and counts the download |
| `/api/install` | app, first launch | records an anonymous install id |
| `/api/sessions` | app, after each shot | upserts the session and its shots |
| `/signup`, `/login` | a player's browser | make an account, or sign in to one |
| `/api/pair` | app, once | ties a phone to an account with a code from that dashboard |
| `/dashboard` | a signed-in player | their own stat tiles and sessions |
| `/dashboard/session/:id` | a signed-in player | one of their rounds: shot paths, flight profiles, shot table |
| `/api/stats`, `/api/sessions`, `/api/sessions/:id` | a signed-in player | the same data as JSON |

## Standing up to a bot

Every request is counted against the address prefix it came from, in memory, per process:

| Surface | Allowance |
|---|---|
| any request (assets excluded) | 600 / minute |
| `POST /login` | 10 / 15 min, cleared by a success |
| `POST /signup` | 5 / hour, counted whether or not it succeeds |
| `POST /api/pair` | 10 / 15 min |
| `GET /golf-tracker.apk` | 30 / 10 min |
| `POST /api/install`, `/api/sessions` | 240 / 10 min |

The download also counts one address once an hour, so a retried install or a bot in a loop cannot
inflate the figure the dashboard reports.

Everything the phone posts is coerced in `validate.js` before it reaches the database — a number
that is not a number becomes null, strings are capped, ids must look like ids, and a session
carries at most 400 shots of at most 200 track points. This matters more than it looks: the ingest
key is compiled into the APK, so anyone who unzips it can post whatever they like, and SQLite
stores text in a column declared INTEGER without complaint. Pages then treat every stored number
as suspect on the way out too.

## Accounts and whose rounds are whose

A round belongs to the account that the phone which uploaded it is paired to. Nothing else links
them, so every dashboard and API read joins through `installs.owner` and one account can never
see another's.

Anyone can make an account at `/signup` — a username and a password, no email. `node
create-user.js <username> <password>` still works for making or resetting one from the box, and
`ADMIN_USER`/`ADMIN_PASSWORD` seed the first account on a fresh install.

Pairing a phone: the dashboard shows a six-character code, good for fifteen minutes and one
phone. The app posts it to `/api/pair` with its install id, behind `X-Ingest-Key`:

```
POST /api/pair            {"installId": "<uuid>", "code": "TML8E6"}
  200 {"ok":true,"account":"dim"}   404 unknown or expired   429 too many attempts
```

An unpaired phone still records and still uploads; its rounds simply sit on no dashboard until it
is paired, and then they appear. Rounds recorded **before** ownership existed have no owner at
all — hand them to an account once with `node adopt-installs.js <username>`.

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
