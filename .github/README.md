# CI/CD

Three workflows, plus the repo secrets they need.

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | push / PR to `main` | runs the 52 JVM tests and the server smoke test, builds a debug APK, uploads it as a run artifact |
| `release.yml` | push a tag `v*` | builds a **signed** release APK and attaches it to a GitHub Release |
| `deploy.yml` | manual (Actions → Deploy to VPS → Run) | builds the signed APK and ships it, the server and the nginx site to the VPS |

## Secrets to add

Settings → Secrets and variables → Actions → New repository secret.

**For signing (release.yml, deploy.yml):**

| Secret | What |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the `.jks`, base64-encoded: `base64 -w0 shotarc.jks` |
| `SIGNING_KEYSTORE_PASSWORD` | its store password |
| `SIGNING_KEY_ALIAS` | the key alias (e.g. `shotarc`) |
| `SIGNING_KEY_PASSWORD` | the key password |
| `INGEST_KEY` | the ingest secret compiled into the app, matching the server |

**Additionally for deploy.yml:**

| Secret | What |
|---|---|
| `DASHBOARD_KEY` | gate for the dashboard; written to `/etc/shotarc.env` |
| `VPS_DEPLOY_KEY` | the **private** deploy key (contents of `~/.ssh/shotarc_deploy`) |
| `VPS_HOST` | `root@156.155.250.79` |

The keystore never enters the repo — it lives only as a secret. Generate one with:

```bash
keytool -genkeypair -v -keystore shotarc.jks -alias shotarc \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 shotarc.jks          # paste this into SIGNING_KEYSTORE_BASE64
```

Keep the `.jks` and its passwords somewhere safe outside git — every future release must be
signed with the **same** keystore or Android will refuse to update installs.

## Cutting a release

```bash
git tag v1.0
git push origin v1.0            # release.yml builds and publishes the signed APK
```

Then deploy it to the VPS from the Actions tab (Deploy to VPS → Run workflow → type `deploy`),
once the VPS deploy key and DNS are in place.
