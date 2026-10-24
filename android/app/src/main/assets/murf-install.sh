#!/usr/bin/env bash
# MURF — установка Hermes Agent + hermes-webui + relay MURF на чистый VDS (Ubuntu 22.04/24.04, Debian 12/13).
#
# Идемпотентен: повторный запуск ничего не ломает, пропускает готовые шаги и чинит недостающие.
# Запуск (от root):
#   bash murf-install.sh [--domain example.com] [--email you@example.com] [--no-tls]
#                        [--user murf] [--update] [--skip-agent]
# Без --domain используется <ip>.sslip.io (публичный wildcard-DNS), чтобы получить HTTPS от Let's Encrypt.
#
# Протокол вывода для приложения (по строке):
#   MURF:STEP <n> <всего> <текст>    начало шага
#   MURF:WARN <текст>                предупреждение
#   MURF:ERROR <текст>               ошибка (скрипт завершается с кодом != 0)
#   MURF:DONE <json>                 готово: {"server": "...", "payload": "murf://pair?...", ...}
# Пароли не печатаются никогда: пароль WebUI генерируется на сервере и хранится в 0600-файлах.
set -Eeuo pipefail
umask 022

INSTALLER_VERSION=2
DOMAIN=""
EMAIL=""
SVC_USER="murf"
NO_TLS=0
UPDATE=0
SKIP_AGENT=0
WEBUI_PORT=8787
PHONE_PORT=8790
AGENT_PORT=8791
HERMES_INSTALL_URL="${MURF_HERMES_INSTALL_URL:-https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh}"
WEBUI_REPO="${MURF_WEBUI_REPO:-https://github.com/nesquena/hermes-webui.git}"

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --user) SVC_USER="${2:-}"; shift 2 ;;
    --no-tls) NO_TLS=1; shift ;;
    --update) UPDATE=1; shift ;;
    --skip-agent) SKIP_AGENT=1; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "MURF:ERROR неизвестный параметр: $1"; exit 2 ;;
  esac
done

TOTAL=10
N=0
step() { N=$((N + 1)); echo "MURF:STEP $N $TOTAL $*"; }
warn() { echo "MURF:WARN $*"; }
die() { echo "MURF:ERROR $*"; exit 1; }
trap 'rc=$?; echo "MURF:ERROR шаг $N: команда завершилась с кодом $rc (строка $LINENO)"; exit $rc' ERR

valid_host() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?)+$'; }
[ "$SVC_USER" = "$(printf '%s' "$SVC_USER" | tr -cd 'a-z0-9_-')" ] && [ -n "$SVC_USER" ] || die "недопустимое имя пользователя: $SVC_USER"
[ -z "$DOMAIN" ] || valid_host "$DOMAIN" || die "недопустимый домен: $DOMAIN"
[ -z "$EMAIL" ] || printf '%s' "$EMAIL" | grep -Eq '^[^@ ]+@[^@ ]+\.[^@ ]+$' || die "недопустимый e-mail: $EMAIL"

as_user() { ( cd "$HOME_DIR" 2>/dev/null || cd /; exec runuser -u "$SVC_USER" -- env HOME="$HOME_DIR" USER="$SVC_USER" LOGNAME="$SVC_USER" PATH="$HOME_DIR/.local/bin:/usr/local/bin:/usr/bin:/bin" "$@" ); }
has_systemd() { [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; }

# ---------------------------------------------------------------- 1. проверки
step "Проверка сервера"
[ "$(id -u)" -eq 0 ] || die "нужны права root (запустите через sudo)"
. /etc/os-release 2>/dev/null || die "не удалось определить ОС"
case "${ID:-}" in
  ubuntu|debian) ;;
  *) case " ${ID_LIKE:-} " in *" debian "*|*" ubuntu "*) warn "ОС ${PRETTY_NAME:-$ID} не проверялась, продолжаю как Debian" ;; *) die "поддерживаются Ubuntu и Debian, найдено: ${PRETTY_NAME:-$ID}" ;; esac ;;
esac
case "$(uname -m)" in x86_64|amd64|aarch64|arm64) ;; *) die "архитектура $(uname -m) не поддерживается" ;; esac
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
[ "$MEM_MB" -ge 900 ] || warn "мало памяти (${MEM_MB} МБ), рекомендуется от 2 ГБ"
FREE_MB=$(df -Pm / | awk 'NR==2 {print $4}')
[ "$FREE_MB" -ge 3000 ] || die "мало места на диске: ${FREE_MB} МБ свободно, нужно от 3 ГБ"
echo "ОС: ${PRETTY_NAME:-$ID}, память ${MEM_MB} МБ, свободно ${FREE_MB} МБ"

