#!/bin/bash
# MURF local mode: DeepSeek Harness (MIT, https://github.com/deepseek-ai/deepseek-harness) внутри proot Ubuntu.
# Node 22 (официальный tarball linux-arm64/x64, glibc) → /opt/node22; dsh (закреплённая версия) → /opt/dsh.
# В Termux (bionic) dsh не работает: нативный модуль @deepseek-ai/node-addon-system есть только для linux glibc/musl.
# Идемпотентно; прогресс в $MURF_HOME/dsh-progress.json; PID в $MURF_HOME/dsh-install.pid.
set -uo pipefail
export HOME=/root LANG=C.UTF-8 LC_ALL=C.UTF-8
MURF_HOME=${MURF_HOME:-/root/.murf}
NODE_VER=${MURF_NODE_VER:-v22.23.4}
DSH_VER=${MURF_DSH_VER:-0.2.1-rc.2}
mkdir -p "$MURF_HOME/logs" "$MURF_HOME/dsh"; chmod 700 "$MURF_HOME"
LOG="$MURF_HOME/logs/dsh-install.log"; exec > >(tee -a "$LOG") 2>&1
P="$MURF_HOME/dsh-progress.json"
PIDF="$MURF_HOME/dsh-install.pid"
DONE=0
FAILED=0
PCT=0
pr() { printf '{"state":"%s","pct":%s,"msg":"%s","ts":%s}\n' "$1" "$2" "$3" "$(date +%s)" > "$P.tmp" && mv -f "$P.tmp" "$P"; echo "[$(date '+%F %T')] [$1 $2%] $3"; }
fail() { FAILED=1; pr error "${PCT:-0}" "$1"; echo "ОШИБКА: $1"; exit 1; }
cleanup() {
  rm -f "$PIDF"
  if [ "$DONE" != 1 ] && [ "$FAILED" != 1 ]; then
    pr error "${PCT:-0}" "установка прервана (процесс убит/закрыт). Откройте Termux, держите экран, повторите."
  fi
}
echo $$ > "$PIDF"
trap cleanup EXIT
trap 'exit 130' INT TERM

case "$(uname -m)" in aarch64|arm64) ARCH=arm64 ;; x86_64) ARCH=x64 ;; *) fail "архитектура $(uname -m) не поддерживается" ;; esac
/opt/node22/bin/node -v 2>/dev/null | grep -q "^$NODE_VER$" || for b in curl xz sha256sum; do command -v $b >/dev/null || NEED=1; done
if [ -n "${NEED:-}" ]; then
  PCT=2; pr running 2 "apt: curl, xz-utils"
  DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends curl xz-utils ca-certificates coreutils \
    || fail "apt-get install curl xz-utils не прошёл"
fi
PCT=5; pr running 5 "Node.js $NODE_VER ($ARCH)"
if ! /opt/node22/bin/node -v 2>/dev/null | grep -q "^$NODE_VER$"; then
  T=/tmp/node22.tar.xz
  curl -fL --retry 5 -C - -o "$T" "https://nodejs.org/dist/$NODE_VER/node-$NODE_VER-linux-$ARCH.tar.xz" || fail "не скачать Node.js с nodejs.org"
  curl -fsSL --retry 3 "https://nodejs.org/dist/$NODE_VER/SHASUMS256.txt" | grep " node-$NODE_VER-linux-$ARCH.tar.xz$" | awk '{print $1"  '"$T"'"}' | sha256sum -c - || fail "контрольная сумма Node.js не совпала"
  rm -rf /opt/node22.new && mkdir -p /opt/node22.new && tar -xJf "$T" -C /opt/node22.new --strip-components=1 || fail "не распаковать Node.js"
  rm -rf /opt/node22 && mv /opt/node22.new /opt/node22 && rm -f "$T"
fi
export PATH=/opt/node22/bin:$PATH
# длинные таймауты: в proot/Termux npm часто «молчит» минутами на мобильной сети
export npm_config_fetch_retries=5
export npm_config_fetch_retry_mintimeout=20000
export npm_config_fetch_retry_maxtimeout=120000
export npm_config_fetch_timeout=600000
export npm_config_network_timeout=600000
node -v
PCT=30; pr running 30 "DeepSeek Harness $DSH_VER: npm install (5–20 мин)"
if ! [ "$(/opt/dsh/bin/dsh --version 2>/dev/null)" = "$DSH_VER" ]; then
  rm -rf /opt/dsh.new
  MAIN_PID=$$
  (
    p=30
    while kill -0 "$MAIN_PID" 2>/dev/null; do
      sleep 20
      p=$((p + 2)); [ "$p" -gt 78 ] && p=78
      printf '{"state":"running","pct":%s,"msg":"%s","ts":%s}\n' "$p" \
        "DeepSeek Harness $DSH_VER: npm install… ещё идёт (не сворачивайте Termux)" "$(date +%s)" \
        > "$P.tmp" && mv -f "$P.tmp" "$P"
    done
  ) &
  HB=$!
  NPM_OK=0
  for attempt in 1 2 3; do
    echo "npm install attempt $attempt/3 → /opt/dsh.new (@deepseek-ai/dsh@$DSH_VER)"
    if npm install -g --no-fund --no-audit --prefix /opt/dsh.new "@deepseek-ai/dsh@$DSH_VER"; then
      NPM_OK=1
      break
    fi
    echo "npm attempt $attempt failed; retry in 8s"
    sleep 8
  done
  kill "$HB" 2>/dev/null || true
  wait "$HB" 2>/dev/null || true
  [ "$NPM_OK" = 1 ] || fail "npm install dsh не прошёл после 3 попыток (см. $LOG)"
  rm -rf /opt/dsh && mv /opt/dsh.new /opt/dsh
  # npm записывает в bin абсолютные ссылки на /opt/dsh.new
  ln -sf ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js /opt/dsh/bin/dsh
fi
PCT=85; pr running 85 "Проверка запуска dsh"
V=$(/opt/dsh/bin/dsh --version 2>&1 | tail -1); echo "dsh --version: $V"
[ "$V" = "$DSH_VER" ] || fail "dsh не запускается: $V"
find /opt/dsh -path "*node-addon-system-linux-$ARCH/bin/glibc/system.node" | grep -q . \
  && echo "нативный модуль linux-$ARCH (glibc): есть" || echo "ВНИМАНИЕ: нет нативного модуля node-addon-system-linux-$ARCH"
npm cache clean --force >/dev/null 2>&1 || true
echo "$DSH_VER" > "$MURF_HOME/dsh/version"
DONE=1
pr done 100 "DeepSeek Harness $DSH_VER установлен"
