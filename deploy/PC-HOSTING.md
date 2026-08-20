# Hosting ShotArc from your PC

Everything runs on your machine — the dashboard, the API and the APK download. Nothing is rented.
The only question is **who needs to reach it**, because that is a networking problem, not a
compute one.

Your PC is Windows running WSL2 in the default NAT mode. Two facts follow:

1. A service started inside WSL is on a private WSL address (172.x). Even your own phone on the
   same WiFi cannot see it until Windows forwards the port in — see step 1 below.
2. Your home router hides the PC behind one public address, and the phone on the course is on a
   different network entirely. Reaching it from the course needs a tunnel — see step 3.

## Step 1 — let the LAN reach WSL

The clean fix is WSL **mirrored networking**, which puts WSL services straight onto the Windows
host. In `C:\Users\<you>\.wslconfig`:

```ini
[wsl2]
networkingMode=mirrored
```

Then `wsl --shutdown` in PowerShell and reopen. After that, a server on `:8080` in WSL answers on
the PC's own LAN address. (Older Windows without mirrored mode: run
`netsh interface portproxy add v4tov4 listenport=8080 connectport=8080 connectaddress=<wsl-ip>`
in an admin PowerShell instead.)

## Step 2 — run the server

```bash
./deploy/run-on-pc.sh
```

It serves on `0.0.0.0:8080` and keeps its SQLite database in `server/data`. To have it start with
the PC, either put this line in Task Scheduler (at logon) or run it under `pm2`.

Drop the APK where it can serve it:

```bash
cp app/build/outputs/apk/release/app-release.apk server/data/golf-tracker.apk
```

At this point, on your home WiFi, `http://<pc-lan-ip>:8080/dashboard` works from any device.

## Step 3 — reach it from the golf course (Cloudflare Tunnel)

This is the part a home connection cannot do on its own. A Cloudflare Tunnel gives
`shotarc.co.za` a public HTTPS front door that points back to the server on your PC — no router
port-forwarding, no static IP, and TLS handled for you. The **compute stays on your PC**;
Cloudflare only carries the traffic, the way your ISP already does.

```bash
# one time
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
  -o /usr/local/bin/cloudflared && chmod +x /usr/local/bin/cloudflared
cloudflared tunnel login                      # opens a browser, pick shotarc.co.za
cloudflared tunnel create shotarc
cloudflared tunnel route dns shotarc shotarc.co.za

# config: ~/.cloudflared/config.yml
#   tunnel: shotarc
#   credentials-file: /root/.cloudflared/<id>.json
#   ingress:
#     - hostname: shotarc.co.za
#       service: http://localhost:8080
#     - service: http_status:404

cloudflared tunnel run shotarc                # keep this running alongside the server
```

Nameservers for `shotarc.co.za` have to be on Cloudflare for this (free plan is fine). Once the
tunnel is up, `https://shotarc.co.za` reaches your PC from anywhere, and the app — which is built
to talk to that address — syncs live from the course.

## If you would rather not sync from the course

Skip step 3. Build the app pointed at your LAN address instead of the domain:

```
# gradle.properties
golfServerUrl=http://<pc-lan-ip>:8080
```

The phone then uploads only when it is back on your home WiFi. Because a round is over by the time
you get home, open the app once on WiFi and tap **Sync** on the results panel to push the last
round up. (That button exists precisely for this case.)