if [ -z "$DOMAIN" ] && [ "$NO_TLS" -eq 0 ]; then
  PUBIP=$(curl -4 -fsS --max-time 10 https://api.ipify.org 2>/dev/null || curl -4 -fsS --max-time 10 https://ifconfig.me 2>/dev/null || true)
  printf '%s' "$PUBIP" | grep -Eq '^[0-9]+(\.[0-9]+){3}$' || die "не удалось определить внешний IP; укажите --domain"
  DOMAIN="$(printf '%s' "$PUBIP" | tr . -).sslip.io"
  echo "Домен не задан — использую $DOMAIN"
fi
if [ "$NO_TLS" -eq 1 ]; then
  SCHEME=http
  [ -n "$DOMAIN" ] || DOMAIN="_"
else
  SCHEME=https
fi
PUBLIC_HOST="$DOMAIN"
[ "$PUBLIC_HOST" != "_" ] || PUBLIC_HOST="$(hostname -I 2>/dev/null | awk '{print $1}')"
PUBLIC_BASE="$SCHEME://$PUBLIC_HOST"

# ---------------------------------------------------------------- 2. пакеты
step "Установка системных пакетов"
export DEBIAN_FRONTEND=noninteractive
APT="apt-get -q -y -o DPkg::Lock::Timeout=300"
PKGS="ca-certificates curl git python3 python3-venv nginx qrencode openssl sudo procps libatomic1 xz-utils"
[ "$NO_TLS" -eq 1 ] || PKGS="$PKGS certbot python3-certbot-nginx"
# Bot Desktop / экран агента: пакеты только на x86_64 (проверено). ARM — явно не поддерживается.
ARCH=$(uname -m)
MURF_SCREEN_ARCH_OK=0
case "$ARCH" in x86_64|amd64) MURF_SCREEN_ARCH_OK=1 ;; esac
if [ "$MURF_SCREEN_ARCH_OK" -eq 1 ]; then
  PKGS="$PKGS tigervnc-standalone-server tigervnc-tools xfce4-panel xfwm4 xfdesktop4 xfce4-settings xfce4-terminal dbus-x11 x11-utils x11-xkb-utils xauth xdotool websockify fonts-dejavu-core"
else
  echo "Архитектура $ARCH: пакеты экрана агента не ставятся — экран не поддерживается (server/docs/AGENT-SCREEN-SUPPORT.md)"
fi
missing=""
for p in $PKGS; do dpkg -s "$p" >/dev/null 2>&1 || missing="$missing $p"; done
if [ -n "$missing" ]; then
  $APT update >/dev/null
  $APT install --no-install-recommends $missing
else
  echo "Все пакеты уже установлены"
fi

# ---------------------------------------------------------------- 3. пользователь
step "Пользователь $SVC_USER"
if ! id "$SVC_USER" >/dev/null 2>&1; then
  useradd -m -s /bin/bash "$SVC_USER"
  echo "Создан пользователь $SVC_USER"
else
  echo "Пользователь уже есть"
fi
HOME_DIR=$(getent passwd "$SVC_USER" | cut -d: -f6)
[ -d "$HOME_DIR" ] || die "нет домашнего каталога $HOME_DIR"
install -d -o "$SVC_USER" -g "$SVC_USER" -m 700 "$HOME_DIR/.hermes" "$HOME_DIR/agent-phone"
install -d -o "$SVC_USER" -g "$SVC_USER" -m 755 "$HOME_DIR/workspace"

# ---------------------------------------------------------------- 4. Hermes Agent
step "Hermes Agent"
AGENT_DIR="$HOME_DIR/.hermes/hermes-agent"
if [ "$SKIP_AGENT" -eq 1 ]; then
  warn "установка Hermes Agent пропущена (--skip-agent)"
elif [ -f "$AGENT_DIR/.hermes-bootstrap-complete" ] && [ "$UPDATE" -eq 0 ]; then
  echo "Hermes Agent уже установлен ($AGENT_DIR)"
