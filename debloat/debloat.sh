#!/usr/bin/env bash
# Remove do usuário 0 os pacotes de pacotes.txt (e de pacotes-opcionais.txt com --opcionais).
# Sem root e reversível: o app continua na partição do sistema e volta com ./restaurar.sh.
#   ./debloat.sh                    mostra o que sairia, sem remover nada
#   ./debloat.sh --aplicar          remove
#   ./debloat.sh --aplicar --opcionais
#   ADB_DEVICE=192.168.0.10:41279 ./debloat.sh --aplicar   com mais de um aparelho conectado
set -euo pipefail
cd "$(dirname "$0")"
APPLY=false; OPTIONAL=false
for a in "$@"; do
  case "$a" in
    --aplicar) APPLY=true ;;
    --opcionais) OPTIONAL=true ;;
    *) echo "opção desconhecida: $a"; exit 1 ;;
  esac
done
adb_() { if [ -n "${ADB_DEVICE:-}" ]; then adb -s "$ADB_DEVICE" "$@"; else adb "$@"; fi; }

model="$(adb_ shell getprop ro.product.model | tr -d '\r')"
echo "Aparelho: $model (Android $(adb_ shell getprop ro.build.version.release | tr -d '\r'))"
case "$model" in *g15*|*G15*) ;; *) echo "Atenção: a lista foi feita para o moto g15. Confira os pacotes antes de aplicar." ;; esac

lists=(pacotes.txt); $OPTIONAL && lists+=(pacotes-opcionais.txt)
installed="$(adb_ shell pm list packages --user 0 | sed 's/^package://' | tr -d '\r')"
stamp="$(date +%Y%m%d-%H%M%S)"
$APPLY && adb_ shell pm list packages -f > "antes-$stamp.txt"

removed=0
while read -r pkg; do
  [ -z "$pkg" ] && continue
  if ! grep -qx "$pkg" <<<"$installed"; then continue; fi
  if ! $APPLY; then echo "  sairia: $pkg"; continue; fi
  r="$(adb_ shell pm uninstall -k --user 0 "$pkg" </dev/null 2>&1 | tr -d '\r')"
  echo "  $pkg: $r"
  if [ "$r" = "Success" ]; then echo "$pkg" >> "removidos-$stamp.txt"; removed=$((removed+1)); fi
done < <(cat "${lists[@]}" | sed 's/#.*//' | tr -d ' \t' | grep -v '^$')

if $APPLY; then
  echo "Removidos: $removed. Lista em removidos-$stamp.txt; a lista completa de antes em antes-$stamp.txt."
  echo "Para desfazer: ./restaurar.sh removidos-$stamp.txt"
else
  echo "Nada foi removido. Rode com --aplicar para remover."
fi
