#!/usr/bin/env bash
# Fase 0, prueba 2: exporta el volante desde una Raspberry Pi / equipo Linux con el USB/IP del kernel.
# Si G HUB ve el volante con force feedback en la PC, el protocolo funciona con tu modelo.
#
#   sudo ./linux-baseline.sh          # exporta el primer dispositivo Logitech
#   sudo ./linux-baseline.sh 1-1.3    # exporta un busid concreto
#   sudo ./linux-baseline.sh --stop   # deja de exportar
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Ejecuta con sudo" >&2; exit 1; }

if ! command -v usbip >/dev/null; then
    echo "Instalando usbip..."
    if command -v apt-get >/dev/null; then
        # Raspberry Pi OS / Debian: usbip; Ubuntu: linux-tools-generic
        apt-get install -y usbip 2>/dev/null || apt-get install -y "linux-tools-$(uname -r)" linux-tools-generic
    else
        echo "Instala el paquete usbip de tu distribución" >&2; exit 1
    fi
fi

modprobe usbip-core
modprobe usbip-host

if [[ ${1:-} == --stop ]]; then
    for id in $(usbip list -l | sed -n 's/.*busid \([0-9.-]*\).*/\1/p'); do usbip unbind -b "$id" 2>/dev/null || true; done
    pkill usbipd || true
    echo "Exportación detenida"
    exit 0
fi

busid=${1:-}
if [[ -z $busid ]]; then
    # Línea típica: " - busid 1-1.3 (046d:c24f)"
    busid=$(usbip list -l | sed -n 's/.*busid \([0-9.-]*\) (046d:.*/\1/p' | head -n1)
    [[ -n $busid ]] || { echo "No encontré ningún dispositivo Logitech (046d). Salida de 'usbip list -l':" >&2; usbip list -l; exit 1; }
fi

pgrep -x usbipd >/dev/null || usbipd -D
usbip bind -b "$busid"
ip=$(hostname -I | awk '{print $1}')

cat <<EOF

Volante exportado (busid $busid). En la PC con Windows:
    usbip.exe list -r $ip
    usbip.exe attach -r $ip -b $busid

Después abre G HUB, calibra y prueba el force feedback. Anota el resultado en docs/fase0-validacion.md.
Si el volante se re-enumera (cambia de modo), 'usbip list -l' mostrará otro product ID: vuelve a correr este script.
EOF