else
  TMP_INST=$(mktemp /tmp/hermes-install.XXXXXX.sh)
  curl -fsSL --retry 3 "$HERMES_INSTALL_URL" -o "$TMP_INST" || die "не удалось скачать официальный установщик Hermes Agent"
  chmod 644 "$TMP_INST"
  echo "Запускаю официальный установщик Hermes Agent (несколько минут)…"
  as_user env HERMES_INSTALL_VERBOSE=1 bash "$TMP_INST" --non-interactive --skip-browser --skip-computer-use </dev/null \
    || { rm -f "$TMP_INST"; die "официальный установщик Hermes Agent завершился с ошибкой (журнал: $HOME_DIR/.hermes/logs/install.log)"; }
  rm -f "$TMP_INST"
fi

# ---------------------------------------------------------------- 5. hermes-webui
step "hermes-webui"
WEBUI_DIR="$HOME_DIR/hermes-webui"
if [ -d "$WEBUI_DIR/.git" ]; then
  if [ "$UPDATE" -eq 1 ]; then as_user git -C "$WEBUI_DIR" pull --ff-only || warn "не удалось обновить hermes-webui (оставлена текущая версия)"; fi
  echo "hermes-webui: $(as_user git -C "$WEBUI_DIR" log -1 --format='%h %cd' --date=short)"
else
  as_user git clone --depth 1 "$WEBUI_REPO" "$WEBUI_DIR"
fi

# ---------------------------------------------------------------- 6. секреты
step "Пароль WebUI (генерируется на сервере, не показывается)"
WEBUI_ENV="$HOME_DIR/.hermes/webui.env"
touch "$WEBUI_ENV"; chown "$SVC_USER:$SVC_USER" "$WEBUI_ENV"; chmod 600 "$WEBUI_ENV"
if ! grep -q '^HERMES_WEBUI_PASSWORD=.' "$WEBUI_ENV"; then
  PW=$(openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-32)
  printf 'HERMES_WEBUI_PASSWORD=%s\n' "$PW" >> "$WEBUI_ENV"
  unset PW
  echo "Пароль создан"
else
  echo "Пароль уже задан"
fi
grep -q '^HERMES_WEBUI_HOST=' "$WEBUI_ENV" || echo "HERMES_WEBUI_HOST=127.0.0.1" >> "$WEBUI_ENV"
grep -q '^HERMES_WEBUI_PORT=' "$WEBUI_ENV" || echo "HERMES_WEBUI_PORT=$WEBUI_PORT" >> "$WEBUI_ENV"
grep -q '^HERMES_WEBUI_DEFAULT_WORKSPACE=' "$WEBUI_ENV" || echo "HERMES_WEBUI_DEFAULT_WORKSPACE=$HOME_DIR/workspace" >> "$WEBUI_ENV"

# ---------------------------------------------------------------- 7. relay MURF
step "Relay MURF (привязка по QR)"
PAYLOAD_LINE=$(awk '/^__MURF_PAYLOAD_BELOW__$/ {print NR + 1; exit 0}' "$0" || true)
if [ -n "$PAYLOAD_LINE" ]; then
  tail -n +"$PAYLOAD_LINE" "$0" | base64 -d | tar -xz -C "$HOME_DIR/agent-phone" --no-same-owner
elif [ -n "${MURF_RELAY_SRC:-}" ] && [ -d "$MURF_RELAY_SRC" ]; then
  cp "$MURF_RELAY_SRC"/phone_relay.py "$MURF_RELAY_SRC"/murf_pairing.py "$MURF_RELAY_SRC"/murf_bots.py "$MURF_RELAY_SRC"/murf_screen_lease.py "$MURF_RELAY_SRC"/murf_screen_desktop.py "$MURF_RELAY_SRC"/murf_reports.py "$MURF_RELAY_SRC"/murf "$HOME_DIR/agent-phone/"
else
  die "в установщике нет файлов relay (соберите его через build-installer.sh)"
