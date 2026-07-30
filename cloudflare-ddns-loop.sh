#!/data/data/com.termux/files/usr/bin/bash

HOME=/data/data/com.termux/files/home

while true; do
    "$HOME/cloudflare-ddns.sh" >/dev/null 2>&1 || true
    sleep 300
done
