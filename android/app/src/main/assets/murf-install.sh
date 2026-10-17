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
  cp "$MURF_RELAY_SRC"/phone_relay.py "$MURF_RELAY_SRC"/murf_pairing.py "$MURF_RELAY_SRC"/murf_bots.py "$MURF_RELAY_SRC"/murf "$HOME_DIR/agent-phone/"
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
# боты MURF: отдельная служба, доступна только через relay (127.0.0.1:8792)
write_unit murf-bots "MURF: боты (Hermes ACP и установленные CLI, 127.0.0.1:8792)" "$HOME_DIR" \
  "/usr/bin/python3 -u $HOME_DIR/agent-phone/murf_bots.py" "Environment=MURF_BOTS_PORT=8792
UMask=0077"
if has_systemd; then
  systemctl daemon-reload
  systemctl enable murf-webui murf-relay murf-bots >/dev/null 2>&1
  systemctl restart murf-relay murf-bots
  if [ "$UPDATE" -eq 1 ] || ! systemctl is-active --quiet murf-webui; then systemctl restart murf-webui; fi
  echo "murf-webui: $(systemctl is-active murf-webui || true), murf-relay: $(systemctl is-active murf-relay || true), murf-bots: $(systemctl is-active murf-bots || true)"
else
  warn "systemd не запущен (контейнер?) — запускаю службы в фоне без автозапуска"
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
z9PP1/T5/wGMYHelAOABAA==
