#!/data/data/com.termux/files/usr/bin/bash
set -eu

HOME=/data/data/com.termux/files/home
DOMAIN=direct.yousa.ccwu.cc

"$HOME/.acme.sh/acme.sh" \
    --install-cert \
    --ecc \
    --domain "$DOMAIN" \
    --key-file "$HOME/.ssl/direct.yousa.ccwu.cc.key" \
    --fullchain-file "$HOME/.ssl/direct.yousa.ccwu.cc.fullchain.pem" \
    --reloadcmd "/data/data/com.termux/files/usr/bin/nginx -s reload"
