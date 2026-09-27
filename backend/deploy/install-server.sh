#!/bin/bash
# Run after copying backend/ and tools/news/ to /opt/tokentrail.
# This never modifies or restarts aibox_backend.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l
apt-get update -qq
apt-get install -y -qq nginx python3-venv
if ! id tokentrail >/dev/null 2>&1; then
    useradd --system --home /var/lib/tokentrail-forum --shell /usr/sbin/nologin tokentrail
fi
install -d -o tokentrail -g tokentrail -m 700 /var/lib/tokentrail-forum
install -d -m 755 /var/lib/tokentrail-acme/.well-known/acme-challenge
python3 -m venv /opt/tokentrail/venv
if [ -d /tmp/tokentrail-linux-wheels ]; then
    /opt/tokentrail/venv/bin/pip --isolated install --no-index --find-links /tmp/tokentrail-linux-wheels -r /opt/tokentrail/backend/requirements.txt
else
    /opt/tokentrail/venv/bin/pip --isolated install --index-url https://pypi.org/simple --timeout 20 -r /opt/tokentrail/backend/requirements.txt
fi
python3 -m venv /opt/tokentrail/certbot-venv
if [ -d /tmp/tokentrail-linux-wheels ]; then
    /opt/tokentrail/certbot-venv/bin/pip --isolated install --no-index --find-links /tmp/tokentrail-linux-wheels 'certbot>=5.4,<6'
else
    /opt/tokentrail/certbot-venv/bin/pip --isolated install --index-url https://pypi.org/simple --timeout 20 'certbot>=5.4,<6'
fi
if [ ! -e /etc/tokentrail-forum.env ]; then
    install -m 600 /opt/tokentrail/backend/deploy/forum.env.example /etc/tokentrail-forum.env
fi
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-forum.service /etc/systemd/system/
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-news.service /etc/systemd/system/
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-news.timer /etc/systemd/system/
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-backup.service /etc/systemd/system/
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-backup.timer /etc/systemd/system/
install -m 644 /opt/tokentrail/backend/deploy/nginx-http.conf /etc/nginx/sites-available/tokentrail-forum
ln -sfn /etc/nginx/sites-available/tokentrail-forum /etc/nginx/sites-enabled/tokentrail-forum
nginx -t
systemctl daemon-reload
systemctl enable --now tokentrail-forum.service tokentrail-news.timer tokentrail-backup.timer nginx.service
systemctl reload nginx
