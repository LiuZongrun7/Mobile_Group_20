#!/bin/bash
set -euo pipefail
install -d -o tokentrail -g tokentrail -m 700 /var/lib/tokentrail-forum-test
cd /opt/tokentrail/backend
runuser -u tokentrail -- /opt/tokentrail/venv/bin/python -c 'from tokentrail_forum.store import Store; from tokentrail_forum.news_job import export_news; export_news(Store("/var/lib/tokentrail-forum"))'
if [ ! -e /etc/tokentrail-forum-test.env ]; then
    install -m 600 /opt/tokentrail/backend/deploy/forum-test.env.example /etc/tokentrail-forum-test.env
fi
install -m 644 /opt/tokentrail/backend/deploy/tokentrail-forum-test.service /etc/systemd/system/
install -m 644 /opt/tokentrail/backend/deploy/nginx-https.conf /etc/nginx/sites-available/tokentrail-forum
nginx -t
systemctl daemon-reload
systemctl restart tokentrail-forum.service
systemctl enable --now tokentrail-forum-test.service
systemctl reload nginx
