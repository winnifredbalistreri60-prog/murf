#!/bin/bash
# murf-svc — супервизор локальной среды MURF внутри proot (вместо systemd).
#   murf-svc run            — запустить все службы и следить за ними (перезапуск при падении); держит терминал
#   murf-svc stop           — остановить всё
#   murf-svc status         — JSON-состояние
#   murf-svc restart NAME   — перезапустить одну службу (gateway|webui|...)
#   murf-svc login [PROV]   — вход в провайдера (по умолчанию xai-oauth, device-code: ссылка + код)
#   murf-svc login-status   — JSON: url, code, state
#   murf-svc proxy URL|off  — прокси для провайдера (HTTPS_PROXY/ALL_PROXY), перезапуск агента
#   murf-svc netcheck       — доступность провайдера напрямую/через прокси (JSON)
#   murf-svc import URL [--with-secrets] — перенос настроек с сервера (архив из MURF)
#   murf-svc restart-screen — перезапуск экрана (выполнит супервизор в течение ~3 с)
#   murf-svc creds          — JSON с локальными адресом/паролем WebUI (для приложения MURF)
export HOME=/root LANG=C.UTF-8 LC_ALL=C.UTF-8
export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
unset TERMUX_VERSION PREFIX LD_PRELOAD 2>/dev/null || true
MURF_DIR=${MURF_DIR:-/opt/murf}
MURF_HOME=/root/.murf
RUN=$MURF_HOME/run; LOGS=$MURF_HOME/logs
HERMES_BIN=/root/.hermes/hermes-agent/.hermes/bin/hermes
PORT=$(sed -n 's/.*listen 127.0.0.1:\([0-9]*\);.*/\1/p' $MURF_HOME/nginx.conf 2>/dev/null | head -1); PORT=${PORT:-18080}
mkdir -p "$RUN/nginx" "$LOGS" /root/.cache/agent-screen
SERVICES="gateway webui viewproxy wsview wsctl api nginx"

svc_cmd() {
  case "$1" in
    gateway)   echo "env PYTHONUNBUFFERED=1 $HERMES_BIN gateway run" ;;
    webui)     echo "bash -c 'set -a; . /root/.hermes/webui.env; set +a; cd /root/hermes-webui && exec python3 bootstrap.py --no-browser --skip-agent-install --foreground'" ;;
    viewproxy) echo "python3 $MURF_DIR/rfb_viewonly_proxy.py" ;;
    wsview)    echo "websockify --unix-target=/root/.cache/agent-screen/view.sock 127.0.0.1:18081" ;;
    wsctl)     echo "websockify --unix-target=/root/.hermes/bot-desktop/rfb.sock 127.0.0.1:18082" ;;
    api)       echo "python3 $MURF_DIR/murf_api.py" ;;
    nginx)     echo "nginx -c $MURF_HOME/nginx.conf -p $RUN/nginx -g 'daemon off;'" ;;
  esac
}
alive() { [ -f "$1" ] && kill -0 "$(cat "$1" 2>/dev/null)" 2>/dev/null; }
tree_kill() { # pid — убить процесс и всех потомков
  local p=$1 c
  for c in $(pgrep -P "$p" 2>/dev/null); do tree_kill "$c"; done
  kill "$p" 2>/dev/null
}
start_one() { # name — запустить службу в отдельной сессии/группе (killpg внутри Hermes не заденет супервизор)
  local n=$1 cmd; cmd=$(svc_cmd "$n")
  [ -f "$LOGS/$n.log" ] && [ "$(stat -c %s "$LOGS/$n.log" 2>/dev/null || echo 0)" -gt 5000000 ] && mv -f "$LOGS/$n.log" "$LOGS/$n.log.1"
  echo "[$(date '+%F %T')] start $n" >> "$LOGS/$n.log"
  setsid bash -c "exec $cmd" >> "$LOGS/$n.log" 2>&1 < /dev/null &
  echo $! > "$RUN/$n.pid"; date +%s > "$RUN/$n.started"
}
# один цикл-супервизор на все службы (раньше — отдельный bash на каждую: лишние процессы,
# а Android 12+ убивает «лишние» дочерние процессы Termux — phantom process killer)
supervise() {
  local n now
  while [ -f "$RUN/svc.pid" ]; do
    sleep 3; now=$(date +%s)
    for n in $SERVICES; do
      alive "$RUN/$n.pid" && continue
      [ $((now - $(cat "$RUN/$n.started" 2>/dev/null || echo 0))) -lt 5 ] && continue
      echo "[$(date '+%F %T')] exit $n — перезапуск" >> "$LOGS/$n.log"; start_one "$n"
    done
    # запрос перезапуска экрана (murf-local restart-screen / кнопка в MURF). Выполняется здесь, в том же proot,
    # что и остальные службы: Termux-proot прячет длинные пути UNIX-сокетов в своём временном каталоге,
    # и сокет, созданный в другом proot-процессе, websockify не увидит.
    if [ -f "$RUN/restart-screen.req" ]; then rm -f "$RUN/restart-screen.req"; do_restart_screen >> "$LOGS/desktop.log" 2>&1; fi
  done
}
do_restart_screen() {
  echo "[$(date '+%F %T')] перезапуск экрана"
  bash "$MURF_DIR/desktop.sh" stop >/dev/null 2>&1
  pkill -x Xvnc 2>/dev/null; pkill -x Xtigervnc 2>/dev/null; sleep 1
  for n in viewproxy wsview wsctl; do [ -f "$RUN/$n.pid" ] && tree_kill "$(cat "$RUN/$n.pid")"; rm -f "$RUN/$n.started"; done
  rm -f /root/.cache/agent-screen/view.sock
  setsid bash "$MURF_DIR/desktop.sh" start < /dev/null &
  echo "[$(date '+%F %T')] экран: запуск отправлен"
}
cleanup_all() {
  for n in $SERVICES; do
    [ -f "$RUN/$n.loop" ] && tree_kill "$(cat "$RUN/$n.loop")"
    [ -f "$RUN/$n.pid" ] && tree_kill "$(cat "$RUN/$n.pid")"
    rm -f "$RUN/$n.loop" "$RUN/$n.pid" "$RUN/$n.started"
  done
  # остатки (рабочий стол бота, процессы WebUI/агента)
  pkill -f "hermes gateway run" 2>/dev/null; pkill -f "bootstrap.py --no-browser" 2>/dev/null
  pkill -f "/root/hermes-webui/server.py" 2>/dev/null; pkill -x Xvnc 2>/dev/null; pkill -x Xtigervnc 2>/dev/null
  pkill -f "nginx: master process nginx -c $MURF_HOME" 2>/dev/null
}

