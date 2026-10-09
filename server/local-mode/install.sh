#!/data/data/com.termux/files/usr/bin/bash
# Ручная установка локальной среды MURF в Termux:
#   curl -fsSL https://github.com/__OWNER__/murf/releases/latest/download/install.sh | bash
set -e
BASE="${MURF_BASE:-https://github.com/__OWNER__/murf/releases/latest/download}"
command -v curl >/dev/null || pkg install -y curl
mkdir -p ~/.murf-local
curl -fsSL "$BASE/murf-local.tgz" -o ~/.murf-local/bundle.tgz
tar -xzf ~/.murf-local/bundle.tgz -C ~/.murf-local && rm -f ~/.murf-local/bundle.tgz
chmod 755 ~/.murf-local/murf-local && ln -sf ~/.murf-local/murf-local "$PREFIX/bin/murf-local"
echo "murf-local $(cat ~/.murf-local/VERSION) установлен. Дальше: murf-local install && murf-local start"
[ "${1:-}" = "--install" ] && exec murf-local install
