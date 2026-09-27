#!/bin/bash
set -euo pipefail
/opt/tokentrail/certbot-venv/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email \
    --preferred-profile shortlived --webroot --webroot-path /var/lib/tokentrail-acme \
    --ip-address 43.140.212.47 --cert-name tokentrail-ip
install -m 644 /opt/tokentrail/backend/deploy/nginx-https.conf /etc/nginx/sites-available/tokentrail-forum
nginx -t
systemctl reload nginx
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-certbot.service /etc/systemd/system/
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-certbot.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now tokentrail-certbot.timer
