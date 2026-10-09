#!/bin/bash
# Собирает murf-local.tgz (Termux-скрипт + файлы для Ubuntu) в assets приложения и dist/.
set -e
cd "$(dirname "$0")"
R=$(cd ../.. && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/inner"
cp murf-local "$T/"; cp inner/setup.sh inner/svc.sh inner/nginx.conf inner/murf_api.py inner/desktop.sh "$T/inner/"
cp novnc-1.6.0.tgz "$T/inner/"
cp "$R/server/agent-screen/rfb_viewonly_proxy.py" "$R/server/agent-screen/index.html" "$T/inner/"
sed -n 's/^MURF_LOCAL_VERSION=//p' murf-local > "$T/VERSION"
chmod 755 "$T/murf-local" "$T/inner/"*.sh; chmod 644 "$T/inner/novnc-1.6.0.tgz"
A="$R/android/app/src/main/assets"; mkdir -p "$A" "$R/dist/murf-local"
tar --owner=0 --group=0 --mtime='2026-10-09 00:00Z' --sort=name -czf "$A/murf-local.tgz" -C "$T" .
cp "$A/murf-local.tgz" install.sh "$R/dist/murf-local/"
( cd "$R/dist/murf-local" && sha256sum murf-local.tgz > murf-local.tgz.sha256 )
ls -la "$A/murf-local.tgz"
