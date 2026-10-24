# Agent Screen / Bot Desktop — support matrix

Public `murf-install` («Свой VDS») ships auto-start for the agent desktop **only** where the same stack as the reference Hermes host is available. Elsewhere the API returns a clear Russian error — never a silent 404 or fake success.

## Authz (production)

| Layer | What |
|---|---|
| nginx `auth_request` on `/phone/api/` | Valid WebUI session cookie required from the public internet |
| Header `X-Agent-App: 1` | Required on `GET/POST /phone/api/screen/status\|start` (CSRF; browsers without the app header get **403**) |
| Relay bind | `127.0.0.1` only — not exposed without nginx |

HTTP codes for start/status:

- **200** — desktop running / start succeeded  
- **403** — missing `X-Agent-App`  
- **501** — host/stack **not supported** (`supported: false`)  
- **503** — supported stack but desktop failed to come up  

## Supported (proven)

| Environment | Status |
|---|---|
| Ubuntu 24.04 / Debian 12+ **x86_64 (amd64)** + **systemd** + **TigerVNC (`Xvnc`)** + **Xfce core** (`xfwm4`, `xfce4-panel`, `xfdesktop`, `xfsettingsd`) + Hermes `tools/bot_desktop` | ✅ Reference (same path as working Hermes) |

On that host `murf-install.sh` (installer v2+):

1. Installs TigerVNC + Xfce core + `xdotool` + `websockify`  
2. Deploys `murf_screen_desktop.py` into `~/agent-phone/`  
3. Sets `bot_desktop.auto_start` when Hermes config exists  
4. **Idempotently** writes/enables/restarts `murf-bot-desktop.service`  
5. Relay serves `/phone/api/screen/start` and `/phone/api/screen/status`  

`computer-use` / cua-driver is **not** required (`--skip-computer-use` stays). Desktop packages are separate.

## Not supported (explicit)

| Environment | Behaviour |
|---|---|
| **ARM / aarch64** | Desktop APT packages **not** installed; unit **not** enabled; API **501** with clear text |
| **Docker-only / no Xvnc / no Xfce** | Unit not enabled; API **501/503** naming the missing piece |
| Host **without systemd** | No keep-alive unit; warn during install; API still gated on binaries |
| Hermes **without** `tools/bot_desktop` | Unit skipped; API **501** |

## Upgrade existing VDS

Re-run the installer from MURF «Свой VDS» or:

```bash
sudo bash murf-install.sh --update
```

Idempotent: refreshes helper + unit when the stack qualifies; otherwise warns only.

## Verify on a fresh x86_64 VDS

```bash
systemctl is-active murf-bot-desktop murf-relay
curl -sS -o /tmp/st.json -w '%{http_code}\n' -H 'X-Agent-App: 1' \
  http://127.0.0.1:8790/phone/api/screen/status
# expect 200 and "running": true once desktop is up; or 501/503 + "error" text — never empty 404
test -S ~murf/.hermes/bot-desktop/rfb.sock && echo rfb.ok
# without header:
curl -sS -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8790/phone/api/screen/start
# expect 403
```

`/screen/index.html` (when deployed) calls `POST /phone/api/screen/start` on open and surfaces the error text if start fails.
