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

INSTALLER_VERSION=1
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
  cp "$MURF_RELAY_SRC"/phone_relay.py "$MURF_RELAY_SRC"/murf_pairing.py "$MURF_RELAY_SRC"/murf "$HOME_DIR/agent-phone/"
else
  die "в установщике нет файлов relay (соберите его через build-installer.sh)"
fi
chown -R "$SVC_USER:$SVC_USER" "$HOME_DIR/agent-phone"
chmod 755 "$HOME_DIR/agent-phone/murf"
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
if has_systemd; then
  systemctl daemon-reload
  systemctl enable murf-webui murf-relay >/dev/null 2>&1
  systemctl restart murf-relay
  if [ "$UPDATE" -eq 1 ] || ! systemctl is-active --quiet murf-webui; then systemctl restart murf-webui; fi
  echo "murf-webui: $(systemctl is-active murf-webui || true), murf-relay: $(systemctl is-active murf-relay || true)"
else
  warn "systemd не запущен (контейнер?) — запускаю службы в фоне без автозапуска"
  pkill -u "$SVC_USER" -f 'phone_relay.py' 2>/dev/null || true
  if ! pgrep -u "$SVC_USER" -f 'bootstrap.py|server.py' >/dev/null 2>&1; then
    ( as_user bash -c 'cd "$2"; set -a; . "$1"; set +a; exec nohup /usr/bin/python3 bootstrap.py --no-browser --skip-agent-install --foreground' _ "$WEBUI_ENV" "$WEBUI_DIR" \
      >"$HOME_DIR/.hermes/murf-webui.log" 2>&1 & )
  fi
  ( as_user env $RELAY_ENV nohup /usr/bin/python3 "$HOME_DIR/agent-phone/phone_relay.py" >"$HOME_DIR/agent-phone/relay.log" 2>&1 & )
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
