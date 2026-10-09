#!/bin/bash
# MURF local mode — установка внутри proot Ubuntu (запускается от root внутри proot).
# Идемпотентно: каждый шаг помечается в $MURF_HOME/steps/<шаг>.done и при повторе пропускается.
# Прогресс: $MURF_HOME/progress.json (читает Termux-скрипт murf-local и приложение MURF).
set -uo pipefail
export HOME=/root LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive
export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
unset TERMUX_VERSION PREFIX LD_PRELOAD TERMUX_APP__PACKAGE_NAME 2>/dev/null || true
MURF_DIR=${MURF_DIR:-/opt/murf}
MURF_HOME=${MURF_HOME:-/root/.murf}
HERMES_COMMIT=${HERMES_COMMIT:-0240fa4a84123406a0e5e6e7262e5b772b43f0bd}
PAYLOAD_URL=${MURF_PAYLOAD_URL:-}
WEBUI_GIT=${MURF_WEBUI_GIT:-https://github.com/nesquena/hermes-webui.git}
NGINX_PORT=${MURF_PORT:-18080}
HERMES_BIN=/root/.hermes/hermes-agent/.hermes/bin/hermes
mkdir -p "$MURF_HOME/steps" "$MURF_HOME/logs" "$MURF_HOME/run"
chmod 700 "$MURF_HOME"
LOG="$MURF_HOME/logs/setup.log"
exec > >(tee -a "$LOG") 2>&1

PCT=0
progress() { # state pct step msg
  PCT=$2
  python3 - "$MURF_HOME/progress.json" "$@" <<'PY' 2>/dev/null || printf '{"phase":"inner","state":"%s","pct":%s,"step":"%s"}\n' "$1" "$2" "$3" > "$MURF_HOME/progress.json"
import json, sys, time, os
p, state, pct, step, msg = sys.argv[1:6]
tmp = p + ".tmp"
json.dump({"phase": "inner", "state": state, "pct": int(pct), "step": step, "msg": msg, "ts": int(time.time())}, open(tmp, "w"), ensure_ascii=False)
os.replace(tmp, p)
PY
  echo "[$(date '+%F %T')] [$1 $2%] $3: $4"
}
done_step() { touch "$MURF_HOME/steps/$1.done"; }
is_done() { [ -f "$MURF_HOME/steps/$1.done" ]; }
fail() { progress error "$PCT" "${STEP:-?}" "$1"; echo "ОШИБКА: $1"; exit 1; }
rand() { python3 -c 'import secrets;print(secrets.token_urlsafe(32))' 2>/dev/null || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

# ---------- 1. apt ----------
STEP=apt
if ! is_done apt; then
  progress running 2 apt "Настройка apt"
  echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/01murf-sandbox
  printf 'Acquire::Retries "5";\nAcquire::http::Timeout "60";\n' > /etc/apt/apt.conf.d/02murf-net
  if ! apt-get update; then
    # в свежих rootfs ключ backports бывает не в keyring — убираем backports и повторяем
    sed -i -E 's/ ?[a-z]+-backports//' /etc/apt/sources.list.d/*.sources 2>/dev/null || true
    sed -i '/-backports/d' /etc/apt/sources.list 2>/dev/null || true
    apt-get update || fail "apt-get update не прошёл (нет интернета или зеркало недоступно)"
  fi
  progress running 5 apt "Установка пакетов (рабочий стол XFCE, VNC, nginx, python) — самый долгий шаг"
  PKGS="ca-certificates curl wget git xz-utils unzip procps psmisc iproute2 lsof locales tzdata less nano
        python3 python3-venv python3-pip python3-yaml websockify nginx xdotool xclip libatomic1
        tigervnc-standalone-server tigervnc-tools xfce4-panel xfwm4 xfdesktop4 xfce4-settings xfce4-terminal
        dbus-x11 x11-xserver-utils x11-utils x11-xkb-utils xauth fonts-dejavu-core fonts-noto-core fonts-noto-color-emoji"
  # после обрыва (закрыли Termux, убил Android) — доделать прерванную распаковку
  dpkg --configure -a || true; apt-get -f install -y || true
  apt-get install -y --no-install-recommends $PKGS || { apt-get -f install -y; apt-get install -y --no-install-recommends $PKGS; } \
    || fail "apt-get install не прошёл"
  # nginx из пакета пытается стартовать как служба — в proot это не нужно
  pkill -x nginx 2>/dev/null || true
  apt-get clean
  done_step apt
fi
progress running 30 apt "Пакеты установлены"

# ---------- 2. Hermes Agent (та же версия, что на сервере) ----------
STEP=hermes
if ! is_done hermes || ! [ -x "$HERMES_BIN" ]; then
  progress running 32 hermes "Hermes Agent ${HERMES_COMMIT:0:8}: загрузка установщика"
  curl -fsSL --retry 5 -o "$MURF_HOME/hermes-install.sh" \
    "https://raw.githubusercontent.com/NousResearch/hermes-agent/$HERMES_COMMIT/scripts/install.sh" \
    || fail "не скачать install.sh Hermes с GitHub"
  progress running 35 hermes "Hermes Agent: установка (git clone + Python-зависимости, 5–20 мин)"
  bash "$MURF_HOME/hermes-install.sh" --commit "$HERMES_COMMIT" --non-interactive --skip-browser --skip-computer-use </dev/null \
    || fail "установщик Hermes завершился с ошибкой (см. $LOG)"
  [ -x "$HERMES_BIN" ] || fail "после установки нет $HERMES_BIN"
  done_step hermes
fi
"$HERMES_BIN" --version 2>/dev/null | head -1
progress running 60 hermes "Hermes Agent установлен"

# ---------- 3. Браузер (Chromium через Hermes/Playwright) — не критично ----------
STEP=browser
if ! is_done browser && ! is_done browser.failed; then
  progress running 62 browser "Chromium для агента (необязательный шаг)"
  if "$HERMES_BIN" pm install chromium </dev/null; then done_step browser
  else echo "Chromium не установился — агент будет без браузера; повтор: murf-local install"; done_step browser.failed; fi
fi

# ---------- 4. WebUI + страница экрана ----------
STEP=webui
if ! is_done webui || ! [ -f /root/hermes-webui/bootstrap.py ]; then
  progress running 72 webui "Hermes WebUI: загрузка (как на сервере)"
  PAY="$MURF_DIR/payload.tgz"
  if [ ! -s "$PAY" ]; then
    [ -n "$PAYLOAD_URL" ] && curl -fL --retry 5 -C - -o "$PAY.part" "$PAYLOAD_URL" && mv "$PAY.part" "$PAY" || rm -f "$PAY.part"
  fi
  if [ -s "$PAY" ]; then
    rm -rf "$MURF_HOME/payload" && mkdir -p "$MURF_HOME/payload"
    tar -xzf "$PAY" -C "$MURF_HOME/payload" || fail "payload повреждён"
    rm -rf /root/hermes-webui && cp -a "$MURF_HOME/payload/hermes-webui" /root/hermes-webui
    mkdir -p /root/agent-screen && cp -a "$MURF_HOME/payload/agent-screen/." /root/agent-screen/
  else
    echo "payload недоступен — беру WebUI с GitHub (версия может отличаться от сервера)"
    rm -rf /root/hermes-webui && git clone --depth 1 "$WEBUI_GIT" /root/hermes-webui || fail "не скачать hermes-webui"
  fi
  done_step webui
fi
# index.html/прокси из комплекта скриптов приложения новее payload — копируем всегда
[ -f "$MURF_DIR/index.html" ] && mkdir -p /root/agent-screen && cp -f "$MURF_DIR/index.html" /root/agent-screen/index.html
# noVNC: в payload с сервера core/vendor могли оказаться ссылками на /usr/share/novnc (там пакет системы),
# а в Ubuntu телефона такого пакета нет — ссылки «висят», модули отдаются 404 и экран вечно «Подключение…».
# Поэтому каждый install/update проверяет и ставит noVNC из комплекта (novnc-1.6.0.tgz), запасной путь — GitHub.
novnc_ok() { [ -f /root/agent-screen/core/rfb.js ] && [ -f /root/agent-screen/core/crypto/des.js ] && [ -f /root/agent-screen/vendor/pako/lib/zlib/inflate.js ]; }
if ! novnc_ok; then
  echo "noVNC: core/vendor отсутствуют или битые ссылки — восстанавливаю"
  mkdir -p /root/agent-screen && rm -rf /root/agent-screen/core /root/agent-screen/vendor
  if [ -s "$MURF_DIR/novnc-1.6.0.tgz" ]; then tar -xzf "$MURF_DIR/novnc-1.6.0.tgz" -C /root/agent-screen core vendor
  else rm -rf /tmp/novnc && git clone -q --depth 1 -b v1.6.0 https://github.com/novnc/noVNC /tmp/novnc \
    && cp -a /tmp/novnc/core /tmp/novnc/vendor /root/agent-screen/; rm -rf /tmp/novnc; fi
fi
novnc_ok && echo "noVNC: ок" || echo "ВНИМАНИЕ: нет noVNC (core/) — вкладка «Экран» не будет работать"

# ---------- 5. Конфиг, ключи ----------
STEP=config
progress running 85 config "Настройка Hermes (локальные ключи, 127.0.0.1)"
mkdir -p /root/.hermes && chmod 700 /root/.hermes
ENVF=/root/.hermes/.env; touch "$ENVF"; chmod 600 "$ENVF"
setenv() { # key value file — заменить/добавить без вывода значения
  python3 - "$3" "$1" "$2" <<'PY'
import sys, re
f, k, v = sys.argv[1:4]
lines = open(f).read().splitlines() if __import__("os").path.exists(f) else []
out, done = [], False
for l in lines:
    if re.match(r"^\s*" + re.escape(k) + r"=", l):
        if not done: out.append(f"{k}={v}"); done = True
    else: out.append(l)
if not done: out.append(f"{k}={v}")
open(f, "w").write("\n".join(out) + "\n")
PY
}
getenv() { sed -n "s/^$1=//p" "$2" 2>/dev/null | tail -1; }
[ -n "$(getenv API_SERVER_KEY "$ENVF")" ] || setenv API_SERVER_KEY "$(rand)" "$ENVF"
setenv API_SERVER_HOST 127.0.0.1 "$ENVF"
setenv API_SERVER_PORT 8642 "$ENVF"
WENV=/root/.hermes/webui.env; touch "$WENV"; chmod 600 "$WENV"
[ -n "$(getenv HERMES_WEBUI_PASSWORD "$WENV")" ] || setenv HERMES_WEBUI_PASSWORD "$(rand)" "$WENV"
setenv HERMES_WEBUI_HOST 127.0.0.1 "$WENV"
setenv HERMES_WEBUI_PORT 8787 "$WENV"
setenv HERMES_HOME /root/.hermes "$WENV"
setenv HERMES_WEBUI_AGENT_DIR /root/.hermes/hermes-agent "$WENV"
# config.yaml: модель/провайдер как на сервере, рабочий стол бота и видимый браузер
python3 - <<'PY' || fail "не записать config.yaml"
import os, yaml
p = "/root/.hermes/config.yaml"
c = (yaml.safe_load(open(p)) if os.path.exists(p) else None) or {}
if not isinstance(c.get("model"), dict): c["model"] = {}
c["model"].setdefault("default", "grok-4.7"); c["model"].setdefault("provider", "xai-oauth")
c.setdefault("bot_desktop", {})["auto_start"] = True
c.setdefault("browser", {}).setdefault("headed", True)
# серверный MCP agent_tools на телефоне не нужен (пути сервера)
if isinstance(c.get("mcp_servers"), dict): c["mcp_servers"].pop("agent_tools", None)
yaml.safe_dump(c, open(p, "w"), allow_unicode=True, sort_keys=False)
PY
# nginx (127.0.0.1:$NGINX_PORT): / → WebUI, /screen/ → noVNC (только с cookie WebUI)
sed "s/@PORT@/$NGINX_PORT/g" "$MURF_DIR/nginx.conf" > "$MURF_HOME/nginx.conf"
mkdir -p "$MURF_HOME/run/nginx"
nginx -t -c "$MURF_HOME/nginx.conf" -p "$MURF_HOME/run/nginx" 2>&1 | tail -2
# через новый файл + mv: работающий супервизор (bash читает скрипт по ходу) не испортится
install -m 755 "$MURF_DIR/svc.sh" /usr/local/bin/murf-svc.new && mv -f /usr/local/bin/murf-svc.new /usr/local/bin/murf-svc
# В proot панель XFCE иногда не может подключиться к D-Bus (SCM_CREDENTIALS) и выходит — а вместе с ней
# лаунчер Hermes гасит Xvnc. Обёртка держит сессию живой, экран продолжает работать (окна, xfwm4, рабочий стол).
cat > /usr/local/bin/xfce4-panel <<'SH'
#!/bin/bash
/usr/bin/xfce4-panel "$@"
case " $* " in *" --help "*|*" -h "*|*" --version "*|*" -V "*|*" --quit "*|*" -q "*|*" --restart "*|*" -r "*) exit 0 ;; esac
echo "murf: xfce4-panel завершилась ($?), держу сессию рабочего стола" >&2
exec sleep infinity
SH
chmod 755 /usr/local/bin/xfce4-panel
echo "$HERMES_COMMIT" > "$MURF_HOME/hermes-commit"
done_step config

# ---------- 6. WebUI: зависимости (один пробный запуск bootstrap) ----------
STEP=webui-deps
if ! is_done webui-deps; then
  progress running 90 webui-deps "WebUI: зависимости Python"
  ( set -a; . "$WENV"; set +a; cd /root/hermes-webui && HERMES_WEBUI_PORT=18799 timeout 600 python3 bootstrap.py --no-browser --skip-agent-install --foreground ) &
  BP=$!
  for i in $(seq 1 300); do
    sleep 2
    curl -fsS -o /dev/null "http://127.0.0.1:18799/api/auth/status" 2>/dev/null && break
    kill -0 $BP 2>/dev/null || break
  done
  OK=0; curl -fsS -o /dev/null "http://127.0.0.1:18799/api/auth/status" 2>/dev/null && OK=1
  pkill -f "bootstrap.py --no-browser" 2>/dev/null; pkill -f "server.py" 2>/dev/null; kill $BP 2>/dev/null; wait $BP 2>/dev/null
  [ "$OK" = 1 ] || fail "WebUI не запустился при проверке (см. $LOG)"
  done_step webui-deps
fi

# кэш загрузок uv не нужен для работы (~300 МБ на телефоне)
rm -rf /root/.hermes/cache/uv /root/.cache/pip 2>/dev/null || true
progress done 100 done "Локальная среда установлена"
echo "MURF local: установка завершена"
