#!/bin/sh
# Miracle Klipper userspace service supervisor.
# Run this script INSIDE the Debian rootfs (for example via proot), not on Android.
# It intentionally does not depend on systemd/PID 1.
set -eu

ROOT="${MIRACLE_KLIPPER_ROOT:-/root}"
DATA="${MIRACLE_KLIPPER_DATA:-$ROOT/printer_data}"
LOGS="$DATA/logs"
COMMS="$DATA/comms"
CONFIG="$DATA/config"
PIDDIR="$DATA/run"
KLIPPER="$ROOT/klipper"
MOONRAKER="$ROOT/moonraker"
KLIPPER_PY="$ROOT/klipper-venv/bin/python"
MOONRAKER_PY="$ROOT/moonraker-venv/bin/python"
NGINX="${NGINX_BIN:-nginx}"

mkdir -p "$LOGS" "$COMMS" "$CONFIG" "$PIDDIR" "$DATA/gcodes"
[ -f "$CONFIG/printer.cfg" ] || {
  echo "Missing $CONFIG/printer.cfg. Add a valid printer configuration before starting." >&2
  exit 2
}
[ -x "$KLIPPER_PY" ] || { echo "Klipper Python environment missing: $KLIPPER_PY" >&2; exit 2; }
[ -x "$MOONRAKER_PY" ] || { echo "Moonraker Python environment missing: $MOONRAKER_PY" >&2; exit 2; }
[ -f "$KLIPPER/klippy/klippy.py" ] || { echo "Klipper source missing." >&2; exit 2; }
[ -f "$MOONRAKER/moonraker/moonraker.py" ] || { echo "Moonraker source missing." >&2; exit 2; }

stop_services() {
  for name in moonraker klipper nginx; do
    pidfile="$PIDDIR/$name.pid"
    if [ -f "$pidfile" ]; then
      pid="$(cat "$pidfile" 2>/dev/null || true)"
      case "$pid" in
        ''|*[!0-9]*) ;;
        *) kill -TERM "$pid" 2>/dev/null || true ;;
      esac
      rm -f "$pidfile"
    fi
  done
}
if [ "${1:-start}" = "stop" ]; then
  stop_services
  exit 0
fi

for name in klipper moonraker nginx; do
  pidfile="$PIDDIR/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "$name already running (PID $(cat "$pidfile"))." >&2
    exit 3
  fi
  rm -f "$pidfile"
done

export HOME="$ROOT"
export PYTHONUNBUFFERED=1
"$NGINX" -g 'daemon off;' >>"$LOGS/nginx.log" 2>&1 &
echo "$!" > "$PIDDIR/nginx.pid"

"$KLIPPER_PY" "$KLIPPER/klippy/klippy.py" "$CONFIG/printer.cfg" \
  -l "$LOGS/klippy.log" -I "$COMMS/klippy.serial" -a "$COMMS/klippy.sock" \
  >>"$LOGS/klipper-supervisor.log" 2>&1 &
echo "$!" > "$PIDDIR/klipper.pid"

"$MOONRAKER_PY" "$MOONRAKER/moonraker/moonraker.py" -d "$DATA" \
  >>"$LOGS/moonraker-supervisor.log" 2>&1 &
echo "$!" > "$PIDDIR/moonraker.pid"

cleanup() {
  stop_services
  wait 2>/dev/null || true
}
trap cleanup INT TERM HUP

while :; do
  for name in nginx klipper moonraker; do
    pidfile="$PIDDIR/$name.pid"
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    case "$pid" in
      ''|*[!0-9]*) echo "$name PID is missing; stopping supervisor." >&2; exit 4 ;;
    esac
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "$name exited; inspect $LOGS for details." >&2
      exit 5
    fi
  done
  sleep 2
done