fi
chown -R "$SVC_USER:$SVC_USER" "$HOME_DIR/agent-phone"
chmod 755 "$HOME_DIR/agent-phone/murf"
[ -f "$HOME_DIR/agent-phone/murf_screen_desktop.py" ] && chmod 755 "$HOME_DIR/agent-phone/murf_screen_desktop.py"
cat > /usr/local/bin/murf <<EOF
#!/bin/sh
# обёртка: команды murf выполняются от пользователя $SVC_USER
if [ "\$(id -un)" = "$SVC_USER" ]; then exec python3 "$HOME_DIR/agent-phone/murf" "\$@"; fi
exec runuser -u "$SVC_USER" -- env HOME="$HOME_DIR" AGENT_PORT=$AGENT_PORT python3 "$HOME_DIR/agent-phone/murf" "\$@"
EOF
chmod 755 /usr/local/bin/murf
# пароль WebUI для выдачи сессий привязанным устройствам (0600, у пользователя сервиса)
grep '^HERMES_WEBUI_PASSWORD=' "$WEBUI_ENV" | head -1 | cut -d= -f2- \
  | as_user python3 "$HOME_DIR/agent-phone/murf" set-webui-password >/dev/null

# ---------------------------------------------------------------- 8. службы
step "Службы systemd"
RELAY_ENV="PHONE_PORT=$PHONE_PORT AGENT_PORT=$AGENT_PORT PUBLIC_BASE=$PUBLIC_BASE MURF_WEBUI_URL=http://127.0.0.1:$WEBUI_PORT"
write_unit() {  # name, description, workdir, exec, extra
  cat > "/etc/systemd/system/$1.service" <<EOF
[Unit]
Description=$2
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SVC_USER
Group=$SVC_USER
WorkingDirectory=$3
Environment=HOME=$HOME_DIR
Environment=PATH=$HOME_DIR/.local/bin:/usr/local/bin:/usr/bin:/bin
Environment=NO_PROXY=localhost,127.0.0.1,::1
$5
ExecStart=$4
Restart=on-failure
RestartSec=5
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
}
SKIP_AGENT_INSTALL_FLAG="--skip-agent-install"
write_unit murf-webui "MURF: Hermes WebUI (127.0.0.1:$WEBUI_PORT)" "$WEBUI_DIR" \
  "/usr/bin/python3 bootstrap.py --no-browser $SKIP_AGENT_INSTALL_FLAG --foreground" \
  "EnvironmentFile=$WEBUI_ENV"
RELAY_ENV_LINES=""
for kv in $RELAY_ENV; do RELAY_ENV_LINES="${RELAY_ENV_LINES}Environment=$kv
"; done
write_unit murf-relay "MURF: relay (QR-привязка, 127.0.0.1:$PHONE_PORT/$AGENT_PORT)" "$HOME_DIR/agent-phone" \
  "/usr/bin/python3 $HOME_DIR/agent-phone/phone_relay.py" "$RELAY_ENV_LINES"
# боты MURF: отдельная служба, доступна только через relay (127.0.0.1:8792)
write_unit murf-bots "MURF: боты (Hermes ACP и установленные CLI, 127.0.0.1:8792)" "$HOME_DIR" \
  "/usr/bin/python3 -u $HOME_DIR/agent-phone/murf_bots.py" "Environment=MURF_BOTS_PORT=8792
UMask=0077"
# --- Bot Desktop keep-alive (экран агента): только при доказанном стеке x86_64+Xvnc+Xfce ---
enable_bot_desktop_unit() {
  local hermes_py="" helper="$HOME_DIR/agent-phone/murf_screen_desktop.py" agent="$HOME_DIR/.hermes/hermes-agent"
  if [ "${MURF_SCREEN_ARCH_OK:-0}" -ne 1 ]; then
    warn "murf-bot-desktop: архитектура $(uname -m) не поддерживается для экрана агента (нужен x86_64)"
    return 0
  fi
  if ! has_systemd; then
    warn "murf-bot-desktop: нет systemd — keep-alive unit не ставится"
    return 0
  fi
  if [ ! -f "$helper" ]; then
    warn "murf-bot-desktop: нет $helper — пропуск"
    return 0
  fi
  if [ ! -d "$agent/tools/bot_desktop" ]; then
    warn "murf-bot-desktop: нет Hermes tools/bot_desktop — пропуск"
    return 0
  fi
  if ! command -v Xvnc >/dev/null 2>&1 && ! command -v Xtigervnc >/dev/null 2>&1; then
    warn "murf-bot-desktop: нет Xvnc — экран агента требует TigerVNC; unit не включаю"
    return 0
  fi
  for b in xfwm4 xfce4-panel xfdesktop xfsettingsd; do
    if ! command -v "$b" >/dev/null 2>&1; then
      warn "murf-bot-desktop: нет $b (Xfce) — unit не включаю"
      return 0
    fi
  done
  hermes_py=$(as_user bash -c '
    if [ -n "${HERMES_PYTHON:-}" ] && [ -x "$HERMES_PYTHON" ]; then echo "$HERMES_PYTHON"; exit 0; fi
    best=""; for d in "$HOME"/.hermes/tools/python-*/bin/python3; do [ -x "$d" ] || continue; best="$d"; done
    if [ -n "$best" ]; then echo "$best"; exit 0; fi
    [ -x "$HOME/.hermes/hermes-agent/venv/bin/python" ] && echo "$HOME/.hermes/hermes-agent/venv/bin/python" && exit 0
    exit 1
  ') || hermes_py=""
  if [ -z "$hermes_py" ] || [ ! -x "$hermes_py" ]; then
    warn "murf-bot-desktop: не найден Hermes tools python — unit не включаю"
    return 0
  fi
  as_user python3 -c '
import os, pathlib
home = pathlib.Path(os.path.expanduser("~/.hermes"))
try:
    import yaml
except ImportError:
    raise SystemExit(0)
for name in ("config.yaml", "config.yml"):
    p = home / name
    if not p.is_file():
        continue
    data = yaml.safe_load(p.read_text()) or {}
    if not isinstance(data, dict):
        continue
    bd = data.setdefault("bot_desktop", {})
    if not isinstance(bd, dict):
        data["bot_desktop"] = {}
        bd = data["bot_desktop"]
    bd["auto_start"] = True
    bd.setdefault("idle_stop_minutes", 0)
    p.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False))
    break
