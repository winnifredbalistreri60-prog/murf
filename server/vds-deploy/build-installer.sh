#!/usr/bin/env bash
# Собирает самодостаточный установщик: murf-install.sh + tar.gz(relay) в base64 после маркера.
# Результат кладётся в ассеты приложения (его загружает экран «Развернуть на VDS»).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="${1:-$ROOT/android/app/src/main/assets/murf-install.sh}"
mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp)
cp "$HERE/murf-install.sh" "$TMP"
printf '\n__MURF_PAYLOAD_BELOW__\n' >> "$TMP"
tar -C "$ROOT/server/phone-relay" --owner=0 --group=0 --mtime='2026-01-01 00:00Z' --sort=name \
    -cz phone_relay.py murf_pairing.py murf_bots.py murf | base64 -w 76 >> "$TMP"
mv "$TMP" "$OUT"
chmod 644 "$OUT"
echo "$OUT ($(wc -c < "$OUT") байт, sha256 $(sha256sum "$OUT" | cut -c1-16))"
