#!/bin/bash
set -euo pipefail
install -d -o tokentrail -g tokentrail -m 700 /var/lib/modelpilot-forum-test
cd /opt/modelpilot/backend
runuser -u tokentrail -- /opt/modelpilot/venv/bin/python -c 'from modelpilot_forum.store import Store; from modelpilot_forum.news_job import export_news; export_news(Store("/var/lib/modelpilot-forum"))'
if [ ! -e /etc/modelpilot-forum-test.env ]; then
    install -m 600 /opt/modelpilot/backend/deploy/forum-test.env.example /etc/modelpilot-forum-test.env
fi
install -m 644 /opt/modelpilot/backend/deploy/modelpilot-forum-test.service /etc/systemd/system/
install -m 644 /opt/modelpilot/backend/deploy/nginx-https.conf /etc/nginx/sites-available/modelpilot-forum
nginx -t
systemctl daemon-reload
systemctl restart modelpilot-forum.service
systemctl enable --now modelpilot-forum-test.service
systemctl reload nginx
