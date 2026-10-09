# Server side / Серверная часть

MURF works with a stock **hermes-webui** + **Hermes Agent** server. The files here add the MURF extras.
Все адреса — заглушки (`your-server.example`); замените на свои. Секреты храните в файлах с правами 600, не в репозитории.

| Path | What |
|---|---|
| `examples/nginx-murf.conf` | nginx site: WebUI, agent API, agent screen (view/control), phone bridge |
| `examples/*.env.example` | environment templates for Hermes API server and WebUI |
| `examples/agent-screen-wsview.service` | websockify serving the screen page + view-only stream |
| `agent-screen/` | screen page (`index.html`, needs noVNC `core/` + `vendor/` next to it), RFB view-only filter proxy, systemd user units |
| `phone-relay/` | phone bridge relay (`phone_relay.py`), MCP server `agent_tools_mcp.py` for the agent, server update helper |
| `local-mode/` | Termux/proot scripts for running everything on the phone (`murf-local`) |

## Quick setup (Ubuntu, systemd user services)

1. Install Hermes Agent and hermes-webui per their docs; enable the agent API server (`examples/hermes.env.example`) and the bot desktop (`bot_desktop.auto_start` in Hermes config).
2. Copy `agent-screen/` to `~/agent-screen-srv/` and the page to `~/agent-screen/` with noVNC:
   `git clone --depth 1 -b v1.5.0 https://github.com/novnc/noVNC /tmp/novnc && cp -a /tmp/novnc/core /tmp/novnc/vendor ~/agent-screen/`
3. Copy `phone-relay/` to `~/agent-phone/`, set `PUBLIC_BASE=https://your-server.example` in `agent-phone-relay.service`.
4. `cp agent-screen/systemd/*.service examples/agent-screen-wsview.service phone-relay/agent-phone-relay.service ~/.config/systemd/user/ && systemctl --user daemon-reload && systemctl --user enable --now agent-screen-view agent-screen-ctl agent-screen-wsview agent-phone-relay`
   (`loginctl enable-linger $USER` to keep them running).
5. Install `examples/nginx-murf.conf` into nginx, get a TLS certificate, `nginx -t && systemctl reload nginx`.
6. Register the MCP server for the agent (Hermes config `mcp_servers`): `python3 ~/agent-phone/agent_tools_mcp.py`.
7. In MURF: add server `https://your-server.example` with the WebUI password.