cmd_run() {
  if alive "$RUN/svc.pid"; then echo "уже запущено (pid $(cat $RUN/svc.pid))"; exit 0; fi
  [ -x "$HERMES_BIN" ] || { echo "среда не установлена"; exit 2; }
  echo $$ > "$RUN/svc.pid"
  trap 'rm -f "$RUN/svc.pid"; cleanup_all; exit 0' TERM INT HUP
  cleanup_all 2>/dev/null
  echo $$ > "$RUN/svc.pid"
  for n in $SERVICES; do start_one "$n"; done
  # рабочий стол бота (экран) — поднять сразу, чтобы вкладка «Экран» работала; дальше Hermes сам гасит его после 30 мин простоя
  ( sleep 20; setsid bash "$MURF_DIR/desktop.sh" start >> "$LOGS/desktop.log" 2>&1 < /dev/null ) &
  echo "MURF local: службы запущены, http://127.0.0.1:$PORT/  (журналы: $LOGS)"
  supervise
}
cmd_stop() {
  if alive "$RUN/svc.pid"; then kill "$(cat "$RUN/svc.pid")" 2>/dev/null; sleep 2; fi
  rm -f "$RUN/svc.pid"; cleanup_all; echo "остановлено"
}
cmd_status() {
  local h; h=$(curl -fsS -m 3 "http://127.0.0.1:18083/murf/health" 2>/dev/null || echo '{}')
  local running=false; alive "$RUN/svc.pid" && running=true
  local svcs=""
  for n in $SERVICES; do local a=false; alive "$RUN/$n.pid" && a=true; svcs="$svcs\"$n\":$a,"; done
  local installed=false; [ -x "$HERMES_BIN" ] && [ -f /root/hermes-webui/bootstrap.py ] && installed=true
  printf '{"installed":%s,"running":%s,"port":%s,"services":{%s},"health":%s}\n' "$installed" "$running" "$PORT" "${svcs%,}" "$h"
}
cmd_restart() { local n=$1; [ -f "$RUN/$n.pid" ] && tree_kill "$(cat "$RUN/$n.pid")"; rm -f "$RUN/$n.started"; echo "перезапуск $n"; }

