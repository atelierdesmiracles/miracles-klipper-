#!/bin/bash
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
export PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_NO_INPUT=1

LOG=/var/log/miracle-klipper-setup.log
mkdir -p /var/log /opt/miracle-klipper /var/lib/miracle/printer_data/{config,logs,gcodes,comms}
exec > >(tee -a "$LOG") 2>&1
trap 'rc=$?; echo "[ERROR] setup failed at line $LINENO (exit $rc)"; exit "$rc"' ERR

echo "[Miracle Klipper] Installing runtime dependencies..."
apt-get update
apt-get install -y --no-install-recommends \
  bash ca-certificates curl git nginx unzip python3 python3-venv python3-dev \
  build-essential libffi-dev libjpeg62-turbo libopenjp2-7 libusb-1.0-0 \
  liblmdb0 libopenblas0 libjpeg-dev zlib1g-dev libssl-dev

cd /opt/miracle-klipper
if [ ! -d klipper/.git ]; then git clone --depth 1 https://github.com/Klipper3d/klipper.git klipper; fi
if [ ! -d moonraker/.git ]; then git clone --depth 1 https://github.com/Arksine/moonraker.git moonraker; fi

echo "[Miracle Klipper] Creating isolated Python environments..."
python3 -m venv --system-site-packages /opt/miracle-klipper/venvs/klipper
/opt/miracle-klipper/venvs/klipper/bin/python -m pip install --upgrade pip setuptools wheel
/opt/miracle-klipper/venvs/klipper/bin/pip install -r /opt/miracle-klipper/klipper/scripts/klippy-requirements.txt

python3 -m venv --system-site-packages /opt/miracle-klipper/venvs/moonraker
/opt/miracle-klipper/venvs/moonraker/bin/python -m pip install --upgrade pip setuptools wheel
/opt/miracle-klipper/venvs/moonraker/bin/pip install -r /opt/miracle-klipper/moonraker/scripts/moonraker-requirements.txt

echo "[Miracle Klipper] Installing Mainsail..."
mkdir -p /var/www/mainsail
curl --fail --location --retry 3 --connect-timeout 20 \
  https://github.com/mainsail-crew/mainsail/releases/latest/download/mainsail.zip \
  -o /tmp/mainsail.zip
unzip -q -o /tmp/mainsail.zip -d /tmp/mainsail-release
if [ -d /tmp/mainsail-release/mainsail ]; then
  cp -a /tmp/mainsail-release/mainsail/. /var/www/mainsail/
else
  cp -a /tmp/mainsail-release/. /var/www/mainsail/
fi
rm -rf /tmp/mainsail.zip /tmp/mainsail-release

mkdir -p /var/lib/miracle/printer_data/config /var/lib/miracle/printer_data/logs /var/lib/miracle/printer_data/gcodes /var/lib/miracle/printer_data/comms
if [ ! -f /var/lib/miracle/printer_data/config/moonraker.conf ]; then
cat > /var/lib/miracle/printer_data/config/moonraker.conf <<'EOF'
[server]
host: 0.0.0.0
port: 7125
klippy_uds_address: /var/lib/miracle/printer_data/comms/klippy.sock

[authorization]
trusted_clients:
  127.0.0.0/8
  10.0.0.0/8
  172.16.0.0/12
  192.168.0.0/16
  100.64.0.0/10
cors_domains:
  *.local
  *.lan
  localhost
  127.0.0.1

[file_manager]
enable_object_processing: True
config_path: /var/lib/miracle/printer_data/config

[history]

[announcements]

[update_manager]
enable_auto_refresh: False
EOF
fi

if [ ! -f /var/lib/miracle/printer_data/config/printer.cfg ]; then
cat > /var/lib/miracle/printer_data/config/printer.cfg <<'EOF'
# Configure your printer's MCU, stepper motors, heaters and limits before starting Klipper.
# This starter file intentionally contains no guessed hardware settings.
EOF
fi

