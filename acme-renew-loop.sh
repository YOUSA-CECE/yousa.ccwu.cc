#!/data/data/com.termux/files/usr/bin/bash

HOME=/data/data/com.termux/files/home

while true; do
    "$HOME/.acme.sh/acme.sh" --cron --home "$HOME/.acme.sh" \
        >>"$HOME/acme-renew.log" 2>&1 || true
    sleep 86400
done
