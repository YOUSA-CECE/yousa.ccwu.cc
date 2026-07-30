#!/data/data/com.termux/files/usr/bin/bash
set -u

PREFIX=/data/data/com.termux/files/usr
HOME=/data/data/com.termux/files/home
PROJECT="$HOME/yousa.ccwu.cc"
LOG="$HOME/server-mgr.log"
CLOUDFLARED_CONFIG="$HOME/.cloudflared/config.yml"
export PATH="$PREFIX/bin:$PATH"

log() {
    printf '[%s] %s\n' "$(date '+%F %T %Z')" "$*" >>"$LOG"
}

start_app() {
    cd "$PROJECT" || return 1
    gunicorn -w 1 --timeout 600 -b 127.0.0.1:8000 app:app \
        --daemon --access-logfile "$HOME/server-access.log" \
        --error-logfile "$HOME/server-error.log"
}

start_monitor() {
    cd "$PROJECT" || return 1
    MONITOR_PORT=5001 nohup python3 monitor.py \
        >>"$HOME/monitor.log" 2>&1 &
}

start_tunnel() {
    nohup cloudflared tunnel --config "$CLOUDFLARED_CONFIG" run \
        >>"$HOME/cloudflared.log" 2>&1 &
}

termux-wake-lock >/dev/null 2>&1 || true
log "manager started"

pgrep -f 'gunicorn.*127.0.0.1:5000.*app:app' >/dev/null || {
    start_app
    log "started web app on 127.0.0.1:5000"
}
pgrep -f 'python3.*monitor.py' >/dev/null || {
    start_monitor
    log "started monitor on 127.0.0.1:5001"
}
pgrep -f 'cloudflared tunnel.*config.*config.yml.*run' >/dev/null || {
    start_tunnel
    log "started Cloudflare tunnel"
}

while sleep 30; do
    pgrep -f 'gunicorn.*127.0.0.1:5000.*app:app' >/dev/null || {
        start_app
        log "watchdog restarted web app"
    }
    pgrep -f 'python3.*monitor.py' >/dev/null || {
        start_monitor
        log "watchdog restarted monitor"
    }
    pgrep -f 'cloudflared tunnel.*config.*config.yml.*run' >/dev/null || {
        start_tunnel
        log "watchdog restarted Cloudflare tunnel"
    }
done
