#!/bin/bash
set -euo pipefail
/opt/modelpilot/certbot-venv/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email \
    --preferred-profile shortlived --webroot --webroot-path /var/lib/modelpilot-acme \
    --ip-address 43.140.212.47 --cert-name tokentrail-ip
install -m 644 /opt/modelpilot/backend/deploy/nginx-https.conf /etc/nginx/sites-available/modelpilot-forum
nginx -t
systemctl reload nginx
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-certbot.service /etc/systemd/system/
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-certbot.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now modelpilot-certbot.timer
