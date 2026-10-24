#!/bin/sh
# Miracle Klipper supervisor for the official Debian Bookworm rootfs.
# Runs inside PRoot; systemd is not PID 1 here.
set -eu

ROOT="${MIRACLE_KLIPPER_ROOT:-/}"
DATA="${MIRACLE_KLIPPER_DATA:-/home/klipper/printer_data}"
LOGS="$DATA/logs"
COMMS="$DATA/comms"
CONFIG="$DATA/config"
PIDDIR="$DATA/run"
KLIPPER_SRC="$ROOT/opt/klipper"
MOONRAKER_SRC="$ROOT/opt/moonraker"
KLIPPER_PY="$ROOT/opt/klipper-venv/bin/python"
MOONRAKER_PY="$ROOT/opt/moonraker-venv/bin/python"
NGINX_BIN="${NGINX_BIN:-/usr/sbin/nginx}"
KLIPPER_USER_HOME="/home/klipper"

mkdir -p "$LOGS" "$COMMS" "$CONFIG" "$PIDDIR" "$DATA/gcodes"
[ -f "$CONFIG/printer.cfg" ] || { echo "Configuration absente : $CONFIG/printer.cfg" >&2; exit 2; }
[ -f "$CONFIG/moonraker.conf" ] || { echo "Configuration absente : $CONFIG/moonraker.conf" >&2; exit 2; }
[ -x "$KLIPPER_PY" ] || { echo "Python Klipper absent : $KLIPPER_PY" >&2; exit 2; }
[ -x "$MOONRAKER_PY" ] || { echo "Python Moonraker absent : $MOONRAKER_PY" >&2; exit 2; }
[ -f "$KLIPPER_SRC/klippy/klippy.py" ] || { echo "Sources Klipper absentes." >&2; exit 2; }
[ -f "$MOONRAKER_SRC/moonraker/moonraker.py" ] || { echo "Sources Moonraker absentes." >&2; exit 2; }
[ -x "$NGINX_BIN" ] || { echo "Nginx absent : $NGINX_BIN" >&2; exit 2; }

stop_one() {
  name="$1"
  pidfile="$PIDDIR/$name.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    case "$pid" in ''|*[!0-9]*) ;; *) kill -TERM "$pid" 2>/dev/null || true ;; esac
    rm -f "$pidfile"
  fi
}
stop_services() {
  stop_one moonraker
  stop_one klipper
  stop_one nginx
}
if [ "${1:-start}" = "stop" ]; then stop_services; exit 0; fi

for name in nginx klipper moonraker; do
  pidfile="$PIDDIR/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile" 2>/dev/null || echo invalid)" 2>/dev/null; then
    echo "$name déjà démarré (PID $(cat "$pidfile"))." >&2
    exit 3
  fi
  rm -f "$pidfile"
done

export HOME="$KLIPPER_USER_HOME"
export PYTHONUNBUFFERED=1

"$NGINX_BIN" -c /etc/nginx/nginx.conf -g 'daemon off;' >>"$LOGS/nginx-supervisor.log" 2>&1 &
echo "$!" > "$PIDDIR/nginx.pid"

"$KLIPPER_PY" "$KLIPPER_SRC/klippy/klippy.py" "$CONFIG/printer.cfg" \
  -l "$LOGS/klippy.log" -a "$COMMS/klippy.sock" \
  >>"$LOGS/klipper-supervisor.log" 2>&1 &
echo "$!" > "$PIDDIR/klipper.pid"

"$MOONRAKER_PY" "$MOONRAKER_SRC/moonraker/moonraker.py" \
  -c "$CONFIG/moonraker.conf" -l "$LOGS/moonraker.log" \
  >>"$LOGS/moonraker-supervisor.log" 2>&1 &
echo "$!" > "$PIDDIR/moonraker.pid"

cleanup() { stop_services; wait 2>/dev/null || true; }
trap cleanup INT TERM HUP

klipper_warning_logged=0
echo "Miracle Klipper lancé. Mainsail reste disponible même si printer.cfg doit encore être configuré."
while :; do
  for name in nginx moonraker; do
    pidfile="$PIDDIR/$name.pid"
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    case "$pid" in ''|*[!0-9]*) echo "PID $name absent; arrêt du superviseur." >&2; exit 4 ;; esac
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$name s'est arrêté; vérifier $LOGS." >&2
      exit 5
    fi
  done

  # Klipper commonly exits until the printer-specific MCU/pin configuration
  # is completed. Keep Moonraker and Mainsail available so the user can inspect
  # logs and edit the configuration instead of losing the entire web UI.
  pidfile="$PIDDIR/klipper.pid"
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  if [ "$klipper_warning_logged" -eq 0 ]; then
    case "$pid" in
      ''|*[!0-9]*) echo "Klipper n'a pas de PID valide; Mainsail reste actif." >&2; klipper_warning_logged=1 ;;
      *)
        if ! kill -0 "$pid" 2>/dev/null; then
          echo "Klipper s'est arrêté. Vérifier $LOGS/klippy.log et compléter $CONFIG/printer.cfg. Moonraker/Mainsail restent actifs." >&2
          rm -f "$pidfile"
          klipper_warning_logged=1
        fi
        ;;
    esac
  fi
  sleep 2
done