' || true
  cat > /etc/systemd/system/murf-bot-desktop.service <<EOF
[Unit]
Description=MURF: Hermes Bot Desktop (Xvnc) keep-alive for Agent Screen
After=network-online.target murf-relay.service
Wants=network-online.target

[Service]
Type=simple
User=$SVC_USER
Group=$SVC_USER
WorkingDirectory=$HOME_DIR/.hermes/hermes-agent
Environment=HOME=$HOME_DIR
Environment=HERMES_HOME=$HOME_DIR/.hermes
Environment=HERMES_AGENT_DIR=$HOME_DIR/.hermes/hermes-agent
Environment=BOT_DESKTOP_WATCH_SEC=15
Environment=PATH=$HOME_DIR/.local/bin:/usr/local/bin:/usr/bin:/bin
Environment=NO_PROXY=localhost,127.0.0.1,::1
ExecStart=$hermes_py -I $HOME_DIR/agent-phone/murf_screen_desktop.py watch
Restart=on-failure
RestartSec=5
KillMode=process

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable murf-bot-desktop >/dev/null 2>&1 || true
  systemctl restart murf-bot-desktop
  echo "murf-bot-desktop: $(systemctl is-active murf-bot-desktop || true) (Xvnc keep-alive)"
}


if has_systemd; then
  systemctl daemon-reload
  systemctl enable murf-webui murf-relay murf-bots >/dev/null 2>&1
  systemctl restart murf-relay murf-bots
  if [ "$UPDATE" -eq 1 ] || ! systemctl is-active --quiet murf-webui; then systemctl restart murf-webui; fi
  echo "murf-webui: $(systemctl is-active murf-webui || true), murf-relay: $(systemctl is-active murf-relay || true), murf-bots: $(systemctl is-active murf-bots || true)"
  enable_bot_desktop_unit
else
  warn "systemd не запущен (контейнер?) — запускаю службы в фоне без автозапуска"
  warn "murf-bot-desktop: без systemd keep-alive не ставится — экран агента в этом режиме не поддерживается"
  pkill -u "$SVC_USER" -f 'phone_relay.py' 2>/dev/null || true
  if ! pgrep -u "$SVC_USER" -f 'bootstrap.py|server.py' >/dev/null 2>&1; then
    ( as_user bash -c 'cd "$2"; set -a; . "$1"; set +a; exec nohup /usr/bin/python3 bootstrap.py --no-browser --skip-agent-install --foreground' _ "$WEBUI_ENV" "$WEBUI_DIR" \
      >"$HOME_DIR/.hermes/murf-webui.log" 2>&1 & )
  fi
  ( as_user env $RELAY_ENV nohup /usr/bin/python3 "$HOME_DIR/agent-phone/phone_relay.py" >"$HOME_DIR/agent-phone/relay.log" 2>&1 & )
  pkill -u "$SVC_USER" -f 'murf_bots.py' 2>/dev/null || true
  ( as_user env MURF_BOTS_PORT=8792 nohup /usr/bin/python3 -u "$HOME_DIR/agent-phone/murf_bots.py" >"$HOME_DIR/agent-phone/bots.log" 2>&1 & )
