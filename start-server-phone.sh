#!/data/data/com.termux/files/usr/bin/bash

PREFIX=/data/data/com.termux/files/usr
HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"

termux-wake-lock >/dev/null 2>&1 || true
mkdir -p "$HOME/service-logs"

if ! pgrep -f '/server-manager.sh' >/dev/null; then
    nohup "$HOME/server-manager.sh" >>"$HOME/service-logs/manager-launch.log" 2>&1 &
fi

if ! pgrep -f '/cloudflare-ddns-loop.sh' >/dev/null; then
    nohup "$HOME/cloudflare-ddns-loop.sh" >>"$HOME/cloudflare-ddns.log" 2>&1 &
fi

if ! pgrep -f '/acme-renew-loop.sh' >/dev/null; then
    nohup "$HOME/acme-renew-loop.sh" >>"$HOME/acme-renew.log" 2>&1 &
fi

printf '[%s] Termux:Boot launch completed\n' "$(date '+%F %T %Z')" >>"$HOME/boot.log"
