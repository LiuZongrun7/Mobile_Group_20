#!/bin/bash
# Run after copying backend/ and tools/news/ to /opt/modelpilot.
# This never modifies or restarts aibox_backend.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l
apt-get update -qq
apt-get install -y -qq nginx python3-venv
if ! id tokentrail >/dev/null 2>&1; then
    useradd --system --home /var/lib/modelpilot-forum --shell /usr/sbin/nologin tokentrail
fi
install -d -o tokentrail -g tokentrail -m 700 /var/lib/modelpilot-forum
install -d -m 755 /var/lib/modelpilot-acme/.well-known/acme-challenge
python3 -m venv /opt/modelpilot/venv
if [ -d /tmp/tokentrail-linux-wheels ]; then
    /opt/modelpilot/venv/bin/pip --isolated install --no-index --find-links /tmp/tokentrail-linux-wheels -r /opt/modelpilot/backend/requirements.txt
else
    /opt/modelpilot/venv/bin/pip --isolated install --index-url https://pypi.org/simple --timeout 20 -r /opt/modelpilot/backend/requirements.txt
fi
python3 -m venv /opt/modelpilot/certbot-venv
if [ -d /tmp/tokentrail-linux-wheels ]; then
    /opt/modelpilot/certbot-venv/bin/pip --isolated install --no-index --find-links /tmp/tokentrail-linux-wheels 'certbot>=5.4,<6'
else
    /opt/modelpilot/certbot-venv/bin/pip --isolated install --index-url https://pypi.org/simple --timeout 20 'certbot>=5.4,<6'
fi
if [ ! -e /etc/modelpilot-forum.env ]; then
    install -m 600 /opt/modelpilot/backend/deploy/forum.env.example /etc/modelpilot-forum.env
fi
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-forum.service /etc/systemd/system/
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-news.service /etc/systemd/system/
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-news.timer /etc/systemd/system/
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-backup.service /etc/systemd/system/
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-backup.timer /etc/systemd/system/
install -m 644 /opt/modelpilot/backend/deploy/nginx-http.conf /etc/nginx/sites-available/modelpilot-forum
ln -sfn /etc/nginx/sites-available/modelpilot-forum /etc/nginx/sites-enabled/modelpilot-forum
nginx -t
systemctl daemon-reload
systemctl enable --now modelpilot-forum.service modelpilot-news.timer modelpilot-backup.timer nginx.service
systemctl reload nginx