cmd_login() {
  local prov=${1:-xai-oauth}
  [ -x "$HERMES_BIN" ] || { echo "среда не установлена"; exit 2; }
  set -a; . <(grep -E '^(HTTPS_PROXY|HTTP_PROXY|ALL_PROXY|NO_PROXY)=' /root/.hermes/.env 2>/dev/null); set +a
  : > "$MURF_HOME/login.log"; echo $$ > "$RUN/login.pid"
  echo "Вход в провайдера $prov. Откройте ссылку в браузере телефона и подтвердите код."
  PYTHONUNBUFFERED=1 "$HERMES_BIN" auth add "$prov" --type oauth --no-browser 2>&1 | tee -a "$MURF_HOME/login.log"
  local rc=${PIPESTATUS[0]}
  echo "MURF_LOGIN_EXIT=$rc" >> "$MURF_HOME/login.log"; rm -f "$RUN/login.pid"
  [ "$rc" = 0 ] && { echo "Готово: вход выполнен."; cmd_restart gateway >/dev/null; cmd_restart webui >/dev/null; } || echo "Вход не удался (код $rc)."
  return $rc
}
cmd_login_status() {
  python3 - "$MURF_HOME/login.log" "$RUN/login.pid" <<'PY'
import json, os, re, sys
log, pidf = sys.argv[1:3]
t = open(log, errors="ignore").read() if os.path.exists(log) else ""
t = re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", t)
urls = re.findall(r"https?://[^\s\"'<>)\]]+", t)
url = next((u for u in urls if "device" in u or "activate" in u or "code" in u), urls[0] if urls else "")
m = re.search(r"(?i)(?:code|код)[^A-Z0-9]{0,20}([A-Z0-9]{3,6}(?:-[A-Z0-9]{3,6})+|[A-Z0-9]{6,10})\b", t)
ex = re.search(r"MURF_LOGIN_EXIT=(\d+)", t)
running = False
try:
    os.kill(int(open(pidf).read()), 0); running = True
except Exception:
    pass
state = "running" if running else ("ok" if ex and ex.group(1) == "0" else ("failed" if ex else "idle"))
print(json.dumps({"state": state, "url": url, "code": m.group(1) if m else "", "tail": t[-600:]}, ensure_ascii=False))
PY
}
cmd_proxy() {
  local v=${1:-}
  python3 - "$v" <<'PY'
import os, re, sys
v = sys.argv[1].strip()
keys = ["HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY"]
for f in ("/root/.hermes/.env", "/root/.hermes/webui.env"):
    lines = open(f).read().splitlines() if os.path.exists(f) else []
    lines = [l for l in lines if not any(l.startswith(k + "=") for k in keys)]
    if v and v != "off":
        lines += [f"HTTPS_PROXY={v}", f"HTTP_PROXY={v}", f"ALL_PROXY={v}", "NO_PROXY=127.0.0.1,localhost,::1"]
    open(f, "w").write("\n".join(lines) + "\n"); os.chmod(f, 0o600)
print("proxy:", "off" if not v or v == "off" else "on")
PY
  cmd_restart gateway >/dev/null; cmd_restart webui >/dev/null
}
cmd_netcheck() {
  local p; p=$(sed -n 's/^HTTPS_PROXY=//p' /root/.hermes/.env 2>/dev/null | tail -1)
  code() { curl -s -o /dev/null -m 15 -w '%{http_code}' "$@" 2>/dev/null || true; }
  local d_api d_auth x_api="" x_auth=""
  d_api=$(code --noproxy '*' https://api.x.ai/v1/models); d_auth=$(code --noproxy '*' https://accounts.x.ai/)
  if [ -n "$p" ]; then x_api=$(code -x "$p" https://api.x.ai/v1/models); x_auth=$(code -x "$p" https://accounts.x.ai/); fi
  # 401 = доступен (нужен ключ); 403 = гео-блок; 000 = нет соединения
  printf '{"direct":{"api":"%s","auth":"%s"},"proxy_set":%s,"proxy":{"api":"%s","auth":"%s"}}\n' \
    "$d_api" "$d_auth" "$([ -n "$p" ] && echo true || echo false)" "$x_api" "$x_auth"
}
cmd_import() {
  local url=$1 secrets=0; [ "${2:-}" = "--with-secrets" ] && secrets=1
  [ -n "$url" ] || { echo "нужен URL"; exit 2; }
  local tmp; tmp=$(mktemp -d); trap 'rm -rf "$tmp"' RETURN
  curl -fsSL --retry 3 -o "$tmp/s.tgz" "$url" || { echo "не скачать архив"; return 1; }
  mkdir -p "$tmp/x" && tar -xzf "$tmp/s.tgz" -C "$tmp/x" || { echo "архив повреждён"; return 1; }
  local bk=/root/.murf/backups/import-$(date +%Y%m%d-%H%M%S); mkdir -p "$bk"
  cp -a /root/.hermes/config.yaml /root/.hermes/skills /root/.hermes/memories /root/.hermes/SOUL.md "$bk/" 2>/dev/null
  python3 - "$tmp/x" "$secrets" <<'PY'
import os, shutil, sys, yaml
src, secrets = sys.argv[1], sys.argv[2] == "1"
H = "/root/.hermes"
p = os.path.join(src, "config.yaml")
if os.path.exists(p):
    new = yaml.safe_load(open(p)) or {}
    cur = yaml.safe_load(open(os.path.join(H, "config.yaml"))) if os.path.exists(os.path.join(H, "config.yaml")) else {}
    cur = cur or {}
    if isinstance(new.get("mcp_servers"), dict): new["mcp_servers"].pop("agent_tools", None)
    for k in ("bot_desktop", "browser", "terminal"):
        if k in cur: new[k] = cur[k]
    new.setdefault("bot_desktop", {})["auto_start"] = True
    yaml.safe_dump(new, open(os.path.join(H, "config.yaml"), "w"), allow_unicode=True, sort_keys=False)
    print("config.yaml: перенесён")
for d in ("skills", "memories"):
    s = os.path.join(src, d)
    if os.path.isdir(s):
        shutil.copytree(s, os.path.join(H, d), dirs_exist_ok=True); print(d + ": перенесены")
if os.path.exists(os.path.join(src, "SOUL.md")):
    shutil.copy2(os.path.join(src, "SOUL.md"), os.path.join(H, "SOUL.md")); print("SOUL.md: перенесён")
if secrets:
    a = os.path.join(src, "secrets", "auth.json")
    if os.path.exists(a):
        if os.path.exists(os.path.join(H, "auth.json")):
            shutil.copy2(os.path.join(H, "auth.json"), os.path.join(H, "auth.json.bak-import"))
        shutil.copy2(a, os.path.join(H, "auth.json")); os.chmod(os.path.join(H, "auth.json"), 0o600)
        print("auth.json: перенесён (вход в провайдера с сервера)")
    e = os.path.join(src, "secrets", "keys.env")
    if os.path.exists(e):
        envf = os.path.join(H, ".env")
        cur = open(envf).read().splitlines() if os.path.exists(envf) else []
        new = dict(l.split("=", 1) for l in open(e).read().splitlines() if "=" in l and not l.startswith("#"))
        keep = [l for l in cur if l.split("=", 1)[0] not in new]
        open(envf, "w").write("\n".join(keep + [f"{k}={v}" for k, v in new.items()]) + "\n"); os.chmod(envf, 0o600)
        print(f"ключи API: перенесено {len(new)} шт.")
elif os.path.isdir(os.path.join(src, "secrets")):
    print("секреты в архиве есть, но не применены (нужен --with-secrets)")
PY
  echo "резервная копия прежних настроек: $bk"
  cmd_restart gateway >/dev/null; cmd_restart webui >/dev/null
}
cmd_creds() {
  local pw; pw=$(sed -n 's/^HERMES_WEBUI_PASSWORD=//p' /root/.hermes/webui.env 2>/dev/null | tail -1)
  printf '{"url":"http://127.0.0.1:%s","password":"%s"}\n' "$PORT" "$pw"
}

case "${1:-status}" in
  run) cmd_run ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  restart) shift; [ $# -eq 0 ] && set -- gateway webui; for n in "$@"; do cmd_restart "$n"; done ;;
  login) shift; cmd_login "$@" ;;
  login-status) cmd_login_status ;;
  proxy) shift; cmd_proxy "${1:-off}" ;;
  netcheck) cmd_netcheck ;;
  import) shift; cmd_import "$@" ;;
  creds) cmd_creds ;;
  restart-screen) touch "$RUN/restart-screen.req"; echo "запрос на перезапуск экрана отправлен" ;;
  *) sed -n '2,14p' "$0"; exit 2 ;;
esac
