#!/usr/bin/env bash
# Traz de volta os apps de sistema removidos pelo debloat.sh.
#   ./restaurar.sh removidos-AAAAMMDD-HHMMSS.txt   só os daquela vez
#   ./restaurar.sh                                 todos de pacotes.txt e pacotes-opcionais.txt
set -euo pipefail
cd "$(dirname "$0")"
adb_() { if [ -n "${ADB_DEVICE:-}" ]; then adb -s "$ADB_DEVICE" "$@"; else adb "$@"; fi; }
files=("$@"); [ ${#files[@]} -eq 0 ] && files=(pacotes.txt pacotes-opcionais.txt)
cat "${files[@]}" | sed 's/#.*//' | tr -d ' \t' | grep -v '^$' | while read -r pkg; do
  echo "  $pkg: $(adb_ shell cmd package install-existing --user 0 "$pkg" </dev/null 2>&1 | tr -d '\r' | head -1)"
done