cat > /etc/nginx/sites-available/miracle-klipper <<'EOF'
server {
    listen 8080 default_server;
    listen [::]:8080 default_server;
    server_name _;
    root /var/www/mainsail;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /websocket {
        proxy_pass http://127.0.0.1:7125/websocket;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $http_host;
        proxy_read_timeout 3600s;
    }

    location ~ ^/(printer|api|access|machine|server|debug|announcements|webhooks|register_remote_method) {
        proxy_pass http://127.0.0.1:7125$request_uri;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
EOF
rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/miracle-klipper /etc/nginx/sites-enabled/miracle-klipper
nginx -t

cat > /usr/local/bin/miracle-klipper-start <<'EOF'
#!/bin/bash
set -eu
DATA=/var/lib/miracle/printer_data
mkdir -p "$DATA/logs" "$DATA/comms"
if ! pgrep -f '[m]oonraker/moonraker.py' >/dev/null; then
  nohup /opt/miracle-klipper/venvs/moonraker/bin/python /opt/miracle-klipper/moonraker/moonraker.py -c "$DATA/config/moonraker.conf" >> "$DATA/logs/moonraker.log" 2>&1 &
fi
if ! pgrep -f '[n]ginx: master process' >/dev/null; then
  nginx
fi
if grep -Eq '^\[mcu\][[:space:]]*
EOF
chmod +x /usr/local/bin/miracle-klipper-start

cat > /usr/local/bin/miracle-klipper-stop <<'EOF'
#!/bin/bash
set +e
pkill -f '[m]oonraker/moonraker.py'
pkill -f '[k]lippy.py'
nginx -s quit
EOF
chmod +x /usr/local/bin/miracle-klipper-stop

cat > /usr/local/bin/miracle-klipper-status <<'EOF'
#!/bin/bash
echo "Klipper: installed; start only after configuring printer.cfg"
if pgrep -f '[m]oonraker/moonraker.py' >/dev/null; then echo "Moonraker: running"; else echo "Moonraker: stopped"; fi
if pgrep -f '[n]ginx: master process' >/dev/null; then echo "Mainsail/nginx: running"; else echo "Mainsail/nginx: stopped"; fi
if pgrep -f '[k]lippy.py' >/dev/null; then echo "Klipper: running"; fi
EOF
chmod +x /usr/local/bin/miracle-klipper-status

echo "[Miracle Klipper] Installed successfully."
 "$DATA/config/printer.cfg" && grep -Eq '^\[printer\][[:space:]]*
EOF
chmod +x /usr/local/bin/miracle-klipper-start

cat > /usr/local/bin/miracle-klipper-stop <<'EOF'
#!/bin/bash
set +e
pkill -f '[m]oonraker/moonraker.py'
nginx -s quit
EOF
chmod +x /usr/local/bin/miracle-klipper-stop

cat > /usr/local/bin/miracle-klipper-status <<'EOF'
#!/bin/bash
echo "Klipper: installed; start only after configuring printer.cfg"
if pgrep -f '[m]oonraker/moonraker.py' >/dev/null; then echo "Moonraker: running"; else echo "Moonraker: stopped"; fi
if pgrep -f '[n]ginx: master process' >/dev/null; then echo "Mainsail/nginx: running"; else echo "Mainsail/nginx: stopped"; fi
EOF
chmod +x /usr/local/bin/miracle-klipper-status

echo "[Miracle Klipper] Installed successfully."
 "$DATA/config/printer.cfg"; then
  if ! pgrep -f '[k]lippy.py' >/dev/null; then
    nohup /opt/miracle-klipper/venvs/klipper/bin/python /opt/miracle-klipper/klipper/klippy/klippy.py "$DATA/config/printer.cfg" -I /tmp/printer -a "$DATA/comms/klippy.sock" >> "$DATA/logs/klipper.log" 2>&1 &
  fi
else
  echo "Klipper is installed; add valid [mcu] and [printer] sections to printer.cfg before starting the printer service."
fi
echo "Mainsail: http://127.0.0.1:8080/"
echo "Moonraker API: http://127.0.0.1:7125/"
EOF
chmod +x /usr/local/bin/miracle-klipper-start

cat > /usr/local/bin/miracle-klipper-stop <<'EOF'
#!/bin/bash
set +e
pkill -f '[m]oonraker/moonraker.py'
nginx -s quit
EOF
chmod +x /usr/local/bin/miracle-klipper-stop

cat > /usr/local/bin/miracle-klipper-status <<'EOF'
#!/bin/bash
echo "Klipper: installed; start only after configuring printer.cfg"
if pgrep -f '[m]oonraker/moonraker.py' >/dev/null; then echo "Moonraker: running"; else echo "Moonraker: stopped"; fi
if pgrep -f '[n]ginx: master process' >/dev/null; then echo "Mainsail/nginx: running"; else echo "Mainsail/nginx: stopped"; fi
EOF
chmod +x /usr/local/bin/miracle-klipper-status

echo "[Miracle Klipper] Installed successfully."
