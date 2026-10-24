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