fi

# ---------------------------------------------------------------- 9. nginx + HTTPS
step "nginx и HTTPS ($PUBLIC_BASE)"
cat > /etc/nginx/conf.d/murf-limits.conf <<'EOF'
limit_req_zone $binary_remote_addr zone=murf_login:1m rate=5r/m;
limit_req_zone $binary_remote_addr zone=murf_all:2m rate=20r/s;
limit_req_zone $binary_remote_addr zone=murf_pair:1m rate=10r/m;
map $http_upgrade $murf_conn { default upgrade; '' close; }
EOF
SITE=/etc/nginx/sites-available/murf.conf
if [ -f "$SITE" ] && grep -q 'managed by Certbot' "$SITE" && [ "$UPDATE" -eq 0 ]; then
  echo "Конфигурация nginx с сертификатом уже есть — не трогаю"
else
  cat > "$SITE" <<EOF
# MURF — сгенерировано murf-install.sh v$INSTALLER_VERSION
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    client_max_body_size 50m;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy same-origin always;

    location = /_murf_auth {
        internal;
        proxy_pass http://127.0.0.1:$WEBUI_PORT/api/profiles;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header Cookie \$http_cookie;
    }
    location /phone/api/ {
        auth_request /_murf_auth;
        proxy_pass http://127.0.0.1:$PHONE_PORT;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_read_timeout 120s;
    }
    location /phone/up/pair/ {
        limit_req zone=murf_pair burst=10 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:$PHONE_PORT;
        proxy_set_header X-Real-IP \$remote_addr;
    }
    location /phone/up/ {
        limit_req zone=murf_all burst=20 nodelay;
        proxy_pass http://127.0.0.1:$PHONE_PORT;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_request_buffering off;
    }
    location /phone/ { return 404; }
    location = /api/auth/login {
        limit_req zone=murf_login burst=5 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:$WEBUI_PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    location / {
        limit_req zone=murf_all burst=100 nodelay;
        proxy_pass http://127.0.0.1:$WEBUI_PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$murf_conn;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
EOF
fi
ln -sf "$SITE" /etc/nginx/sites-enabled/murf.conf
nginx -t 2>&1 | tail -2 || die "nginx -t: ошибка конфигурации"
if has_systemd; then systemctl enable nginx >/dev/null 2>&1 || true; systemctl reload nginx 2>/dev/null || systemctl restart nginx; else nginx -s reload 2>/dev/null || nginx; fi
if [ "$NO_TLS" -eq 0 ]; then
  if [ -d "/etc/letsencrypt/live/$DOMAIN" ] && grep -q 'managed by Certbot' "$SITE"; then
    echo "Сертификат для $DOMAIN уже есть"
  else
    if [ -n "$EMAIL" ]; then CB_MAIL=(-m "$EMAIL"); else CB_MAIL=(--register-unsafely-without-email); fi
    certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos "${CB_MAIL[@]}" --redirect --keep-until-expiring \
      || die "не удалось получить сертификат Let's Encrypt для $DOMAIN: проверьте, что DNS указывает на этот сервер и порт 80 открыт"
  fi
fi
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q 'Status: active'; then
  ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; echo "ufw: открыты 80 и 443"
fi

# ---------------------------------------------------------------- 10. проверка и привязка
step "Проверка и код привязки"
ok=0
for i in $(seq 1 90); do
  if curl -fsS --max-time 3 "http://127.0.0.1:$WEBUI_PORT/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
[ "$ok" -eq 1 ] || die "WebUI не ответил на /health за 3 минуты (journalctl -u murf-webui)"
for i in $(seq 1 15); do
  curl -fsS --max-time 3 "http://127.0.0.1:$AGENT_PORT/pair/devices" >/dev/null 2>&1 && break
  sleep 1
done
PAIR_JSON=$(as_user env AGENT_PORT=$AGENT_PORT python3 "$HOME_DIR/agent-phone/murf" pair --json --ttl 300 --label vds-deploy --public-base "$PUBLIC_BASE") \
  || die "relay не выдал код привязки (journalctl -u murf-relay)"
echo "MURF:DONE $PAIR_JSON"
exit 0

__MURF_PAYLOAD_BELOW__
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
